package lilmuff1.bsml.state

import lilmuff1.bsml.config.PATCH_NAMESPACE
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

object InstallFlowRepository {
    private val patchedModAssetServed = AtomicBoolean(false)
    private val servedPatchedPaths = ConcurrentHashMap.newKeySet<String>()

    fun reset() {
        patchedModAssetServed.set(false)
        servedPatchedPaths.clear()
    }

    fun onPatchedAssetServed(path: String) {
        val normalized = path.trim().removePrefix("/")
        if (normalized.isEmpty() || normalized == PATCH_NAMESPACE) return
        patchedModAssetServed.set(true)
        servedPatchedPaths += normalized
    }

    fun wasPatchedModAssetServed(): Boolean = patchedModAssetServed.get()

    fun servedPatchedCount(): Int = servedPatchedPaths.size
}
