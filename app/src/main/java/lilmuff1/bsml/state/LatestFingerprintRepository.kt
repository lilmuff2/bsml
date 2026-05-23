package lilmuff1.bsml.state

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import lilmuff1.bsml.config.CHINA_GAME_HOST
import lilmuff1.bsml.config.CHINA_STAGE_HOST
import lilmuff1.bsml.config.DEFAULT_CAPTURE_TARGETS
import lilmuff1.bsml.config.GAME_HOST
import lilmuff1.bsml.protocol.ByteWriter
import lilmuff1.bsml.protocol.LoginFailedPrefix
import lilmuff1.bsml.protocol.LoginFailedTail
import lilmuff1.bsml.protocol.buildSupercellMessage
import lilmuff1.bsml.protocol.readUInt16
import lilmuff1.bsml.protocol.readUInt24
import lilmuff1.bsml.service.LoginFailedRewritePrewarmer
import org.json.JSONArray
import org.json.JSONObject

data class LatestFingerprintState(
    val isFetching: Boolean = false,
    val step: Int = 0,
    val totalSteps: Int = 0,
    val message: String? = null,
    val error: String? = null,
    val savedGameServer: String? = null,
    val savedRootSha: String? = null,
    val savedGameVersion: Int? = null,
    val savedOriginsCount: Int = 0
) {
    val progress: Float
        get() = if (totalSteps <= 0) 0f else step.toFloat() / totalSteps.toFloat()
}

object LatestFingerprintRepository {
    private const val CLIENT_HELLO_ID = 10100
    private const val LOGIN_FAILED_ID = 0x4E87
    private const val HELLO_VERSION = 0
    private const val SOCKET_CONNECT_TIMEOUT_MS = 10_000
    private const val SOCKET_READ_TIMEOUT_MS = 15_000
    private const val TOTAL_STEPS = 5

    private const val LAST_SERVER_FINGERPRINT_FILE = "last_server_fingerprint.json"
    private const val LAST_SERVER_FINGERPRINT_STATE_FILE = "last_server_fingerprint_state.json"
    private const val LAST_ASSET_ORIGINS_FILE = "last_asset_origins.json"
    private const val LAST_GAME_SERVER_FILE = "last_game_server.json"
    private const val CHINA_PACKAGE = "com.tencent.tmgp.supercell.brawlstars"
    private val GLOBAL_DEFAULT_TARGETS = setOf(GAME_HOST, "62.233.36.83", "62.233.36.84")

    private val _state = MutableStateFlow(LatestFingerprintState())
    val state = _state.asStateFlow()

    fun refreshState(context: Context) {
        val filesDir = context.filesDir
        val savedGameServer = readStoredGameServer(filesDir)
        val savedRootSha = readStoredClientHelloHash(filesDir)
        val savedGameVersion = readStoredGameVersion(filesDir)
        val savedOriginsCount = readStoredOrigins(filesDir).size
        _state.value = _state.value.copy(
            isFetching = false,
            step = 0,
            totalSteps = 0,
            message = null,
            error = null,
            savedGameServer = savedGameServer,
            savedRootSha = savedRootSha,
            savedGameVersion = savedGameVersion,
            savedOriginsCount = savedOriginsCount
        )
    }

    suspend fun fetchLatest(context: Context): Boolean = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val captureSettings = VpnLogRepository.captureSettingsNow()
        val storedGameServer = readStoredGameServer(filesDir)
        val isChinaMode = isChinaCapture(captureSettings)
        val clientVersion = parseClientVersion(VpnLogRepository.lastClientVersionNow())
            ?: FetchClientVersion(0, 0, 0)
        val configuredTargets = if (captureSettings.filterByIp) {
            parseTargets(captureSettings.ipFilterText)
        } else if (isChinaMode) {
            listOf(CHINA_GAME_HOST, CHINA_STAGE_HOST)
        } else {
            parseTargets(DEFAULT_CAPTURE_TARGETS)
        }
        val targets = buildList {
            storedGameServer
                ?.takeIf { it.isNotBlank() }
                ?.takeUnless { isChinaMode && isGlobalDefaultTarget(it) }
                ?.let(::add)
            addAll(filterTargetsForMode(configuredTargets, isChinaMode))
            if (isChinaMode) {
                add(CHINA_GAME_HOST)
                add(CHINA_STAGE_HOST)
            }
            if (isEmpty()) add(GAME_HOST)
        }.distinct().toMutableList()

