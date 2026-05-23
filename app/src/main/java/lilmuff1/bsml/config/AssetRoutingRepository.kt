package lilmuff1.bsml.config

import java.util.concurrent.atomic.AtomicBoolean

object AssetRoutingRepository {
    private val usePublicAssetHost = AtomicBoolean(false)

    fun setUsePublicAssetHost(enabled: Boolean) {
        usePublicAssetHost.set(enabled)
    }

    fun usePublicAssetHostNow(): Boolean = usePublicAssetHost.get()

    fun reset() {
        usePublicAssetHost.set(false)
    }
}
