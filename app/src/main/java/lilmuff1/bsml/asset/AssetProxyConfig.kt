package lilmuff1.bsml.asset

object AssetProxyConfig {
    const val LOCAL_BASE_URL = "http://127.0.0.1:8787"
    const val PRIMARY_ASSET_BASE_ORIGIN = "https://game-assets.brawlstarsgame.com"
    const val SECONDARY_ASSET_BASE_ORIGIN = "https://game-assets-2.brawlstars.com"
    const val PORT = 8787
}

fun rewriteAssetUrl(url: String): String {
    return when {
        url.startsWith(AssetProxyConfig.PRIMARY_ASSET_BASE_ORIGIN) ->
            url.replace(AssetProxyConfig.PRIMARY_ASSET_BASE_ORIGIN, AssetProxyConfig.LOCAL_BASE_URL)
        url.startsWith(AssetProxyConfig.SECONDARY_ASSET_BASE_ORIGIN) ->
            url.replace(AssetProxyConfig.SECONDARY_ASSET_BASE_ORIGIN, AssetProxyConfig.LOCAL_BASE_URL)
        else -> url
    }
}
