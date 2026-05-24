package lilmuff1.bsml.state

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lilmuff1.bsml.modpatch.NbAssetsCompiler
import lilmuff1.bsml.service.LoginFailedRewritePrewarmer
import org.json.JSONArray
import org.json.JSONObject

data class PreparedModFile(
    val path: String,
    val sha: String,
    val uri: String,
    val size: Long,
    val lastModified: Long
)

data class ModPreparationState(
    val folderName: String? = null,
    val isPreparing: Boolean = false,
    val stage: String? = null,
    val preparedCount: Int = 0,
    val totalCount: Int = 0,
    val isReady: Boolean = false,
    val error: String? = null
) {
    val progress: Float
        get() = if (totalCount <= 0) 0f else preparedCount.toFloat() / totalCount.toFloat()
}

object ModFilesRepository {
    const val STAGE_ARCHIVE = "archive"
    const val STAGE_CSV = "csv"
    const val STAGE_ORIGINAL_ASSETS = "original_assets"
    const val STAGE_SAVING = "saving"

    private const val PREPARED_INDEX_FILE = "prepared_mod_index.json"
    private const val PREPARED_FILES_DIR = "prepared_mod_files"
    private const val PREPARE_SIGNATURE_KEY = "prepareSignature"
    private val prepareDispatcher = Dispatchers.IO.limitedParallelism(PREPARE_FILE_PARALLELISM)
    private val prepareMutex = Mutex()

    private val _preparation = MutableStateFlow(ModPreparationState())
    val preparation = _preparation.asStateFlow()
    @Volatile
    private var preparedFilesCache: List<PreparedModFile> = emptyList()
    @Volatile
    private var preparedStateSignature: String = ""

    fun refreshState(context: Context) {
        ImportedModRepository.refreshState(context)
        refreshPreparedStateFromImportedMod(context)
    }

    fun refreshPreparedStateFromImportedMod(context: Context) {
        val imported = ImportedModRepository.state.value
        val folderName = imported.metadata?.title
            ?: imported.fileName
            ?: OriginalAssetsRepository.state.value.folderName
        val prepared = listPreparedFiles(context)
        _preparation.value = ModPreparationState(
            folderName = folderName,
            isPreparing = false,
            preparedCount = prepared.size,
            totalCount = prepared.size,
            isReady = folderName != null && prepared.isNotEmpty(),
            error = null
        )
    }

