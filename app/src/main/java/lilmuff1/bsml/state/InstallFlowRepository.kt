package lilmuff1.bsml.state

import android.content.Context
import lilmuff1.bsml.config.PATCH_NAMESPACE
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

object InstallFlowRepository {
    private const val SERVED_PATCHED_PATHS_FILE = "install_served_patched_paths.txt"
    private const val SERVED_PATCHED_PREFS = "install_served_patched_paths"
    private const val KEY_SERVED_PATCHED_PATHS = "paths"
    private val patchedModAssetServed = AtomicBoolean(false)
    private val contentHashRewriteEnabled = AtomicBoolean(true)
    private val rootShaRewriteApplied = AtomicBoolean(false)
    private val patchedClientHelloSeen = AtomicBoolean(false)
    private val originalRootAfterPatchSeen = AtomicBoolean(false)
    private val finalClientHelloSeen = AtomicBoolean(false)
    private val installResultNotified = AtomicBoolean(false)
    @Volatile
    private var currentClientHelloHash: String? = null
    @Volatile
    private var originalRootSha: String? = null
    private val servedPatchedPaths = ConcurrentHashMap.newKeySet<String>()

    fun reset() {
        patchedModAssetServed.set(false)
        contentHashRewriteEnabled.set(true)
        rootShaRewriteApplied.set(false)
        patchedClientHelloSeen.set(false)
        originalRootAfterPatchSeen.set(false)
        finalClientHelloSeen.set(false)
        installResultNotified.set(false)
        currentClientHelloHash = null
        originalRootSha = null
        servedPatchedPaths.clear()
    }

    fun reset(context: Context) {
        reset()
        servedPathsFile(context).delete()
        prefs(context).edit().clear().commit()
    }

    fun onPatchedAssetServed(path: String) {
        val normalized = path.trim().removePrefix("/")
        if (normalized.isEmpty() || normalized == PATCH_NAMESPACE) return
        patchedModAssetServed.set(true)
        servedPatchedPaths += normalized
    }

    fun onPatchedAssetServed(context: Context, path: String) {
        onPatchedAssetServed(path)
        val normalized = path.trim().removePrefix("/")
        if (normalized.isEmpty() || normalized == PATCH_NAMESPACE) return
        runCatching {
            val file = servedPathsFile(context)
            file.parentFile?.mkdirs()
            val existing = if (file.isFile) {
                file.readLines().map(String::trim).filter(String::isNotEmpty).toSet()
            } else {
                emptySet()
            }
            if (normalized !in existing) {
                file.appendText("$normalized\n")
            }
        }
        runCatching {
            val preferences = prefs(context)
            val updated = preferences
                .getStringSet(KEY_SERVED_PATCHED_PATHS, emptySet())
                .orEmpty()
                .toMutableSet()
                .apply { add(normalized) }
            preferences.edit()
                .putStringSet(KEY_SERVED_PATCHED_PATHS, updated)
                .commit()
        }
    }

    fun wasPatchedModAssetServed(): Boolean = patchedModAssetServed.get()

    fun isContentHashRewriteEnabled(): Boolean = contentHashRewriteEnabled.get()

    fun disableContentHashRewrite() {
        contentHashRewriteEnabled.set(false)
    }

    fun markRootShaRewriteApplied() {
        rootShaRewriteApplied.set(true)
    }

    fun wasRootShaRewriteApplied(): Boolean = rootShaRewriteApplied.get()

    fun markPatchedClientHelloSeen() {
        patchedClientHelloSeen.set(true)
    }

    fun wasPatchedClientHelloSeen(): Boolean = patchedClientHelloSeen.get()

    fun setCurrentClientHelloHash(contentHash: String?) {
        currentClientHelloHash = contentHash?.trim()?.ifEmpty { null }
    }

    fun getCurrentClientHelloHash(): String? = currentClientHelloHash

    fun markOriginalRootAfterPatchSeen() {
        originalRootAfterPatchSeen.set(true)
    }

    fun wasOriginalRootAfterPatchSeen(): Boolean = originalRootAfterPatchSeen.get()

    fun markFinalClientHelloSeen() {
        finalClientHelloSeen.set(true)
        contentHashRewriteEnabled.set(false)
    }

    fun wasFinalClientHelloSeen(): Boolean = finalClientHelloSeen.get()

    fun setOriginalRootSha(rootSha: String?) {
        originalRootSha = rootSha?.trim()?.ifEmpty { null }
    }

    fun getOriginalRootSha(): String? = originalRootSha

    fun tryMarkInstallResultNotified(): Boolean {
        return installResultNotified.compareAndSet(false, true)
    }

    fun servedPatchedCount(): Int = servedPatchedPaths.size

    fun servedPatchedCount(context: Context): Int {
        val filePaths = runCatching {
            val file = servedPathsFile(context)
            if (!file.isFile) {
                emptySet()
            } else {
                file.readLines()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && it != PATCH_NAMESPACE }
                    .toSet()
            }
        }.getOrDefault(emptySet())
        val prefsPaths = prefs(context)
            .getStringSet(KEY_SERVED_PATCHED_PATHS, emptySet())
            .orEmpty()
            .map(String::trim)
            .filter { it.isNotEmpty() && it != PATCH_NAMESPACE }
            .toSet()
        return (servedPatchedPaths + filePaths + prefsPaths).size
    }

    private fun servedPathsFile(context: Context): File {
        return File(context.filesDir, SERVED_PATCHED_PATHS_FILE)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(SERVED_PATCHED_PREFS, Context.MODE_PRIVATE)
}