        setProgress(context, 1, context.getString(lilmuff1.bsml.R.string.fetch_latest_stage_connect))
        var lastError: String? = null

        var targetIndex = 0
        while (targetIndex < targets.size) {
            val target = targets[targetIndex++]
            try {
                Socket().use { socket ->
                    socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                    socket.connect(InetSocketAddress(target, captureSettings.port), SOCKET_CONNECT_TIMEOUT_MS)

                    setProgress(context, 2, context.getString(lilmuff1.bsml.R.string.fetch_latest_stage_request, target))
                    val output = socket.getOutputStream()
                    val input = socket.getInputStream()
                    output.write(buildSupercellMessage(CLIENT_HELLO_ID, buildClientHelloBody(clientVersion), HELLO_VERSION))
                    output.flush()

                    setProgress(context, 3, context.getString(lilmuff1.bsml.R.string.fetch_latest_stage_wait))
                    val loginFailedBody = readLoginFailedBody(input)
                        ?: error("LOGIN_FAILED not received from $target")

                    setProgress(context, 4, context.getString(lilmuff1.bsml.R.string.fetch_latest_stage_unpack))
                    val parsed = LoginFailedPrefix.parse(loginFailedBody)
                        ?: error("Failed to parse LOGIN_FAILED from $target")
                    val tail = LoginFailedTail.parse(parsed.suffix)
                    val fingerprintJson = extractFingerprintJson(parsed, tail)
                    if (fingerprintJson == null) {
                        val redirectTargets = if (parsed.reason == 9) {
                            extractRedirectTargets(parsed, tail, isChinaMode)
                        } else {
                            emptyList()
                        }
                        redirectTargets
                            .filter { it !in targets }
                            .forEach { redirect ->
                                targets += redirect
                                VpnLogRepository.log("SC latest fingerprint redirect host=$target -> $redirect")
                            }
                        error("Fingerprint not found in LOGIN_FAILED from $target")
                    }
                    val rootSha = extractRootSha(fingerprintJson)
                        ?: error("Fingerprint root sha missing in LOGIN_FAILED from $target")
                    val origins = linkedSetOf<String>().apply {
                        parsed.contentDownloadUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                        tail?.contentDownloadUrls
                            ?.map(String::trim)
                            ?.filter(String::isNotEmpty)
                            ?.forEach(::add)
                    }

                    setProgress(context, 5, context.getString(lilmuff1.bsml.R.string.fetch_latest_stage_save))
                    saveLatest(filesDir, fingerprintJson, origins, rootSha)
                    storeObservedGameServer(filesDir, target)
                    LoginFailedRewritePrewarmer.prewarm(context)
                    VpnLogRepository.log("SC latest fingerprint fetched rootSha=$rootSha origins=${origins.size} host=$target")
                    refreshState(context)
                    _state.value = _state.value.copy(
                        isFetching = false,
                        step = TOTAL_STEPS,
                        totalSteps = TOTAL_STEPS,
                        message = context.getString(lilmuff1.bsml.R.string.fetch_latest_done, rootSha),
                        error = null
                    )
                    return@withContext true
                }
            } catch (error: Throwable) {
                lastError = error.message ?: error::class.java.simpleName
                VpnLogRepository.log("SC latest fingerprint fetch failed host=$target error=${error::class.java.simpleName}: ${error.message ?: "unknown"}")
            }
        }

