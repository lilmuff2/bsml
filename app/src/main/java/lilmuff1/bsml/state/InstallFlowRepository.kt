package lilmuff1.bsml.state

import lilmuff1.bsml.config.PATCH_NAMESPACE
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

object InstallFlowRepository {
    private val patchedModAssetServed = AtomicBoolean(false)
    private val contentHashRewriteEnabled = AtomicBoolean(true)
    private val rootShaRewriteApplied = AtomicBoolean(false)
    private val patchedClientHelloSeen = AtomicBoolean(false)
    private val originalRootAfterPatchSeen = AtomicBoolean(false)
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
        installResultNotified.set(false)
        currentClientHelloHash = null
        originalRootSha = null
        servedPatchedPaths.clear()
    }

    fun onPatchedAssetServed(path: String) {
        val normalized = path.trim().removePrefix("/")
        if (normalized.isEmpty() || normalized == PATCH_NAMESPACE) return
        patchedModAssetServed.set(true)
        servedPatchedPaths += normalized
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

    fun setOriginalRootSha(rootSha: String?) {
        originalRootSha = rootSha?.trim()?.ifEmpty { null }
    }

    fun getOriginalRootSha(): String? = originalRootSha

    fun tryMarkInstallResultNotified(): Boolean {
        return installResultNotified.compareAndSet(false, true)
    }

    fun servedPatchedCount(): Int = servedPatchedPaths.size
}
