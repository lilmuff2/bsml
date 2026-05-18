package lilmuff1.bsml.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.math.min
import kotlin.random.Random
import java.util.concurrent.ConcurrentHashMap
import lilmuff1.bsml.config.*
import lilmuff1.bsml.protocol.LoginFailedPrefix
import lilmuff1.bsml.protocol.LoginFailedTail
import lilmuff1.bsml.protocol.debugLog
import lilmuff1.bsml.state.LatestFingerprintStore
import lilmuff1.bsml.state.VpnLogRepository
import org.json.JSONArray
import org.json.JSONObject

private const val RANDOM_SHA_SUFFIX_LENGTH = 8
private const val FINGERPRINT_COMPRESSION_LEVEL = Deflater.BEST_SPEED
private const val FINGERPRINT_COMPRESSION_BUFFER_SIZE = 256 * 1024

class LoginFailedRewriter(
    private val filesDir: File,
    private val listPatchedAssetPaths: () -> List<String>,
    private val getPatchedAssetSha: (String) -> String?,
    private val openPatchedAsset: (String) -> ByteArray?,
    private val getCurrentModStateKey: () -> String
) {
    companion object {
        private val prewarmedFingerprintBaseCache = ConcurrentHashMap<String, FingerprintRewriteResult>()

        private fun prewarmedBaseKey(clientHelloHash: String, modStateKey: String): String {
            return "${clientHelloHash.trim()}|$modStateKey"
        }
    }

    @Volatile
    private var lastStoredServerClientHelloHash: String? = null
    private val decompressedFingerprintCache = ConcurrentHashMap<String, FingerprintRewriteResult>()
    private val inflatedFingerprintCache = ConcurrentHashMap<String, InflatedFingerprintCacheEntry>()

    fun prewarmFingerprintJson(fingerprintJson: String, clientHelloHash: String?) {
        val normalizedHash = clientHelloHash?.trim()?.ifEmpty { null } ?: return
        val modStateKey = getCurrentModStateKey()
        runCatching {
            val base = rewriteFingerprintJsonStructured(
                fingerprintJson = fingerprintJson,
                shouldPatchRootFingerprintSha = false,
                includeTriggerAsset = false
            )
            prewarmedFingerprintBaseCache[prewarmedBaseKey(normalizedHash, modStateKey)] = base
        }
    }

    fun rewriteCleanup(
        body: ByteArray,
        reasonCode: Int,
        reasonName: String,
        reasonText: String
    ): LoginFailedRewriteResult {
        val totalStartedAt = System.nanoTime()
        val prefixStartedAt = System.nanoTime()
        val parsed = LoginFailedPrefix.parse(body)
            ?: return LoginFailedRewriteResult(body, FingerprintHashes(null, null), FingerprintPatchStats(mode = "delete"))
        val prefixMs = elapsedMsLong(prefixStartedAt)
        val prefixRoundTripStartedAt = System.nanoTime()
        val prefixRoundTrip = parsed.encode()
        val prefixRoundTripMs = elapsedMsLong(prefixRoundTripStartedAt)
        if (!prefixRoundTrip.contentEquals(body)) {
            VpnLogRepository.log(
                "SC LOGIN_FAILED structure mismatch stage=prefix original=${body.size} encoded=${prefixRoundTrip.size} " +
                    "diff=${firstDiffIndex(body, prefixRoundTrip)}"
            )
            return LoginFailedRewriteResult(body, FingerprintHashes(null, null), FingerprintPatchStats(mode = "delete"))
        }

        val localAssetUrl = localAssetBaseUrl()
        val assetOrigins = linkedSetOf<String>()
        var assetUrlRewritten = false
        val shouldRewriteFingerprint = reasonCode != 1
        val tailParseStartedAt = System.nanoTime()
        val parsedTail = LoginFailedTail.parse(parsed.suffix)
        val tailParseMs = elapsedMsLong(tailParseStartedAt)
        val originalHashes = FingerprintHashes(extractPlainFingerprintRootSha(parsed.fingerprint), null)
        val fingerprintStartedAt = System.nanoTime()
        val cleanupFingerprintJson = if (shouldRewriteFingerprint) createCleanupFingerprintJson() else null
        var tailRoundTripMs = 0L
        var cleanupDeflateMs = 0L
        var tailEncodeMs = 0L

        val rewrittenTail = parsedTail?.let { tail ->
            val tailRoundTripStartedAt = System.nanoTime()
            val tailRoundTrip = tail.encode()
            tailRoundTripMs = elapsedMsLong(tailRoundTripStartedAt)
            if (!tailRoundTrip.contentEquals(parsed.suffix)) {
                VpnLogRepository.log(
                    "SC LOGIN_FAILED structure mismatch stage=tail suffix=${parsed.suffix.size} encoded=${tailRoundTrip.size} " +
                        "diff=${firstDiffIndex(parsed.suffix, tailRoundTrip)}"
                )
                return LoginFailedRewriteResult(body, originalHashes, FingerprintPatchStats(mode = "delete"))
            }

            tail.contentDownloadUrls.forEach { url ->
                if (url.isNotBlank()) assetOrigins += url.trimEnd('/')
                if (url != localAssetUrl) assetUrlRewritten = true
            }
            val rewrittenCompressedFingerprint = if (shouldRewriteFingerprint) {
                tail.compressedFingerprint?.let { bytes ->
                    val cleanupDeflateStartedAt = System.nanoTime()
                    deflateFingerprint(
                        cleanupFingerprintJson ?: return@let bytes,
                        guessFingerprintFormat(bytes)
                    ).also {
                        cleanupDeflateMs = elapsedMsLong(cleanupDeflateStartedAt)
                    }
                }
            } else {
                tail.compressedFingerprint
            }
            val tailEncodeStartedAt = System.nanoTime()
            tail.copy(
                compressedFingerprint = rewrittenCompressedFingerprint,
                contentDownloadUrls = listOf(localAssetUrl),
                preserveRawPrefixWhenCompressedNull = !shouldRewriteFingerprint
            ).encode().also {
                tailEncodeMs = elapsedMsLong(tailEncodeStartedAt)
            }
        } ?: parsed.suffix
        val fingerprintMs = elapsedMsLong(fingerprintStartedAt)

        val rewrittenContentDownloadUrl = parsed.contentDownloadUrl?.let { url ->
            if (url.isNotBlank()) assetOrigins += url.trimEnd('/')
            if (url != localAssetUrl) assetUrlRewritten = true
            localAssetUrl
        }
        val encodeStartedAt = System.nanoTime()
        val rewritten = parsed.copy(
            reason = reasonCode,
            fingerprint = if (shouldRewriteFingerprint && parsed.fingerprint != null) cleanupFingerprintJson else parsed.fingerprint,
            contentDownloadUrl = rewrittenContentDownloadUrl,
            reasonText = reasonText,
            suffix = rewrittenTail
        )
        val rewrittenBody = rewritten.encode()
        val encodeMs = elapsedMsLong(encodeStartedAt)
        val validateStartedAt = System.nanoTime()
        val cleanupReparseStartedAt = System.nanoTime()
        val reparsed = LoginFailedPrefix.parse(rewrittenBody)
        val cleanupReparseMs = elapsedMsLong(cleanupReparseStartedAt)
        val cleanupReparseTailStartedAt = System.nanoTime()
        val reparsedTail = reparsed?.suffix?.let(LoginFailedTail::parse)
        val cleanupReparseTailMs = elapsedMsLong(cleanupReparseTailStartedAt)
        if (
            reparsed == null ||
            reparsedTail == null ||
            reparsed.reason != reasonCode ||
            reparsed.reasonText != reasonText
        ) {
            VpnLogRepository.log("SC LOGIN_FAILED structure mismatch stage=cleanup-parse encoded=${rewrittenBody.size}")
            return LoginFailedRewriteResult(body, originalHashes, FingerprintPatchStats(mode = "delete"))
        }
        val validateMs = elapsedMsLong(validateStartedAt)
        val hashes = if (shouldRewriteFingerprint) {
            FingerprintHashes(extractPlainFingerprintRootSha(cleanupFingerprintJson), null)
        } else {
            originalHashes
        }
        return LoginFailedRewriteResult(
            body = rewrittenBody,
            hashes = hashes,
            patchStats = FingerprintPatchStats(
                fileShaPatched = if (shouldRewriteFingerprint) 1 else 0,
                rootShaOld = originalHashes.rootSha,
                rootShaNew = if (shouldRewriteFingerprint) hashes.rootSha else originalHashes.rootSha,
                mode = "delete"
            ),
            assetUrlRewritten = assetUrlRewritten,
            rootShaRewriteApplied = false,
            assetOrigins = assetOrigins.toList(),
            originalRootSha = originalHashes.rootSha
        )
    }

    fun rewrite(
        body: ByteArray,
        shouldPatchRootFingerprintSha: Boolean,
        includeTriggerAsset: Boolean,
        forcedReasonCode: Int? = null,
        currentClientHelloHash: String? = null
    ): LoginFailedRewriteResult {
        val totalStartedAt = System.nanoTime()
        val prefixStartedAt = System.nanoTime()
        val parsed = LoginFailedPrefix.parse(body) ?: return LoginFailedRewriteResult(body, FingerprintHashes(null, null), FingerprintPatchStats())
        val prefixMs = elapsedMsLong(prefixStartedAt)
        val prefixRoundTripStartedAt = System.nanoTime()
        val prefixRoundTrip = parsed.encode()
        val prefixRoundTripMs = elapsedMsLong(prefixRoundTripStartedAt)
        if (!prefixRoundTrip.contentEquals(body)) {
            VpnLogRepository.log(
                "SC LOGIN_FAILED structure mismatch stage=prefix original=${body.size} encoded=${prefixRoundTrip.size} " +
                    "diff=${firstDiffIndex(body, prefixRoundTrip)}"
            )
            return LoginFailedRewriteResult(body, FingerprintHashes(null, null), FingerprintPatchStats())
        }

        var assetUrlRewritten = false
        var finalRootSha: String? = null
        var patchStats = FingerprintPatchStats()
        var originalRootSha: String? = null
        val shouldPatchFingerprint = true
        val localAssetUrl = localAssetBaseUrl()
        val assetOrigins = linkedSetOf<String>()
        val plainFingerprintStartedAt = System.nanoTime()
        val rewrittenFingerprint = if (shouldPatchFingerprint) parsed.fingerprint?.takeIf { it.trimStart().startsWith("{") }?.let { fingerprintJson ->
            rewriteFingerprintJsonStructured(
                fingerprintJson = fingerprintJson,
                shouldPatchRootFingerprintSha = shouldPatchRootFingerprintSha,
                includeTriggerAsset = includeTriggerAsset
            ).also { result ->
                originalRootSha = result.patchStats.rootShaOld ?: result.rootSha ?: originalRootSha
                finalRootSha = result.rootSha
                patchStats += result.patchStats
            }.json
        } else parsed.fingerprint
        val plainFingerprintMs = elapsedMsLong(plainFingerprintStartedAt)
        val tailStartedAt = System.nanoTime()
        var compressedMs = 0L
        var tailRoundTripMs = 0L
        var tailEncodeMs = 0L
        val rewrittenTail = LoginFailedTail.parse(parsed.suffix)?.let { tail ->
            val tailRoundTripStartedAt = System.nanoTime()
            val tailRoundTrip = tail.encode()
            tailRoundTripMs = elapsedMsLong(tailRoundTripStartedAt)
            if (!tailRoundTrip.contentEquals(parsed.suffix)) {
                VpnLogRepository.log(
                    "SC LOGIN_FAILED structure mismatch stage=tail suffix=${parsed.suffix.size} encoded=${tailRoundTrip.size} " +
                        "diff=${firstDiffIndex(parsed.suffix, tailRoundTrip)}"
                )
                return LoginFailedRewriteResult(body, FingerprintHashes(finalRootSha, null), patchStats)
            }

            val compressedStartedAt = System.nanoTime()
            val rewrittenCompressedFingerprint = if (shouldPatchFingerprint) tail.compressedFingerprint?.let { bytes ->
                rewriteCompressedFingerprintJson(
                    compressedFingerprint = bytes,
                    shouldPatchRootFingerprintSha = shouldPatchRootFingerprintSha,
                    includeTriggerAsset = includeTriggerAsset,
                    currentClientHelloHash = currentClientHelloHash
                ).also { result ->
                    originalRootSha = result.patchStats.rootShaOld ?: result.rootSha ?: originalRootSha
                    finalRootSha = result.rootSha ?: finalRootSha
                    patchStats += result.patchStats
                }.bytes
            } else tail.compressedFingerprint
            compressedMs = elapsedMsLong(compressedStartedAt)
            tail.contentDownloadUrls.forEach { url ->
                if (url.isNotBlank()) {
                    assetOrigins += url.trimEnd('/')
                }
                if (url != localAssetUrl) {
                    assetUrlRewritten = true
                }
            }
            val tailEncodeStartedAt = System.nanoTime()
            tail.copy(
                compressedFingerprint = rewrittenCompressedFingerprint,
                contentDownloadUrls = listOf(localAssetUrl),
                preserveRawPrefixWhenCompressedNull = true
            ).encode().also {
                tailEncodeMs = elapsedMsLong(tailEncodeStartedAt)
            }
        } ?: parsed.suffix
        val tailMs = elapsedMsLong(tailStartedAt)
        val rewrittenContentDownloadUrl = parsed.contentDownloadUrl?.let { url ->
            if (url.isNotBlank()) {
                assetOrigins += url.trimEnd('/')
            }
            if (url != localAssetUrl) {
                assetUrlRewritten = true
            }
            localAssetUrl
        }
        val encodeStartedAt = System.nanoTime()
        val rewritten = parsed.copy(
            reason = forcedReasonCode ?: parsed.reason,
            fingerprint = rewrittenFingerprint,
            contentDownloadUrl = rewrittenContentDownloadUrl,
            suffix = rewrittenTail
        )
        val rewrittenBody = rewritten.encode()
        val encodeMs = elapsedMsLong(encodeStartedAt)
        val resultingHashes = if (shouldPatchFingerprint) {
            FingerprintHashes(finalRootSha, null)
        } else {
            extractFingerprintHashes(rewrittenFingerprint, null)
        }
        maybeStoreLatestServerFingerprint(
            plainFingerprint = parsed.fingerprint,
            compressedFingerprint = LoginFailedTail.parse(parsed.suffix)?.compressedFingerprint,
            assetOrigins = assetOrigins,
            currentClientHelloHash = currentClientHelloHash
        )
        return LoginFailedRewriteResult(
            body = rewrittenBody,
            hashes = resultingHashes,
            patchStats = patchStats,
            assetUrlRewritten = assetUrlRewritten,
            rootShaRewriteApplied = shouldPatchRootFingerprintSha,
            assetOrigins = assetOrigins.toList(),
            originalRootSha = originalRootSha
        )
    }

    private fun extractPlainFingerprintRootSha(plainFingerprint: String?): String? {
    if (plainFingerprint.isNullOrBlank()) return null
    findLikelyRootSha(plainFingerprint)?.let { return it }
    return try {
        val root = JSONObject(plainFingerprint)
        root.optString("sha", "").ifEmpty { null }
    } catch (_: Throwable) {
        null
    }
}

    private fun findLikelyRootSha(json: String): String? {
    val shaKey = "\"sha\""
    val keyIndex = json.lastIndexOf(shaKey).takeIf { it >= 0 } ?: return null
    val colonIndex = json.indexOf(':', keyIndex + shaKey.length).takeIf { it >= 0 } ?: return null
    val valueStartQuote = json.indexOf('"', colonIndex + 1).takeIf { it >= 0 } ?: return null
    val valueStart = valueStartQuote + 1
    val valueEndQuote = json.indexOf('"', valueStart).takeIf { it >= 0 } ?: return null
    return json.substring(valueStart, valueEndQuote).ifEmpty { null }
}

    private fun replaceLikelyRootSha(json: String, oldSha: String, newSha: String): String? {
    val shaKey = "\"sha\""
    val keyIndex = json.lastIndexOf(shaKey).takeIf { it >= 0 } ?: return null
    val colonIndex = json.indexOf(':', keyIndex + shaKey.length).takeIf { it >= 0 } ?: return null
    val valueStartQuote = json.indexOf('"', colonIndex + 1).takeIf { it >= 0 } ?: return null
    val valueStart = valueStartQuote + 1
    val valueEndQuote = json.indexOf('"', valueStart).takeIf { it >= 0 } ?: return null
    if (json.substring(valueStart, valueEndQuote) != oldSha) return null
    return json.replaceRange(valueStart, valueEndQuote, newSha)
}

    private fun extractFingerprintHashes(
    plainFingerprint: String?,
    compressedFingerprint: ByteArray?
    ): FingerprintHashes {
    val fingerprintJson = plainFingerprint
        ?: compressedFingerprint?.let { inflateFingerprint(it)?.second }
        ?: return FingerprintHashes(null, null)
    return try {
        val root = JSONObject(fingerprintJson)
        val rootSha = root.optString("sha", "").ifEmpty { null }
        FingerprintHashes(rootSha, null)
    } catch (_: Throwable) {
        FingerprintHashes(null, null)
    }
}

    private fun firstDiffIndex(left: ByteArray, right: ByteArray): String {
    val limit = min(left.size, right.size)
    for (index in 0 until limit) {
        if (left[index] != right[index]) {
            val leftByte = left[index].toInt() and 0xFF
            val rightByte = right[index].toInt() and 0xFF
            return "$index:${leftByte.toString(16).padStart(2, '0')}!=${rightByte.toString(16).padStart(2, '0')}"
        }
    }
    return if (left.size == right.size) "none" else "$limit:eof"
}

    private fun rewriteFingerprintJson(
    fingerprintJson: String,
    shouldPatchRootFingerprintSha: Boolean
    ): FingerprintRewriteResult {
    if (!fingerprintJson.trimStart().startsWith("{")) {
        return FingerprintRewriteResult(fingerprintJson, null, FingerprintPatchStats())
    }
    return try {
        val root = JSONObject(fingerprintJson)
        var rewrittenJson = fingerprintJson
        var patchStats = FingerprintPatchStats()
        val oldRootSha = root.optString("sha", "")
        val files = root.optJSONArray("files") ?: return FingerprintRewriteResult(fingerprintJson, root.optString("sha", "<none>"), patchStats)
        val selectedPaths = listPatchedAssetPaths()
        for (path in selectedPaths) {
            val oldSha = findExistingFileSha(files, path)
            val newSha = patchedAssetSha(path)
            val patched = when {
                newSha == null -> null
                oldSha != null -> replaceFileShaPreservingJson(rewrittenJson, path, oldSha, newSha)
                else -> upsertFileShaPreservingJson(rewrittenJson, path, newSha)
            }
            if (patched != null) {
                rewrittenJson = patched
                patchStats = patchStats.copy(fileShaPatched = patchStats.fileShaPatched + 1)
            } else {
                VpnLogRepository.log("SC fingerprint file=$path patch failed oldSha=${oldSha ?: "<new>"}")
            }
        }
            val trigger = createGeneratedTriggerAsset()
            rewrittenJson = upsertFileShaPreservingJson(
                json = rewrittenJson,
                path = PATCH_NAMESPACE,
                sha = trigger.sha
            )?.also {
                patchStats = patchStats.copy(fileShaPatched = patchStats.fileShaPatched + 1)
            } ?: rewrittenJson.also {
                VpnLogRepository.log("SC fingerprint file=$PATCH_NAMESPACE upsert failed")
            }
        var finalRootSha = oldRootSha.ifEmpty { root.optString("sha", "<none>") }
        if (shouldPatchRootFingerprintSha && PATCH_NAMESPACE.isNotEmpty()) {
            val patchedRootSha = PATCH_NAMESPACE + randomShaSuffix()
            val patched = replaceRootShaPreservingJson(rewrittenJson, oldRootSha, patchedRootSha)
            if (patched != null) {
                rewrittenJson = patched
                finalRootSha = patchedRootSha
                patchStats = patchStats.copy(rootShaOld = oldRootSha, rootShaNew = patchedRootSha)
            } else {
                VpnLogRepository.log("SC fingerprint rootSha in-place patch failed old=$oldRootSha")
            }
        }
        FingerprintRewriteResult(rewrittenJson, finalRootSha, patchStats)
    } catch (error: Throwable) {
        VpnLogRepository.log("SC fingerprint patch failed ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
        FingerprintRewriteResult(fingerprintJson, null, FingerprintPatchStats())
    }
}

    private fun replaceFileShaPreservingJson(json: String, path: String, oldSha: String, newSha: String): String? {
    if (oldSha.isEmpty()) return null
    val pathIndex = listOf(
        "\"$path\"",
        "\"${path.replace("/", "\\/")}\""
    ).asSequence()
        .map { json.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: return null
    val objectStart = json.lastIndexOf('{', pathIndex).takeIf { it >= 0 } ?: return null
    val objectEnd = json.indexOf('}', pathIndex).takeIf { it >= 0 } ?: return null
    return replaceShaValueInRange(json, objectStart, objectEnd + 1, oldSha, newSha)
}

    private fun replaceRootShaPreservingJson(json: String, oldSha: String, newSha: String): String? {
    if (oldSha.isEmpty()) return null
    return replaceLastShaValueInRange(json, 0, json.length, oldSha, newSha)
}

    private fun upsertFileShaPreservingJson(json: String, path: String, sha: String): String? {
    val existingSha = findFileSha(json, path)
    if (existingSha != null) {
        return replaceFileShaPreservingJson(json, path, existingSha, sha)
    }
    val filesKey = "\"files\""
    val filesIndex = json.indexOf(filesKey).takeIf { it >= 0 } ?: return null
    val arrayStart = json.indexOf('[', filesIndex + filesKey.length).takeIf { it >= 0 } ?: return null
    val entry = "{\"file\":\"$path\",\"sha\":\"$sha\"}"
    val insertText = if (json.getOrNull(arrayStart + 1) == ']') entry else "$entry,"
    return json.replaceRange(arrayStart + 1, arrayStart + 1, insertText)
}

    private fun findFileSha(json: String, path: String): String? {
    val pathIndex = listOf(
        "\"$path\"",
        "\"${path.replace("/", "\\/")}\""
    ).asSequence()
        .map { json.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: return null
    val objectStart = json.lastIndexOf('{', pathIndex).takeIf { it >= 0 } ?: return null
    val objectEnd = json.indexOf('}', pathIndex).takeIf { it >= 0 } ?: return null
    val shaKey = "\"sha\""
    val keyIndex = json.indexOf(shaKey, objectStart).takeIf { it >= 0 && it < objectEnd } ?: return null
    val colonIndex = json.indexOf(':', keyIndex + shaKey.length).takeIf { it >= 0 && it < objectEnd } ?: return null
    val valueStartQuote = json.indexOf('"', colonIndex + 1).takeIf { it >= 0 && it < objectEnd } ?: return null
    val valueStart = valueStartQuote + 1
    val valueEndQuote = json.indexOf('"', valueStart).takeIf { it >= 0 && it <= objectEnd } ?: return null
    return json.substring(valueStart, valueEndQuote)
}

    private fun replaceShaValueInRange(
    json: String,
    start: Int,
    end: Int,
    oldSha: String,
    newSha: String
    ): String? {
    val shaKey = "\"sha\""
    var cursor = start
    while (cursor in start until end) {
        val keyIndex = json.indexOf(shaKey, cursor).takeIf { it >= 0 && it < end } ?: return null
        val colonIndex = json.indexOf(':', keyIndex + shaKey.length).takeIf { it >= 0 && it < end } ?: return null
        val valueStartQuote = json.indexOf('"', colonIndex + 1).takeIf { it >= 0 && it < end } ?: return null
        val valueStart = valueStartQuote + 1
        val valueEndQuote = json.indexOf('"', valueStart).takeIf { it >= 0 && it <= end } ?: return null
        val value = json.substring(valueStart, valueEndQuote)
        if (value == oldSha) {
            return json.replaceRange(valueStart, valueEndQuote, newSha)
        }
        cursor = valueEndQuote + 1
    }
    return null
}

    private fun replaceLastShaValueInRange(
    json: String,
    start: Int,
    end: Int,
    oldSha: String,
    newSha: String
    ): String? {
    val shaKey = "\"sha\""
    var cursor = start
    var foundStart = -1
    var foundEnd = -1
    while (cursor in start until end) {
        val keyIndex = json.indexOf(shaKey, cursor).takeIf { it >= 0 && it < end } ?: break
        val colonIndex = json.indexOf(':', keyIndex + shaKey.length).takeIf { it >= 0 && it < end } ?: break
        val valueStartQuote = json.indexOf('"', colonIndex + 1).takeIf { it >= 0 && it < end } ?: break
        val valueStart = valueStartQuote + 1
        val valueEndQuote = json.indexOf('"', valueStart).takeIf { it >= 0 && it <= end } ?: break
        val value = json.substring(valueStart, valueEndQuote)
        if (value == oldSha) {
            foundStart = valueStart
            foundEnd = valueEndQuote
        }
        cursor = valueEndQuote + 1
    }
    return if (foundStart >= 0 && foundEnd >= foundStart) {
        json.replaceRange(foundStart, foundEnd, newSha)
    } else {
        null
    }
}

    private fun findExistingFileSha(files: org.json.JSONArray, path: String): String? {
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            if (file.optString("file") == path) {
                return file.optString("sha", "").ifEmpty { null }
            }
        }
        return null
    }

    private fun patchedAssetSha(path: String): String? {
        val sha = getPatchedAssetSha(path)
        if (sha == null) {
            VpnLogRepository.log("SC fingerprint file=$path patch sha missing")
        }
        return sha
    }

    private fun randomShaSuffix(): String {
    return randomHex(RANDOM_SHA_SUFFIX_LENGTH)
}

    private fun randomHex(length: Int): String {
    val alphabet = "0123456789abcdef"
    return buildString(length) {
        repeat(length) {
            append(alphabet[Random.nextInt(alphabet.length)])
        }
    }
}

    private fun createGeneratedTriggerAsset(): GeneratedTriggerAsset {
    val bytes = ByteArray(GENERATED_TRIGGER_BYTES)
    Random.nextBytes(bytes)
    val sha = sha1Hex(bytes)
    val dir = File(filesDir, GENERATED_ASSET_DIR)
    dir.mkdirs()
    File(dir, PATCH_NAMESPACE).writeBytes(bytes)
    return GeneratedTriggerAsset(sha)
}

    private fun createCleanupFingerprintJson(): String {
    val trigger = createGeneratedTriggerAsset()
    return "{\"files\":[{\"file\":\"$PATCH_NAMESPACE\",\"sha\":\"${trigger.sha}\"}],\"sha\":\"$PATCH_NAMESPACE\",\"version\":\"0.0.0\"}"
}

    private fun sha1Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
    return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
}

    private fun rewriteCompressedFingerprintJson(
    compressedFingerprint: ByteArray,
    shouldPatchRootFingerprintSha: Boolean,
    includeTriggerAsset: Boolean,
    currentClientHelloHash: String?
    ): CompressedFingerprintRewriteResult {
    val cacheKey = buildDecompressedFingerprintCacheKey(compressedFingerprint)
    val prewarmedBase = findPrewarmedFingerprintBase(currentClientHelloHash)
    val inflated = if (prewarmedBase != null) {
        InflatedFingerprintCacheEntry(format = guessFingerprintFormat(compressedFingerprint), json = prewarmedBase.json)
    } else {
        inflatedFingerprintCache[cacheKey] ?: inflateFingerprint(compressedFingerprint)
            ?.let { InflatedFingerprintCacheEntry(format = it.first, json = it.second) }
            ?.also { inflatedFingerprintCache[cacheKey] = it }
            ?: return CompressedFingerprintRewriteResult(null, null, FingerprintPatchStats())
    }
    val base = prewarmedBase ?: decompressedFingerprintCache[cacheKey] ?: rewriteFingerprintJsonStructured(
        fingerprintJson = inflated.json,
        shouldPatchRootFingerprintSha = false,
        includeTriggerAsset = false
    ).also {
        decompressedFingerprintCache[cacheKey] = it
    }
    if (prewarmedBase != null) {
        decompressedFingerprintCache.putIfAbsent(cacheKey, prewarmedBase)
        inflatedFingerprintCache.putIfAbsent(cacheKey, inflated)
    }
    val rewritten = applyRuntimeFingerprintMutations(
        fingerprintJson = base.json,
        basePatchStats = base.patchStats,
        shouldPatchRootFingerprintSha = shouldPatchRootFingerprintSha,
        includeTriggerAsset = includeTriggerAsset
    )
    val bytes = deflateFingerprint(rewritten.json, inflated.format)
    return CompressedFingerprintRewriteResult(bytes, rewritten.rootSha, rewritten.patchStats, rewritten.json)
}

    private fun findPrewarmedFingerprintBase(currentClientHelloHash: String?): FingerprintRewriteResult? {
    val contentHash = currentClientHelloHash?.trim()?.ifEmpty { null } ?: return null
    if (contentHash.startsWith(PATCH_NAMESPACE)) return null
    return prewarmedFingerprintBaseCache[prewarmedBaseKey(contentHash, getCurrentModStateKey())]
}

    private fun maybeStoreLatestServerFingerprint(
        plainFingerprint: String?,
        compressedFingerprint: ByteArray?,
        assetOrigins: Collection<String>,
        currentClientHelloHash: String?
    ) {
        if (!shouldRefreshPersistentFingerprintCache(currentClientHelloHash)) return
        val fingerprintJson = when {
            !plainFingerprint.isNullOrBlank() && plainFingerprint.trimStart().startsWith("{") -> plainFingerprint
            compressedFingerprint != null -> inflateFingerprint(compressedFingerprint)?.second
            else -> null
        } ?: return
        runCatching {
            val currentHash = currentClientHelloHash?.trim().orEmpty()
            LatestFingerprintStore.saveLatest(
                filesDir = filesDir,
                fingerprintJson = fingerprintJson,
                origins = assetOrigins,
                clientHelloHash = currentHash
            )
            lastStoredServerClientHelloHash = currentHash
        }
    }

    private fun shouldRefreshPersistentFingerprintCache(currentClientHelloHash: String?): Boolean {
        val contentHash = currentClientHelloHash?.trim().orEmpty()
        if (contentHash.isEmpty() || contentHash.startsWith(PATCH_NAMESPACE)) return false
        val storedHash = lastStoredServerClientHelloHash ?: loadStoredServerClientHelloHash().also {
            lastStoredServerClientHelloHash = it
        }
        return storedHash != contentHash
    }

    private fun loadStoredServerClientHelloHash(): String? {
        return LatestFingerprintStore.readStoredClientHelloHash(filesDir)
    }

    private fun rewriteFingerprintJsonStructured(
    fingerprintJson: String,
    shouldPatchRootFingerprintSha: Boolean,
    includeTriggerAsset: Boolean = true
    ): FingerprintRewriteResult {
    return try {
        val root = JSONObject(fingerprintJson)
        val oldRootSha = root.optString("sha", "").ifEmpty { null }
        val files = root.optJSONArray("files") ?: org.json.JSONArray().also { root.put("files", it) }

        val fileObjects = HashMap<String, JSONObject>(files.length() * 2)
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            val path = file.optString("file", "")
            if (path.isNotEmpty()) {
                fileObjects[path] = file
            }
        }

        var patchStats = FingerprintPatchStats()
        for (path in listPatchedAssetPaths()) {
            val newSha = patchedAssetSha(path) ?: continue
            val fileObject = fileObjects[path]
            if (fileObject != null) {
                fileObject.put("sha", newSha)
            } else {
                val created = JSONObject()
                    .put("file", path)
                    .put("sha", newSha)
                files.put(created)
                fileObjects[path] = created
            }
            patchStats = patchStats.copy(fileShaPatched = patchStats.fileShaPatched + 1)
        }

        if (includeTriggerAsset) {
            val trigger = createGeneratedTriggerAsset()
            val triggerObject = fileObjects[PATCH_NAMESPACE]
            if (triggerObject != null) {
                triggerObject.put("sha", trigger.sha)
            } else {
                val created = JSONObject()
                    .put("file", PATCH_NAMESPACE)
                    .put("sha", trigger.sha)
                files.put(created)
                fileObjects[PATCH_NAMESPACE] = created
            }
            patchStats = patchStats.copy(fileShaPatched = patchStats.fileShaPatched + 1)
        }

        var finalRootSha = oldRootSha
        if (shouldPatchRootFingerprintSha && PATCH_NAMESPACE.isNotEmpty()) {
            finalRootSha = PATCH_NAMESPACE + randomShaSuffix()
            root.put("sha", finalRootSha)
            patchStats = patchStats.copy(rootShaOld = oldRootSha, rootShaNew = finalRootSha)
        }

        FingerprintRewriteResult(
            json = root.toString(),
            rootSha = finalRootSha ?: oldRootSha,
            patchStats = patchStats
        )
    } catch (error: Throwable) {
        VpnLogRepository.log("SC fingerprint structured patch failed ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
        rewriteFingerprintJson(fingerprintJson, shouldPatchRootFingerprintSha)
    }
}

    private fun applyRuntimeFingerprintMutations(
        fingerprintJson: String,
        basePatchStats: FingerprintPatchStats,
        shouldPatchRootFingerprintSha: Boolean,
        includeTriggerAsset: Boolean
    ): FingerprintRewriteResult {
    return try {
        val oldRootSha = extractPlainFingerprintRootSha(fingerprintJson)
        var rewrittenJson = fingerprintJson
        var patchStats = basePatchStats

        if (includeTriggerAsset) {
            val trigger = createGeneratedTriggerAsset()
            rewrittenJson = upsertFileShaPreservingJson(
                json = rewrittenJson,
                path = PATCH_NAMESPACE,
                sha = trigger.sha
            ) ?: rewrittenJson.also {
                VpnLogRepository.log("SC fingerprint file=$PATCH_NAMESPACE upsert failed")
            }
            patchStats = patchStats.copy(fileShaPatched = basePatchStats.fileShaPatched + 1)
        }

        var finalRootSha = oldRootSha
        if (shouldPatchRootFingerprintSha && !oldRootSha.isNullOrEmpty()) {
            val patchedRootSha = PATCH_NAMESPACE + randomShaSuffix()
            rewrittenJson = replaceLikelyRootSha(rewrittenJson, oldRootSha, patchedRootSha)
                ?: replaceRootShaPreservingJson(rewrittenJson, oldRootSha, patchedRootSha)
                ?: rewrittenJson.also {
                    VpnLogRepository.log("SC fingerprint rootSha in-place patch failed old=$oldRootSha")
                }
            finalRootSha = patchedRootSha
            patchStats = patchStats.copy(rootShaOld = oldRootSha, rootShaNew = patchedRootSha)
        }

        FingerprintRewriteResult(
            json = rewrittenJson,
            rootSha = finalRootSha,
            patchStats = patchStats
        )
    } catch (error: Throwable) {
        VpnLogRepository.log("SC fingerprint runtime patch failed ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
        rewriteFingerprintJsonStructured(
            fingerprintJson = fingerprintJson,
            shouldPatchRootFingerprintSha = shouldPatchRootFingerprintSha,
            includeTriggerAsset = includeTriggerAsset
        )
    }
}

    private fun buildDecompressedFingerprintCacheKey(
        compressedFingerprint: ByteArray
    ): String {
    val sourceSha = sha1Hex(compressedFingerprint)
    val modSha = getCurrentModStateKey()
    return buildString {
        append(sourceSha)
        append('|')
        append(modSha)
    }
}

    private fun inflateFingerprint(bytes: ByteArray): Pair<String, String>? {
    return inflatePrefixedZlib(bytes)?.let { "prefixed-zlib" to it }
        ?: inflateGzip(bytes)?.let { "gzip" to it }
        ?: inflateZlib(bytes, nowrap = false)?.let { "zlib" to it }
        ?: inflateZlib(bytes, nowrap = true)?.let { "deflate" to it }
}

    private fun deflateFingerprint(text: String, format: String): ByteArray {
        return when (format) {
        "prefixed-zlib" -> deflatePrefixedZlib(text)
        "gzip" -> deflateGzip(text)
        "zlib" -> deflateZlib(text, nowrap = false)
        "deflate" -> deflateZlib(text, nowrap = true)
        else -> deflateZlib(text, nowrap = false)
        }
    }

    private fun guessFingerprintFormat(bytes: ByteArray): String {
    if (bytes.size >= 6 && (bytes[4].toInt() and 0xFF) == 0x78) {
        return "prefixed-zlib"
    }
    if (bytes.size >= 2) {
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        if (first == 0x1f && second == 0x8b) return "gzip"
        if (first == 0x78) return "zlib"
    }
    return "prefixed-zlib"
}

    private fun inflateGzip(bytes: ByteArray): String? {
    return try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readBytes().decodeToString()
        }
    } catch (_: Throwable) {
        null
    }
}

    private fun inflateZlib(bytes: ByteArray, nowrap: Boolean): String? {
    return try {
        InflaterInputStream(ByteArrayInputStream(bytes), Inflater(nowrap)).use { input ->
            input.readBytes().decodeToString()
        }
    } catch (_: Throwable) {
        null
    }
}

    private fun inflatePrefixedZlib(bytes: ByteArray): String? {
    if (bytes.size <= 4) return null
    return try {
        val expectedLength = readLittleEndianInt(bytes, 0)
        val compressed = bytes.copyOfRange(4, bytes.size)
        val text = inflateZlib(compressed, nowrap = false) ?: return null
        if (expectedLength != text.encodeToByteArray().size) {
            debugLog("SC prefixed zlib length mismatch expected=$expectedLength actual=${text.encodeToByteArray().size}")
        }
        text
    } catch (_: Throwable) {
        null
    }
}

    private fun deflateZlib(text: String, nowrap: Boolean): ByteArray {
    return deflateZlibBytes(text.encodeToByteArray(), nowrap)
}

    private fun deflateZlibBytes(bytes: ByteArray, nowrap: Boolean): ByteArray {
    val output = ByteArrayOutputStream()
    val deflater = Deflater(FINGERPRINT_COMPRESSION_LEVEL, nowrap)
    try {
        DeflaterOutputStream(output, deflater, FINGERPRINT_COMPRESSION_BUFFER_SIZE).use { stream ->
            stream.write(bytes)
        }
    } finally {
        deflater.end()
    }
    return output.toByteArray()
}

    private fun deflatePrefixedZlib(text: String): ByteArray {
    val textBytes = text.encodeToByteArray()
    return writeLittleEndianInt(textBytes.size) + deflateZlibBytes(textBytes, nowrap = false)
}

    private fun deflateGzip(text: String): ByteArray {
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output, FINGERPRINT_COMPRESSION_BUFFER_SIZE).use { gzip ->
        gzip.write(text.encodeToByteArray())
    }
    return output.toByteArray()
}

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

    private fun writeLittleEndianInt(value: Int): ByteArray {
    return byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )
}

}

private fun elapsedMsLong(startedAtNanos: Long): Long {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L
}
