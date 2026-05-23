package lilmuff1.bsml.config

const val GAME_HOST = "game.brawlstarsgame.com"
const val GAME_PORT = 9339
const val DEFAULT_CAPTURE_PACKAGES = "com.supercell.brawlstars, bsd.suitcase.release, com.tencent.tmgp.supercell.brawlstars"
const val DEFAULT_CAPTURE_TARGETS = "game.brawlstarsgame.com, 62.233.36.84,62.233.36.83, game.brawlstars.cn"

const val LOCAL_ASSET_HOST = "127.0.0.1"
const val PUBLIC_ASSET_HOST = "thirdqq.qlogo.cn"
const val LOCAL_ASSET_PORT = 4267

fun localAssetBaseUrl(): String {
    return "http://$LOCAL_ASSET_HOST:$LOCAL_ASSET_PORT"
}

fun externalAssetBaseUrl(): String {
    val host = if (AssetRoutingRepository.usePublicAssetHostNow()) PUBLIC_ASSET_HOST else LOCAL_ASSET_HOST
    return "http://$host:$LOCAL_ASSET_PORT"
}

const val PATCH_NAMESPACE = "lilmuff1"

const val GENERATED_ASSET_DIR = "generated_assets"
const val GENERATED_TRIGGER_BYTES = 64