    suspend fun prepareFiles(context: Context): Boolean = prepareMutex.withLock {
        withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            if (LatestFingerprintStore.readStoredClientHelloHash(context.filesDir).isNullOrBlank()) {
                _preparation.value = _preparation.value.copy(
                    isPreparing = false,
                    isReady = false,
                    error = "fingerprint_not_loaded"
                )
                VpnLogRepository.log("PREPARE abort fingerprint_not_loaded")
                return@withContext false
            }
            val enabledArchives = ImportedModRepository.enabledArchives(context)
            if (enabledArchives.isNotEmpty()) {
                VpnLogRepository.log("PREPARE mode=imported enabledMods=${enabledArchives.size}")
                return@withContext prepareImportedArchives(context, enabledArchives)
            }
            if (ImportedModRepository.hasActiveMod(context)) {
                VpnLogRepository.log("PREPARE mode=original-assets-only importedModsPresent=true enabledMods=0")
                return@withContext prepareOriginalAssetsOnly(context)
            }
            if (OriginalAssetsRepository.state.value.folderName != null) {
                VpnLogRepository.log("PREPARE mode=original-assets-only importedModsPresent=false enabledMods=0")
                return@withContext prepareOriginalAssetsOnly(context)
            }

            _preparation.value = ModPreparationState(error = "folder_not_selected")
            VpnLogRepository.log("PREPARE abort folder_not_selected")
            false
        }
    }

    fun listPreparedFiles(context: Context): List<PreparedModFile> {
        if (preparedFilesCache.isNotEmpty()) return preparedFilesCache
        val indexFile = File(context.filesDir, PREPARED_INDEX_FILE)
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(indexFile.readText())
            val files = root.optJSONArray("files") ?: JSONArray()
            buildList {
                for (index in 0 until files.length()) {
                    val item = files.optJSONObject(index) ?: continue
                    val path = item.optString("path", "").trim()
                    val sha = item.optString("sha", "").trim()
                    val uri = item.optString("uri", "").trim()
                    val size = item.optLong("size", -1L)
                    val lastModified = item.optLong("lastModified", -1L)
                    if (path.isNotEmpty() && sha.isNotEmpty() && uri.isNotEmpty()) {
                        add(
                            PreparedModFile(
                                path = path,
                                sha = sha,
                                uri = uri,
                                size = size,
                                lastModified = lastModified
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList()).also {
            preparedFilesCache = it
            preparedStateSignature = buildPreparedStateSignature(it)
        }
    }

    fun readPreparedRootSha(context: Context): String? {
        val indexFile = File(context.filesDir, PREPARED_INDEX_FILE)
        if (!indexFile.isFile) return null
        return runCatching {
            JSONObject(indexFile.readText())
                .optString("preparedRootSha", "")
                .trim()
                .takeUnless { it == "null" }
                ?.ifEmpty { null }
        }.getOrNull()
    }

    fun listPreparedPaths(context: Context): List<String> {
        return listPreparedFiles(context).map { it.path }
    }

    fun getPreparedSha(context: Context, path: String): String? {
        return findPreparedFile(context, path)?.sha
    }

    fun findPreparedFile(context: Context, path: String): PreparedModFile? {
        return listPreparedFiles(context).firstOrNull { it.path == normalizePath(path) }
    }

    fun openPreparedFile(context: Context, relativePath: String): ByteArray? {
        val entry = findPreparedFile(context, relativePath) ?: return null
        return runCatching {
            openPreparedInputStream(context, Uri.parse(entry.uri))?.use { it.readBytes() }
        }.getOrNull()
    }

    fun openPreparedInputStream(context: Context, uri: Uri): java.io.InputStream? {
        return if (uri.scheme == "file") {
            uri.path?.let { File(it).inputStream() }
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    fun getPreparedStateSignature(context: Context): String {
        val cached = preparedStateSignature
        if (cached.isNotEmpty()) return cached
        val signature = buildPreparedStateSignature(listPreparedFiles(context))
        preparedStateSignature = signature
        return signature
    }

    fun clearPreparedFiles(context: Context) {
        preparedFilesCache = emptyList()
        preparedStateSignature = ""
        File(context.filesDir, PREPARED_INDEX_FILE).delete()
        File(context.filesDir, PREPARED_FILES_DIR).deleteRecursively()
    }

    private suspend fun prepareImportedArchives(context: Context, archives: List<File>): Boolean = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        ImportedModRepository.refreshState(context)
        val imported = ImportedModRepository.state.value
        val folderName = imported.metadata?.title ?: imported.fileName ?: archives.first().name
        val prepareSignature = buildImportedPrepareSignature(context, imported, archives)
        val cachedPrepared = listPreparedFiles(context)
        if (cachedPrepared.isNotEmpty() && readPreparedPrepareSignature(context) == prepareSignature && preparedFilesExist(cachedPrepared)) {
            _preparation.value = ModPreparationState(
                folderName = folderName,
                isPreparing = false,
                preparedCount = cachedPrepared.size,
                totalCount = cachedPrepared.size,
                isReady = true,
                error = null
            )
            VpnLogRepository.log("NBASSETS prepare skip cache files=${cachedPrepared.size} ms=${elapsedMs(startedAt)}")
            LoginFailedRewritePrewarmer.prewarm(context)
            return@withContext true
        }
        _preparation.value = ModPreparationState(
            folderName = folderName,
            isPreparing = true,
            stage = STAGE_ARCHIVE,
            preparedCount = 0,
            totalCount = 0,
            isReady = false,
            error = null
        )
        runCatching {
            VpnLogRepository.log(
                "NBASSETS prepare imported mods=${archives.size} order=${archives.map { it.name }}"
            )
            val outputDir = File(context.filesDir, PREPARED_FILES_DIR)
            outputDir.deleteRecursively()
            val preparedMap = LinkedHashMap<String, PreparedModFile>()
            val importedModsById = imported.mods.associateBy { it.id }
            preloadOriginalAssetsIntoPreparedMap(
                context = context,
                folderName = folderName,
                preparedMap = preparedMap,
                startedAt = startedAt
            )
            archives.forEachIndexed { index, archive ->
                val modId = archive.name
                val currentImported = importedModsById[modId] ?: return@forEachIndexed
                VpnLogRepository.log(
                    "NBASSETS prepare imported mod=${currentImported.fileName} enabled=${currentImported.isEnabled} selected=${currentImported.featureSelection.enabledFeatureIds.sorted()}"
                )
                if (!currentImported.isEnabled) return@forEachIndexed
                val beforeKeys = preparedMap.keys.toSet()
                val prepared = NbAssetsCompiler(
                    context = context,
                    enabledFeatureIds = currentImported.featureSelection.enabledFeatureIds,
                    onStage = { stage, done, total ->
                        _preparation.value = ModPreparationState(
                            folderName = folderName,
                            isPreparing = true,
                            stage = stage,
                            preparedCount = done,
                            totalCount = total,
                            isReady = false,
                            error = null
                        )
                    }
                ).compile(
                    archive = archive,
                    outputDir = outputDir,
                    clearOutput = index == 0
                )
                var added = 0
                var replaced = 0
                prepared.forEach {
                    if (it.path in preparedMap) {
                        replaced++
                    } else {
                        added++
                    }
                    preparedMap[it.path] = it
                }
                val changedPaths = prepared.map { it.path }.filter { it in beforeKeys }
                VpnLogRepository.log(
                    "NBASSETS prepare overlay mod=${currentImported.fileName} added=$added replaced=$replaced replacedPaths=${changedPaths.take(10)}"
                )
            }
            val prepared = preparedMap.values.sortedBy { it.path }
            _preparation.value = ModPreparationState(
                folderName = folderName,
                isPreparing = true,
                stage = STAGE_SAVING,
                preparedCount = prepared.size,
                totalCount = prepared.size,
                isReady = false,
                error = null
            )
            writePreparedIndex(context, prepared, prepareSignature)
            _preparation.value = ModPreparationState(
                folderName = folderName,
                isPreparing = false,
                preparedCount = prepared.size,
                totalCount = prepared.size,
                isReady = prepared.isNotEmpty(),
                error = null
            )
            VpnLogRepository.log("NBASSETS prepare imported done files=${prepared.size} ms=${elapsedMs(startedAt)}")
            LoginFailedRewritePrewarmer.prewarm(context)
            prepared.isNotEmpty()
        }.getOrElse { error ->
            VpnLogRepository.log(
                "NBASSETS prepare failed error=${error::class.java.simpleName}: ${error.message ?: "unknown"}"
            )
            _preparation.value = ModPreparationState(
                folderName = folderName,
                isPreparing = false,
                preparedCount = 0,
                totalCount = 0,
                isReady = false,
                error = error.message ?: error::class.java.simpleName
            )
            VpnLogRepository.log("NBASSETS prepare imported abort ms=${elapsedMs(startedAt)}")
            false
        }
    }

    private suspend fun preloadOriginalAssetsIntoPreparedMap(
        context: Context,
        folderName: String,
        preparedMap: MutableMap<String, PreparedModFile>,
        startedAt: Long
    ) {
        val sourceStartedAt = System.nanoTime()
        val sourceFiles = OriginalAssetsRepository.listFiles(context)
        VpnLogRepository.log("PREPARE source-folder preload sourceCount=${sourceFiles.size}")
        if (sourceFiles.isEmpty()) return

        _preparation.value = ModPreparationState(
            folderName = folderName,
            isPreparing = true,
            stage = STAGE_ORIGINAL_ASSETS,
            preparedCount = 0,
            totalCount = sourceFiles.size,
            isReady = false,
            error = null
        )

        val completedCount = AtomicInteger(0)
        val preparedFiles = coroutineScope {
            sourceFiles.map { file ->
                async(prepareDispatcher) {
                    val sha = runCatching {
                        context.contentResolver.openInputStream(file.uri)?.use(::sha1Hex)
                    }.getOrNull()
                    val completed = completedCount.incrementAndGet()
                    _preparation.value = ModPreparationState(
                        folderName = folderName,
                        isPreparing = true,
                        stage = STAGE_ORIGINAL_ASSETS,
                        preparedCount = completed,
                        totalCount = sourceFiles.size,
                        isReady = false,
                        error = null
                    )
                    sha?.let {
                        PreparedModFile(
                            path = normalizePath(file.path),
                            sha = it,
                            uri = file.uri.toString(),
                            size = file.size,
                            lastModified = file.lastModified
                        )
                    }
                }
            }.awaitAll()
        }

        val failedIndex = preparedFiles.indexOfFirst { it == null }
        if (failedIndex >= 0) {
            error("source_asset_read_failed:${sourceFiles[failedIndex].path}")
        }
        preparedFiles.filterNotNull().forEach { preparedMap[it.path] = it }
        VpnLogRepository.log(
            "PREPARE source-folder preload done files=${preparedFiles.size} ms=${elapsedMs(sourceStartedAt)} totalMs=${elapsedMs(startedAt)}"
        )
    }

    private suspend fun prepareOriginalAssetsOnly(context: Context): Boolean = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val folderName = OriginalAssetsRepository.state.value.folderName ?: "Папка ассетов"
        _preparation.value = ModPreparationState(
            folderName = folderName,
            isPreparing = true,
            stage = STAGE_ORIGINAL_ASSETS,
            preparedCount = 0,
            totalCount = 0,
            isReady = false,
            error = null
        )
        val sourceFiles = OriginalAssetsRepository.listFiles(context)
        VpnLogRepository.log("PREPARE original-assets-only sourceCount=${sourceFiles.size}")
        if (sourceFiles.isEmpty()) {
            clearPreparedFiles(context)
            _preparation.value = ModPreparationState(
                folderName = folderName,
                isPreparing = false,
                preparedCount = 0,
                totalCount = 0,
                isReady = false,
                error = null
            )
            VpnLogRepository.log("PREPARE original-assets-only empty ms=${elapsedMs(startedAt)}")
            return@withContext false
        }
        val completedCount = AtomicInteger(0)
        val preparedFiles = coroutineScope {
            sourceFiles.map { file ->
                async(prepareDispatcher) {
                    val sha = context.contentResolver.openInputStream(file.uri)?.use(::sha1Hex)
                    val completed = completedCount.incrementAndGet()
                    _preparation.value = ModPreparationState(
                        folderName = folderName,
                        isPreparing = true,
                        stage = STAGE_ORIGINAL_ASSETS,
                        preparedCount = completed,
                        totalCount = sourceFiles.size,
                        isReady = false,
                        error = null
                    )
                    if (sha == null) {
                        null
                    } else {
                        PreparedModFile(
                            path = normalizePath(file.path),
                            sha = sha,
                            uri = file.uri.toString(),
                            size = file.size,
                            lastModified = file.lastModified
                        )
                    }
                }
            }.awaitAll()
        }
        val failedIndex = preparedFiles.indexOfFirst { it == null }
        if (failedIndex >= 0) {
            _preparation.value = ModPreparationState(
                folderName = folderName,
                isPreparing = false,
                preparedCount = failedIndex,
                totalCount = sourceFiles.size,
                isReady = false,
                error = sourceFiles[failedIndex].path
            )
            VpnLogRepository.log("PREPARE original-assets-only failed path=${sourceFiles[failedIndex].path} ms=${elapsedMs(startedAt)}")
            return@withContext false
        }
        writePreparedIndex(context, preparedFiles.filterNotNull(), buildOriginalAssetsPrepareSignature(context))
        _preparation.value = ModPreparationState(
            folderName = folderName,
            isPreparing = false,
            preparedCount = preparedFiles.size,
            totalCount = preparedFiles.size,
            isReady = preparedFiles.isNotEmpty(),
            error = null
        )
        VpnLogRepository.log("PREPARE original-assets-only done files=${preparedFiles.size} ms=${elapsedMs(startedAt)}")
        return@withContext preparedFiles.isNotEmpty()
    }

    private fun writePreparedIndex(context: Context, files: List<PreparedModFile>, prepareSignature: String? = null) {
        val jsonFiles = JSONArray()
        files.forEach { file ->
            jsonFiles.put(
                JSONObject()
                    .put("path", file.path)
                    .put("sha", file.sha)
                    .put("uri", file.uri)
                    .put("size", file.size)
                    .put("lastModified", file.lastModified)
            )
        }
        val preparedRootSha = LatestFingerprintStore.readStoredClientHelloHash(context.filesDir)
        val root = JSONObject().put("files", jsonFiles)
        if (!preparedRootSha.isNullOrBlank()) {
            root.put("preparedRootSha", preparedRootSha)
        }
        if (!prepareSignature.isNullOrBlank()) {
            root.put(PREPARE_SIGNATURE_KEY, prepareSignature)
        }
        File(context.filesDir, PREPARED_INDEX_FILE).writeText(root.toString())
        preparedFilesCache = files
        preparedStateSignature = buildPreparedStateSignature(files)
    }

    private fun readPreparedPrepareSignature(context: Context): String? {
        val indexFile = File(context.filesDir, PREPARED_INDEX_FILE)
        if (!indexFile.isFile) return null
        return runCatching {
            JSONObject(indexFile.readText())
                .optString(PREPARE_SIGNATURE_KEY, "")
                .trim()
                .ifEmpty { null }
        }.getOrNull()
    }

    private fun preparedFilesExist(files: List<PreparedModFile>): Boolean {
        return files.all { file ->
            val uri = Uri.parse(file.uri)
            if (uri.scheme == "file") {
                uri.path?.let { File(it).isFile } == true
            } else {
                true
            }
        }
    }

    private fun buildImportedPrepareSignature(
        context: Context,
        imported: ImportedModState,
        archives: List<File>
    ): String {
        val digest = MessageDigest.getInstance("SHA-1")
        fun add(value: String?) {
            digest.update((value ?: "").encodeToByteArray())
            digest.update(0)
        }

        add("imported")
        add(LatestFingerprintStore.readStoredClientHelloHash(context.filesDir))
        archives.forEach { archive ->
            val mod = imported.mods.firstOrNull { it.id == archive.name }
            add(archive.name)
            add(archive.length().toString())
            add(archive.lastModified().toString())
            add(mod?.isEnabled.toString())
            add(mod?.featureSelection?.enabledFeatureIds.orEmpty().sorted().joinToString(","))
            add(mod?.featureSelection?.disabledGroupIds.orEmpty().sorted().joinToString(","))
        }
        OriginalAssetsRepository.listFiles(context)
            .sortedBy { it.path }
            .forEach { file ->
                add(file.path)
                add(file.uri.toString())
                add(file.size.toString())
                add(file.lastModified.toString())
            }
        return digest.digest().joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun buildOriginalAssetsPrepareSignature(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-1")
        fun add(value: String?) {
            digest.update((value ?: "").encodeToByteArray())
            digest.update(0)
        }

        add("original-assets")
        add(LatestFingerprintStore.readStoredClientHelloHash(context.filesDir))
        OriginalAssetsRepository.listFiles(context)
            .sortedBy { it.path }
            .forEach { file ->
                add(file.path)
                add(file.uri.toString())
                add(file.size.toString())
                add(file.lastModified.toString())
            }
        return digest.digest().joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun sha1Hex(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-1")
        BufferedInputStream(input, COPY_BUFFER_SIZE).use { bufferedInput ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = bufferedInput.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val digestBytes = digest.digest()
        return digestBytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun normalizePath(path: String): String {
        return path.replace('\\', '/')
            .trimStart('/')
            .replace(" ", "")
    }

    private fun buildPreparedStateSignature(files: List<PreparedModFile>): String {
        if (files.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-1")
        files.sortedBy { it.path }.forEach { file ->
            digest.update(file.path.encodeToByteArray())
            digest.update(0)
            digest.update(file.sha.encodeToByteArray())
            digest.update(0)
            digest.update(file.uri.encodeToByteArray())
            digest.update(0)
            digest.update(file.size.toString().encodeToByteArray())
            digest.update(0)
            digest.update(file.lastModified.toString().encodeToByteArray())
            digest.update(0)
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }
}

private const val COPY_BUFFER_SIZE = 256 * 1024
private const val PREPARE_FILE_PARALLELISM = 4

private fun elapsedMs(startedAtNanos: Long): String {
    return String.format(java.util.Locale.US, "%.3f", (System.nanoTime() - startedAtNanos) / 1_000_000.0)
}
