package lilmuff1.bsml.service

data class GeneratedTriggerAsset(
    val sha: String
)

data class LoginFailedRewriteResult(
    val body: ByteArray,
    val hashes: FingerprintHashes,
    val patchStats: FingerprintPatchStats,
    val assetUrlRewritten: Boolean = false,
    val rootShaRewriteApplied: Boolean = false,
    val assetOrigins: List<String> = emptyList(),
    val originalRootSha: String? = null
)

data class FingerprintRewriteResult(
    val json: String,
    val rootSha: String?,
    val patchStats: FingerprintPatchStats
)

data class CompressedFingerprintRewriteResult(
    val bytes: ByteArray?,
    val rootSha: String?,
    val patchStats: FingerprintPatchStats,
    val json: String? = null
)

data class FingerprintPatchStats(
    val fileShaPatched: Int = 0,
    val rootShaOld: String? = null,
    val rootShaNew: String? = null,
    val mode: String? = null
) {
    operator fun plus(other: FingerprintPatchStats): FingerprintPatchStats {
        return FingerprintPatchStats(
            fileShaPatched = fileShaPatched + other.fileShaPatched,
            rootShaOld = rootShaOld ?: other.rootShaOld,
            rootShaNew = rootShaNew ?: other.rootShaNew,
            mode = mode ?: other.mode
        )
    }
}

data class FingerprintHashes(
    val rootSha: String?,
    val fileSha: String?
)