        _state.value = _state.value.copy(
            isFetching = false,
            step = 0,
            totalSteps = 0,
            message = null,
            error = lastError ?: context.getString(lilmuff1.bsml.R.string.fetch_latest_error_unknown)
        )
        false
    }

    fun saveLatest(
        filesDir: File,
        fingerprintJson: String,
        origins: Collection<String>,
        clientHelloHash: String
    ) {
        File(filesDir, LAST_SERVER_FINGERPRINT_FILE).writeText(fingerprintJson)
        File(filesDir, LAST_SERVER_FINGERPRINT_STATE_FILE).writeText(
            JSONObject()
                .put("clientHelloHash", clientHelloHash)
                .toString()
        )
        if (origins.isNotEmpty()) {
            File(filesDir, LAST_ASSET_ORIGINS_FILE).writeText(
                JSONObject()
                    .put("clientHelloHash", clientHelloHash)
                    .put("origins", JSONArray().apply {
                        origins.forEach(::put)
                    })
                    .toString()
            )
        }
    }

    fun readStoredClientHelloHash(filesDir: File): String? {
        val file = File(filesDir, LAST_SERVER_FINGERPRINT_STATE_FILE)
        if (!file.isFile) return null
        return runCatching {
            JSONObject(file.readText())
                .optString("clientHelloHash", "")
                .trim()
                .ifEmpty { null }
        }.getOrNull()
    }

    fun readStoredOrigins(filesDir: File): List<String> {
        val file = File(filesDir, LAST_ASSET_ORIGINS_FILE)
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val origins = root.optJSONArray("origins") ?: JSONArray()
            buildList {
                for (index in 0 until origins.length()) {
                    val value = origins.optString(index).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }
        }.getOrElse { emptyList() }
    }

    fun storeObservedGameServer(context: Context, host: String) {
        storeObservedGameServer(context.filesDir, host)
        _state.value = _state.value.copy(savedGameServer = host.trim().ifEmpty { null })
    }

    fun storeObservedGameServer(filesDir: File, host: String) {
        val normalized = host.trim()
        if (normalized.isEmpty()) return
        File(filesDir, LAST_GAME_SERVER_FILE).writeText(
            JSONObject()
                .put("host", normalized)
                .toString()
        )
    }

    fun readStoredGameServer(filesDir: File): String? {
        val file = File(filesDir, LAST_GAME_SERVER_FILE)
        if (!file.isFile) return null
        return runCatching {
            JSONObject(file.readText())
                .optString("host", "")
                .trim()
                .ifEmpty { null }
        }.getOrNull()
    }

    fun readStoredGameVersion(filesDir: File): Int? {
        val file = File(filesDir, LAST_SERVER_FINGERPRINT_FILE)
        if (!file.isFile) return null
        return runCatching {
            extractGameVersion(file.readText())
        }.getOrNull()
    }

    private fun setProgress(context: Context, step: Int, message: String) {
        _state.value = _state.value.copy(
            isFetching = true,
            step = step,
            totalSteps = TOTAL_STEPS,
            message = message,
            error = null,
            savedGameServer = _state.value.savedGameServer,
            savedRootSha = _state.value.savedRootSha,
            savedGameVersion = _state.value.savedGameVersion,
            savedOriginsCount = _state.value.savedOriginsCount
        )
        VpnLogRepository.setStatus(message)
        if (step == 1) {
            VpnLogRepository.log(context.getString(lilmuff1.bsml.R.string.log_fetch_latest_started))
        }
    }

    private fun parseTargets(text: String): List<String> {
        return text
            .split(',', '\n', '\r', '\t', ' ')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
    }

    private fun isChinaCapture(settings: VpnCaptureSettings): Boolean {
        val packageText = settings.packageText.lowercase()
        val targetText = settings.ipFilterText.lowercase()
        return settings.autoLaunchPackage == CHINA_PACKAGE ||
            packageText.split(',', '\n', '\r', '\t', ' ').any { it.trim() == CHINA_PACKAGE } ||
            targetText.contains(".cn")
    }

    private fun filterTargetsForMode(targets: List<String>, isChinaMode: Boolean): List<String> {
        if (!isChinaMode) return targets
        return targets.filterNot(::isGlobalDefaultTarget)
    }

    private fun isGlobalDefaultTarget(target: String): Boolean {
        return GLOBAL_DEFAULT_TARGETS.contains(target.trim().lowercase())
    }

    private fun buildClientHelloBody(version: FetchClientVersion): ByteArray {
        val writer = ByteWriter()
        writer.writeInt(2)
        writer.writeInt(56)
        writer.writeInt(version.major)
        writer.writeInt(version.revision)
        writer.writeInt(version.build)
        writer.writeString("")
        writer.writeInt(2)
        writer.writeInt(2)
        return writer.toByteArray()
    }

    private fun parseClientVersion(value: String?): FetchClientVersion? {
        val parts = value
            ?.trim()
            ?.split('.', '-', '_')
            ?.mapNotNull { it.toIntOrNull() }
            ?: return null
        if (parts.size < 3) return null
        return FetchClientVersion(parts[0], parts[1], parts[2])
    }

    private fun readLoginFailedBody(input: java.io.InputStream): ByteArray? {
        while (true) {
            val header = readExactly(input, 7)
            val messageId = readUInt16(header, 0)
            val bodyLength = readUInt24(header, 2)
            val body = readExactly(input, bodyLength)
            if (messageId == LOGIN_FAILED_ID) {
                return body
            }
        }
    }

    private fun readExactly(input: java.io.InputStream, size: Int): ByteArray {
        val output = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(output, offset, size - offset)
            if (read < 0) throw EOFException("Unexpected EOF")
            offset += read
        }
        return output
    }

    private fun extractFingerprintJson(parsed: LoginFailedPrefix, tail: LoginFailedTail?): String? {
        val plain = parsed.fingerprint?.trim()
        if (!plain.isNullOrEmpty() && plain.startsWith("{")) return plain
        val compressed = tail?.compressedFingerprint ?: return null
        return inflateFingerprint(compressed)
    }

    private fun extractRedirectTargets(
        parsed: LoginFailedPrefix,
        tail: LoginFailedTail?,
        isChinaMode: Boolean
    ): List<String> {
        val textFields = listOfNotNull(
            parsed.unknownString,
            parsed.contentDownloadUrl,
            parsed.updateUrl,
            parsed.reasonText
        ) + tail?.contentDownloadUrls.orEmpty()
        val hostPattern = Regex("""(?i)(?:https?://)?([a-z0-9.-]+\.[a-z]{2,})(?::\d+)?""")
        return textFields
            .flatMap { field ->
                hostPattern.findAll(field).mapNotNull { match ->
                    match.groupValues.getOrNull(1)
                        ?.trim()
                        ?.trimEnd('/')
                        ?.lowercase()
                }.toList()
            }
            .filter { host ->
                host.contains("brawlstars") &&
                    !host.contains("asset") &&
                    (!isChinaMode || host.endsWith(".cn"))
            }
            .distinct()
    }

    private fun extractRootSha(fingerprintJson: String): String? {
        return runCatching {
            JSONObject(fingerprintJson).optString("sha", "").ifEmpty { null }
        }.getOrNull()
    }

    private fun extractGameVersion(fingerprintJson: String): Int? {
        val keys = arrayOf("@gv", "gv", "gameVersion", "version")
        for (key in keys) {
            val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\"[^\"]+\"|\\d+)")
            pattern.findAll(fingerprintJson)
                .lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
                ?.trim('"')
                ?.let(::parseVersionMajor)
                ?.let { return it }
        }
        return null
    }

    private fun parseVersionMajor(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> Regex("\\d+").find(value)?.value?.toIntOrNull()
            else -> null
        }
    }

    private data class FetchClientVersion(
        val major: Int,
        val revision: Int,
        val build: Int
    )

    private fun inflateFingerprint(bytes: ByteArray): String? {
        return inflatePrefixedZlib(bytes)
            ?: inflateGzip(bytes)
            ?: inflateZlib(bytes, nowrap = false)
            ?: inflateZlib(bytes, nowrap = true)
    }

    private fun inflatePrefixedZlib(bytes: ByteArray): String? {
        if (bytes.size <= 4) return null
        return inflateZlib(bytes.copyOfRange(4, bytes.size), nowrap = false)
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
}
