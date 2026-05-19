package lilmuff1.bsml.state

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
    private const val PREPARED_FILES_DIR = "prepared_mod_files"
    private val prepareDispatcher = Dispatchers.IO.limitedParallelism(PREPARE_FILE_PARALLELISM)
    private val prepareMutex = Mutex()

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
        ImportedModRepository.refreshState(context)
        refreshPreparedStateFromImportedMod(context)
    }

    fun refreshPreparedStateFromImportedMod(context: Context) {
        val imported = ImportedModRepository.state.value
        val folderName = imported.metadata?.title ?: imported.fileName ?: getDisplayName(context)
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

    suspend fun prepareFiles(context: Context): Boolean = prepareMutex.withLock {
        withContext(Dispatchers.IO) {
            if (LatestFingerprintStore.readStoredClientHelloHash(context.filesDir).isNullOrBlank()) {
                _preparation.value = _preparation.value.copy(
                    isPreparing = false,
                    isReady = false,
                    error = "fingerprint_not_loaded"
                )
                return@withContext false
            }
            val activeArchive = ImportedModRepository.activeArchiveFile(context)
            if (activeArchive.isFile) {
                return@withContext prepareImportedArchive(context, activeArchive)
            }

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

            val sourceFiles = listFilesParallel(context)
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

            val completedCount = AtomicInteger(0)
            val preparedFiles = coroutineScope {
                sourceFiles.map { file ->
                    async(prepareDispatcher) {
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
                        val completed = completedCount.incrementAndGet()
                        _preparation.value = ModPreparationState(
                            folderName = folderName,
                            isPreparing = true,
                            preparedCount = completed,
                            totalCount = sourceFiles.size,
                            isReady = false,
                            error = null
                        )
                        if (sha == null) {
                            null
                        } else {
                            PreparedModFile(
                                path = file.path,
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
                return@withContext false
            }

            writePreparedIndex(context, preparedFiles.filterNotNull())
            _preparation.value = ModPreparationState(
                folderName = folderName,
                isPreparing = false,
                preparedCount = preparedFiles.size,
                totalCount = preparedFiles.size,
                isReady = true,
                error = null
            )
            LoginFailedRewritePrewarmer.prewarm(context)
            true
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

    fun clearPreparedFiles(context: Context) {
        preparedFilesCache = emptyList()
        preparedStateSignature = ""
        File(context.filesDir, PREPARED_INDEX_FILE).delete()
        File(context.filesDir, PREPARED_FILES_DIR).deleteRecursively()
    }

    private suspend fun prepareImportedArchive(context: Context, archive: File): Boolean = withContext(Dispatchers.IO) {
        ImportedModRepository.refreshState(context)
        val imported = ImportedModRepository.state.value
        val folderName = imported.metadata?.title ?: imported.fileName ?: archive.name
        _preparation.value = ModPreparationState(
            folderName = folderName,
            isPreparing = true,
            preparedCount = 0,
            totalCount = 0,
            isReady = false,
            error = null
        )
        runCatching {
            VpnLogRepository.log(
                "NBASSETS prepare imported enabled=${imported.isEnabled} selected=${imported.featureSelection.enabledFeatureIds.sorted()}"
            )
            val prepared = if (imported.isEnabled) {
                NbAssetsCompiler(
                    context = context,
                    enabledFeatureIds = imported.featureSelection.enabledFeatureIds
                ).compile(
                    archive = archive,
                    outputDir = File(context.filesDir, PREPARED_FILES_DIR)
                )
            } else {
                File(context.filesDir, PREPARED_FILES_DIR).deleteRecursively()
                emptyList()
            }
            writePreparedIndex(context, prepared)
            _preparation.value = ModPreparationState(
                folderName = folderName,
                isPreparing = false,
                preparedCount = prepared.size,
                totalCount = prepared.size,
                isReady = prepared.isNotEmpty(),
                error = null
            )
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
            false
        }
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

    private suspend fun listFilesParallel(context: Context): List<ModFileEntry> {
        val root = getRoot(context) ?: return emptyList()
        return collectFilesParallel(root, "").sortedBy { it.path }
    }

    private suspend fun collectFilesParallel(
        directory: DocumentFile,
        prefix: String
    ): List<ModFileEntry> = coroutineScope {
        val files = withContext(prepareDispatcher) { directory.listFiles().toList() }
        val directFiles = ArrayList<ModFileEntry>()
        val childJobs = files.mapNotNull { file ->
            val name = file.name ?: return@mapNotNull null
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            when {
                file.isDirectory -> async(prepareDispatcher) {
                    collectFilesParallel(file, path)
                }
                file.isFile -> {
                    directFiles += ModFileEntry(
                        path = normalizePath(path),
                        uri = file.uri,
                        size = file.length(),
                        lastModified = file.lastModified()
                    )
                    null
                }
                else -> null
            }
        }
        directFiles + childJobs.awaitAll().flatten()
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
        return path.replace('\\', '/')
            .trimStart('/')
            .replace(" ", "")
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
private const val PREPARE_FILE_PARALLELISM = 4
