package lilmuff1.bsml.state

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ModFileEntry(
    val path: String,
    val uri: Uri,
    val size: Long,
    val lastModified: Long
)

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
    val preparedCount: Int = 0,
    val totalCount: Int = 0,
    val isReady: Boolean = false,
    val error: String? = null
) {
    val progress: Float
        get() = if (totalCount <= 0) 0f else preparedCount.toFloat() / totalCount.toFloat()
}

object ModFilesRepository {
    private const val PREFS_NAME = "mod_files_repository"
    private const val KEY_TREE_URI = "tree_uri"
    private const val PREPARED_INDEX_FILE = "prepared_mod_index.json"

    private val _preparation = MutableStateFlow(ModPreparationState())
    val preparation = _preparation.asStateFlow()
    @Volatile
    private var preparedFilesCache: List<PreparedModFile> = emptyList()
    @Volatile
    private var preparedStateSignature: String = ""

    fun getTreeUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(raw) }.getOrNull()
    }

    fun setTreeUri(context: Context, uri: Uri?) {
        if (uri == null) {
            getTreeUri(context)?.let { previousUri ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        previousUri,
                        IntentFlags.readWritePersistableFlags
                    )
                }
            }
        }
        val editor = prefs(context).edit()
        if (uri == null) {
            editor.remove(KEY_TREE_URI)
        } else {
            editor.putString(KEY_TREE_URI, uri.toString())
        }
        editor.commit()
        clearPreparedFiles(context)
        refreshState(context)
    }

    fun refreshState(context: Context) {
        val folderName = getDisplayName(context)
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

    fun getDisplayName(context: Context): String? {
        val root = getRoot(context) ?: return null
        return root.name ?: getTreeUri(context)?.lastPathSegment
    }

    fun listFiles(context: Context): List<ModFileEntry> {
        val root = getRoot(context) ?: return emptyList()
        val result = ArrayList<ModFileEntry>()
        collectFiles(root, "", result)
        return result.sortedBy { it.path }
    }

    suspend fun prepareFiles(context: Context): Boolean = withContext(Dispatchers.IO) {
        val folderName = getDisplayName(context)
        if (folderName == null) {
            _preparation.value = ModPreparationState(error = "folder_not_selected")
            return@withContext false
        }

        _preparation.value = ModPreparationState(
            folderName = folderName,
            isPreparing = true,
            preparedCount = 0,
            totalCount = 0,
            isReady = false,
            error = null
        )

        val sourceFiles = listFiles(context)
        val existingPreparedList = listPreparedFiles(context)
        if (matchesSnapshot(existingPreparedList, sourceFiles)) {
            _preparation.value = ModPreparationState(
                folderName = folderName,
                isPreparing = false,
                preparedCount = existingPreparedList.size,
                totalCount = existingPreparedList.size,
                isReady = existingPreparedList.isNotEmpty(),
                error = null
            )
            return@withContext existingPreparedList.isNotEmpty()
        }

        val existingPrepared = existingPreparedList.associateBy { it.path }
        _preparation.value = ModPreparationState(
            folderName = folderName,
            isPreparing = true,
            preparedCount = 0,
            totalCount = sourceFiles.size,
            isReady = false,
            error = null
        )

        val preparedFiles = ArrayList<PreparedModFile>(sourceFiles.size)
        sourceFiles.forEachIndexed { index, file ->
            val cached = existingPrepared[file.path]
            val sha = if (cached != null && cached.matches(file)) {
                cached.sha
            } else {
                runCatching {
                    context.contentResolver.openInputStream(file.uri)?.use { input ->
                        sha1Hex(input)
                    }
                }.getOrNull()
            }
            if (sha == null) {
                _preparation.value = ModPreparationState(
                    folderName = folderName,
                    isPreparing = false,
                    preparedCount = index,
                    totalCount = sourceFiles.size,
                    isReady = false,
                    error = file.path
                )
                return@withContext false
            }

            preparedFiles += PreparedModFile(
                path = file.path,
                sha = sha,
                uri = file.uri.toString(),
                size = file.size,
                lastModified = file.lastModified
            )
            _preparation.value = ModPreparationState(
                folderName = folderName,
                isPreparing = true,
                preparedCount = index + 1,
                totalCount = sourceFiles.size,
                isReady = false,
                error = null
            )
        }

        writePreparedIndex(context, preparedFiles)
        _preparation.value = ModPreparationState(
            folderName = folderName,
            isPreparing = false,
            preparedCount = preparedFiles.size,
            totalCount = preparedFiles.size,
            isReady = true,
            error = null
        )
        true
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

    fun listPreparedPaths(context: Context): List<String> {
        return listPreparedFiles(context).map { it.path }
    }

    fun getPreparedSha(context: Context, path: String): String? {
        return listPreparedFiles(context).firstOrNull { it.path == normalizePath(path) }?.sha
    }

    fun openPreparedFile(context: Context, relativePath: String): ByteArray? {
        val entry = listPreparedFiles(context).firstOrNull { it.path == normalizePath(relativePath) } ?: return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(entry.uri))?.use { it.readBytes() }
        }.getOrNull()
    }

    fun takePersistablePermission(context: Context, uri: Uri) {
        val flags = IntentFlags.readWritePersistableFlags
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }

    fun getPreparedStateSignature(context: Context): String {
        val cached = preparedStateSignature
        if (cached.isNotEmpty()) return cached
        val signature = buildPreparedStateSignature(listPreparedFiles(context))
        preparedStateSignature = signature
        return signature
    }

    private fun clearPreparedFiles(context: Context) {
        preparedFilesCache = emptyList()
        preparedStateSignature = ""
        File(context.filesDir, PREPARED_INDEX_FILE).delete()
    }

    private fun writePreparedIndex(context: Context, files: List<PreparedModFile>) {
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
        File(context.filesDir, PREPARED_INDEX_FILE).writeText(
            JSONObject().put("files", jsonFiles).toString()
        )
        preparedFilesCache = files
        preparedStateSignature = buildPreparedStateSignature(files)
    }

    private fun getRoot(context: Context): DocumentFile? {
        val uri = getTreeUri(context) ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.exists() && it.isDirectory }
    }

    private fun collectFiles(directory: DocumentFile, prefix: String, result: MutableList<ModFileEntry>) {
        directory.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            when {
                file.isDirectory -> collectFiles(file, path, result)
                file.isFile -> result += ModFileEntry(
                    path = normalizePath(path),
                    uri = file.uri,
                    size = file.length(),
                    lastModified = file.lastModified()
                )
            }
        }
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
        return path.replace('\\', '/').trimStart('/')
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

private fun PreparedModFile.matches(file: ModFileEntry): Boolean {
    return path == file.path &&
        uri == file.uri.toString() &&
        size == file.size &&
        lastModified == file.lastModified
}

private fun matchesSnapshot(
    prepared: List<PreparedModFile>,
    files: List<ModFileEntry>
): Boolean {
    if (prepared.size != files.size) return false
    return prepared.zip(files).all { (preparedFile, file) ->
        preparedFile.matches(file)
    }
}

private object IntentFlags {
    val readWritePersistableFlags: Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
}

private const val COPY_BUFFER_SIZE = 256 * 1024
