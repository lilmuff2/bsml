package lilmuff1.bsml.modpatch

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import android.util.Log
import java.util.zip.ZipFile
import lilmuff1.bsml.state.PreparedModFile
import lilmuff1.bsml.state.VpnLogRepository
import org.json.JSONArray
import org.json.JSONObject

class NbAssetsCompiler(
    private val context: Context,
    private val enabledFeatureIds: Set<String> = emptySet(),
    private val onStage: (stage: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val LAST_MERGED_PATCH_JSON_FILE = "last_nbassets_patch.json"

        @Volatile
        private var lastMergedPatchJson: String? = null

        fun lastMergedPatchJsonForTest(): String? = lastMergedPatchJson

        fun lastMergedPatchJsonFile(context: Context): File {
            return File(context.filesDir, LAST_MERGED_PATCH_JSON_FILE)
        }
    }

    fun compile(archive: File, outputDir: File, clearOutput: Boolean = true): List<PreparedModFile> {
        val startedAt = System.nanoTime()
        onStage(lilmuff1.bsml.state.ModFilesRepository.STAGE_ARCHIVE, 0, 0)
        VpnLogRepository.log("NBASSETS prepare start file=${archive.name} clearOutput=$clearOutput")
        if (clearOutput && outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()

        val contentJson = NbAssetsArchiveReader.readRootContentJson(archive)
        val provider = OriginalAssetProvider(context, outputDir)
        val contentParts = buildContentParts(contentJson)
        val activePatchFiles = contentParts.flatMap { patchFilePaths(it.json) }.toSet()
        val allPatchFiles = collectAllPatchFilePaths(contentJson)
        val activeRoots = contentParts.map { it.root }.toSet()
        val inactiveFeatureRoots = collectAllFeatureRoots(contentJson) - activeRoots
        val patchJson = JSONObject()
        val roots = LinkedHashSet<String>()
        val prepared = LinkedHashMap<String, PreparedModFile>()
        var assetCount = 0
        var csvCount = 0

        contentParts.forEach { part ->
            roots += part.root
            val partPatchFiles = patchFilePaths(part.json)
            if (partPatchFiles.isEmpty()) {
                mergeTables(patchJson, part.json)
            } else {
                partPatchFiles.forEach { patchPath ->
                    val patch = JSONObject(readArchiveBytes(archive, patchPath).decodeToString())
                    mergeTables(patchJson, patch)
                }
            }
        }

        val archiveFiles = listArchiveFiles(archive)
        var scannedFiles = 0
        archiveFiles.forEach { path ->
            scannedFiles++
            onStage(lilmuff1.bsml.state.ModFilesRepository.STAGE_ARCHIVE, scannedFiles, archiveFiles.size)
            val assetPath = archiveAssetPath(path, roots.toList(), allPatchFiles, inactiveFeatureRoots) ?: return@forEach
            prepared[assetPath] = if (archive.isDirectory) {
                val sourceFile = File(archive, path)
                PreparedModFile(
                    path = assetPath,
                    sha = sha1Hex(sourceFile.readBytes()),
                    uri = Uri.fromFile(sourceFile).toString(),
                    size = sourceFile.length(),
                    lastModified = sourceFile.lastModified()
                )
            } else {
                val output = File(outputDir, assetPath)
                output.parentFile?.mkdirs()
                val bytes = readArchiveBytes(archive, path)
                output.writeBytes(bytes)
                PreparedModFile(
                    path = assetPath,
                    sha = sha1Hex(bytes),
                    uri = Uri.fromFile(output).toString(),
                    size = output.length(),
                    lastModified = output.lastModified()
                )
            }
            assetCount++
        }
        val currentPatchJson = patchJson.toString()
        val mergedPatchJson = if (clearOutput) {
            currentPatchJson
        } else {
            mergePatchJsonStrings(lastMergedPatchJsonFile(context).takeIf { it.isFile }?.readText(), currentPatchJson)
        }
        lastMergedPatchJson = mergedPatchJson
        lastMergedPatchJsonFile(context).writeText(mergedPatchJson)
        VpnLogRepository.log("NBASSETS prepare assets=$assetCount patches=${activePatchFiles.size} parts=${contentParts.size} file=${archive.name}")
        val tableNames = patchJson.keysList().sorted()
        tableNames
            .sorted()
            .forEachIndexed { tableIndex, tableName ->
                onStage(lilmuff1.bsml.state.ModFilesRepository.STAGE_CSV, tableIndex, tableNames.size)
                val tableStartedAt = System.nanoTime()
                runCatching {
                    val patch = patchJson.optJSONObject(tableName) ?: return@forEachIndexed
                    val original = provider.resolveCsv(tableName)
                    val table = CsvTable.load(original.bytes)
                    val resolvedPatch = CsvPatchApplier.resolveWildcards(patch, table)
                    CsvPatchApplier.apply(table, resolvedPatch)
                    val output = File(outputDir, original.path)
                    output.parentFile?.mkdirs()
                    output.writeBytes(table.toCsvBytes())
                    prepared[original.path] = PreparedModFile(
                        path = original.path,
                        sha = sha1Hex(output.readBytes()),
                        uri = Uri.fromFile(output).toString(),
                        size = output.length(),
                        lastModified = output.lastModified()
                    )
                    csvCount++
                    onStage(lilmuff1.bsml.state.ModFilesRepository.STAGE_CSV, tableIndex + 1, tableNames.size)
                    VpnLogRepository.log("NBASSETS csv table=$tableName path=${original.path} ms=${elapsedMs(tableStartedAt)}")
                }.getOrElse { error ->
                    VpnLogRepository.log(
                        "NBASSETS prepare csv failed table=$tableName error=${error::class.java.simpleName}: ${error.message ?: "unknown"}"
                    )
                    VpnLogRepository.log("NBASSETS prepare stack ${Log.getStackTraceString(error)}")
                    throw error
                }
            }

        val result = prepared.values.sortedBy { it.path }
        VpnLogRepository.log("NBASSETS prepare done files=${result.size} csv=$csvCount assets=$assetCount ms=${elapsedMs(startedAt)}")
        return result
    }

    private fun archiveAssetPath(
        path: String,
        roots: List<String>,
        patchFiles: Set<String>,
        inactiveFeatureRoots: Set<String>
    ): String? {
        if (path.startsWith("META-INF/") || path.startsWith("build/")) return null
        if (path == "metadata.json" || path == "feature_selection.json" || path == "mod_state.json") return null
        if (patchFiles.contains(path)) return null
        if (inactiveFeatureRoots.any { root -> path == root || path.startsWith("$root/") }) return null

        val orderedRoots = roots
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length } +
            roots.filter { it.isEmpty() }
        val relative = orderedRoots.asSequence()
            .mapNotNull { root ->
                if (root.isEmpty()) {
                    path
                } else {
                    val prefix = "$root/"
                    if (path.startsWith(prefix)) path.removePrefix(prefix) else null
                }
            }
            .firstOrNull()
            ?: return null

        if (relative == ROOT_CONTENT_JSON) return null
        if (relative.endsWith(".csv")) return null
        if (relative.startsWith("icon") && relative.endsWith(".png") && relative == relative.substringAfterLast('/')) return null
        return relative.ifEmpty { null }?.toFingerprintPath()
    }

    private fun normalizePath(path: String): String {
        return path.replace('\\', '/').trimStart('/')
    }

    private fun listArchiveFiles(source: File): List<String> {
        if (source.isDirectory) {
            return source.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(source).invariantSeparatorsPath }
                .toList()
        }
        ZipFile(source).use { zip ->
            return zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name }
                .toList()
        }
    }

    private fun readArchiveBytes(source: File, path: String): ByteArray {
        if (source.isDirectory) {
            return File(source, path).readBytes()
        }
        ZipFile(source).use { zip ->
            val entry = zip.getEntry(path) ?: error("file not found: $path")
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun String.toFingerprintPath(): String {
        return replace(" ", "")
    }

    private fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun sha1Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun JSONObject.keysList(): List<String> {
        val result = ArrayList<String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            result += iterator.next()
        }
        return result
    }

    private fun patchFilePaths(contentJson: JSONObject): List<String> {
        val value = contentJson.opt("@patches") ?: return emptyList()
        return when (value) {
            is JSONArray -> buildList {
                for (index in 0 until value.length()) {
                    value.optString(index, "").normalizeArchivePathOrNull()?.let(::add)
                }
            }
            is String -> listOfNotNull(value.normalizeArchivePathOrNull())
            else -> emptyList()
        }
    }

    private fun collectAllPatchFilePaths(contentJson: JSONObject): Set<String> {
        val result = LinkedHashSet<String>()
        result += patchFilePaths(contentJson)
        val features = contentJson.optJSONObject("@features") ?: return result
        features.keysList().forEach { key ->
            features.optJSONObject(key)?.let { featureJson ->
                result += patchFilePaths(featureJson)
            }
        }
        return result
    }

    private fun collectAllFeatureRoots(contentJson: JSONObject): Set<String> {
        val features = contentJson.optJSONObject("@features") ?: return emptySet()
        return features.keysList()
            .mapNotNull { key ->
                features.optJSONObject(key)
                    ?.takeIf { it.has("@root") }
                    ?.optString("@root", "")
                    ?.normalizeRoot()
                    ?.takeIf { it.isNotEmpty() }
            }
            .toSet()
    }

    private fun String.normalizeArchivePathOrNull(): String? {
        return replace('\\', '/').trimStart('/').takeIf { it.isNotBlank() }
    }

    private fun buildContentParts(contentJson: JSONObject): List<ContentPart> {
        val result = ArrayList<ContentPart>()
        result += ContentPart(
            json = contentJson,
            root = contentJson.optString("@root", "").normalizeRoot(),
            key = null
        )

        val features = contentJson.optJSONObject("@features") ?: return result
        features.keysList()
            .map { key -> key to features.getJSONObject(key) }
            .filter { (key, _) -> key in enabledFeatureIds }
            .sortedWith { left, right ->
                val priorityCompare = left.second.optInt("@priority", 0).compareTo(right.second.optInt("@priority", 0))
                if (priorityCompare != 0) priorityCompare else left.first.compareTo(right.first)
            }
            .forEach { (key, json) ->
                result += ContentPart(
                    json = json,
                    root = json.optString("@root", "").normalizeRoot(),
                    key = key
                )
            }
        return result
    }

    private fun String.normalizeRoot(): String {
        return replace('\\', '/').trim('/')
    }

    private fun mergeTables(base: JSONObject, updates: JSONObject) {
        updates.keysList()
            .filterNot(::isNbAssetsMetadataKey)
            .forEach { key ->
                val incoming = updates.get(key)
                val current = base.opt(key)
                if (current is JSONObject && incoming is JSONObject) {
                    mergeTables(current, incoming)
                } else {
                    base.put(key, incoming)
                }
            }
    }

    private fun mergePatchJsonStrings(baseJson: String?, updateJson: String): String {
        val base = baseJson?.takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
        mergeTables(base, JSONObject(updateJson))
        return base.toString()
    }
}

private data class ContentPart(
    val json: JSONObject,
    val root: String,
    val key: String?
)

private fun elapsedMs(startedAtNanos: Long): String {
    return "%.3f".format((System.nanoTime() - startedAtNanos) / 1_000_000.0)
}
