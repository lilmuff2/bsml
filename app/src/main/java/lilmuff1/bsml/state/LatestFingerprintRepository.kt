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
import lilmuff1.bsml.config.PATCH_NAMESPACE
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
    private const val GLOBAL_PACKAGE = "com.supercell.brawlstars"
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

    suspend fun fetchLatest(context: Context, targetServer: String? = null): Boolean = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val captureSettings = VpnLogRepository.captureSettingsNow()
        val storedGameServer = targetServer ?: readStoredGameServer(filesDir)
        val isChinaMode = when (captureSettings.autoLaunchPackage) {
            CHINA_PACKAGE -> true
            null -> isLastConnectionChina(storedGameServer)
            else -> false
        }
        val clientVersion = resolveClientVersion(context, isChinaMode)
        val targets = buildList {
            storedGameServer
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { shouldUseStoredTarget(it, isChinaMode, targetServer != null) }
                ?.let { add(it) }

            val configuredTargets = if (captureSettings.filterByIp) {
                parseTargets(captureSettings.ipFilterText)
            } else {
                parseTargets(DEFAULT_CAPTURE_TARGETS)
            }
            if (isChinaMode) {
                addAll(configuredTargets.filter(::isChinaHost))
                add(CHINA_GAME_HOST)
                add(CHINA_STAGE_HOST)
            } else {
                addAll(configuredTargets.filterNot(::isChinaHost))
                add(GAME_HOST)
            }
        }.distinct().toMutableList()

        setProgress(context, 1, context.getString(lilmuff1.bsml.R.string.fetch_latest_stage_connect))
        VpnLogRepository.log(
            "SC latest fingerprint request version=${clientVersion.major}.${clientVersion.revision}.${clientVersion.build} " +
                "china=$isChinaMode targets=${targets.joinToString()}"
        )
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
                    val loginFailedBody = readLoginFailedBody(input, target)
                        ?: error("LOGIN_FAILED not received from $target")

                    setProgress(context, 4, context.getString(lilmuff1.bsml.R.string.fetch_latest_stage_unpack))
                    val parsed = LoginFailedPrefix.parse(loginFailedBody)
                        ?: error("Failed to parse LOGIN_FAILED from $target")
                    val tail = LoginFailedTail.parse(parsed.suffix)
                    val fingerprintJson = extractFingerprintJson(parsed, tail)

                    if (fingerprintJson == null) {
                        VpnLogRepository.log("SC latest fingerprint structure host=$target ${describeLoginFailedBody(loginFailedBody, parsed, tail)}")
                        val redirectTargets = if (parsed.reason == 9) {
                            extractRedirectTargets(parsed, tail)
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

    private fun isLastConnectionChina(storedGameServer: String?): Boolean {
        return storedGameServer?.let(::isChinaHost) == true
    }

    private fun shouldUseStoredTarget(target: String, isChinaMode: Boolean, explicitTarget: Boolean): Boolean {
        if (explicitTarget) return true
        return if (isChinaMode) {
            true
        } else {
            isKnownGlobalTarget(target)
        }
    }

    private fun isKnownGlobalTarget(target: String): Boolean {
        val normalized = target.lowercase().trim()
        return normalized == GAME_HOST ||
            normalized.startsWith("62.") ||
            (!isIpv4(normalized) && !isChinaHost(normalized))
    }

    private fun isChinaHost(target: String): Boolean {
        val normalized = target.lowercase().trim()
        return normalized.endsWith(".cn") ||
            normalized.contains("brawlstars.cn") ||
            normalized.contains("tencent")
    }

    private fun isIpv4(target: String): Boolean {
        return target.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
    }

    private fun buildClientHelloBody(version: FetchClientVersion): ByteArray {
        val writer = ByteWriter()
        writer.writeInt(2)
        writer.writeInt(56)
        writer.writeInt(version.major)
        writer.writeInt(version.revision)
        writer.writeInt(version.build)
        writer.writeString(PATCH_NAMESPACE)
        writer.writeInt(2)
        writer.writeInt(2)
        return writer.toByteArray()
    }

    private fun resolveClientVersion(context: Context, isChinaMode: Boolean): FetchClientVersion {
        if (!isChinaMode) return FetchClientVersion(0, 0, 0)
        val installedVersion = getInstalledVersionForRegion(context, isChinaMode)
        return parseClientVersion(installedVersion)
            ?: parseClientVersion(VpnLogRepository.lastClientVersionNow())
            ?: FetchClientVersion(0, 0, 0)
    }

    private fun getInstalledVersionForRegion(context: Context, isChinaMode: Boolean): String? {
        val captureSettings = VpnLogRepository.captureSettingsNow()
        val packages = if (isChinaMode) {
            listOf(CHINA_PACKAGE)
        } else {
            buildList {
                captureSettings.autoLaunchPackage?.takeIf { it != CHINA_PACKAGE }?.let { add(it) }
                captureSettings.packageText.split(',', ' ', '\n', '\r', '\t')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != CHINA_PACKAGE }
                    .forEach { add(it) }
                add(GLOBAL_PACKAGE)
            }.distinct()
        }
        for (pkg in packages) {
            try {
                val info = context.packageManager.getPackageInfo(pkg, 0)
                val version = info.versionName?.trim()
                if (!version.isNullOrEmpty()) {
                    return version
                }
            } catch (_: Throwable) {
                // package not installed
            }
        }
        return null
    }

    private fun parseClientVersion(value: String?): FetchClientVersion? {
        val parts = value
            ?.trim()
            ?.split('.', '-', '_')
            ?.mapNotNull { it.toIntOrNull() }
            ?: return null
        if (parts.isEmpty()) return null
        val major = parts.getOrNull(0) ?: 0
        val revision = parts.getOrNull(1) ?: 0
        val build = parts.getOrNull(2) ?: 0
        return FetchClientVersion(major, revision, build)
    }

    private fun readLoginFailedBody(input: java.io.InputStream, target: String): ByteArray? {
        while (true) {
            val header = readExactly(input, 7)
            val messageId = readUInt16(header, 0)
            val bodyLength = readUInt24(header, 2)
            val version = readUInt16(header, 5)
            val body = readExactly(input, bodyLength)
            VpnLogRepository.log(
                "SC latest fingerprint recv host=$target msg=${messageName(messageId)} " +
                    "id=$messageId len=$bodyLength ver=$version first=${body.hexPreview()}"
            )
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

    private fun describeLoginFailedBody(
        body: ByteArray,
        parsed: LoginFailedPrefix,
        tail: LoginFailedTail?
    ): String {
        return buildString {
            append("bodyLen=${body.size}")
            append(" reason=${parsed.reason}")
            append(" fingerprintLen=${parsed.fingerprint?.length ?: -1}")
            append(" fingerprintPreview=${parsed.fingerprint.previewForLog()}")
            append(" unknown=${parsed.unknownString.previewForLog()}")
            append(" contentUrl=${parsed.contentDownloadUrl.previewForLog()}")
            append(" updateUrl=${parsed.updateUrl.previewForLog()}")
            append(" reasonText=${parsed.reasonText.previewForLog()}")
            append(" maintenance=${parsed.maintenanceWaitSecs}")
            append(" suffixLen=${parsed.suffix.size}")
            if (tail == null) {
                append(" tailParse=false suffixFirst=${parsed.suffix.hexPreview()}")
            } else {
                append(" tailParse=true")
                append(" tailBool=${tail.unknownBoolean}")
                append(" compressedLen=${tail.compressedFingerprint?.size ?: -1}")
                append(" compressedFirst=${tail.compressedFingerprint.hexPreview()}")
                append(" urls=${tail.contentDownloadUrls.size}")
                tail.contentDownloadUrls.take(4).forEachIndexed { index, url ->
                    append(" url[$index]=${url.previewForLog()}")
                }
                append(" rawSuffixLen=${tail.rawSuffix.size}")
                append(" rawSuffixFirst=${tail.rawSuffix.hexPreview()}")
            }
        }
    }

    private fun extractRedirectTargets(
        parsed: LoginFailedPrefix,
        tail: LoginFailedTail?
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
                host.contains("brawlstars") && !host.contains("asset")
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

    private fun messageName(messageId: Int): String {
        return when (messageId) {
            CLIENT_HELLO_ID -> "CLIENT_HELLO"
            10101 -> "LOGIN"
            20100 -> "SERVER_HELLO"
            LOGIN_FAILED_ID -> "LOGIN_FAILED"
            20104 -> "LOGIN_OK"
            else -> "UNKNOWN"
        }
    }

    private fun ByteArray?.hexPreview(maxBytes: Int = 24): String {
        if (this == null) return "<null>"
        if (isEmpty()) return "<empty>"
        return take(maxBytes).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun String?.previewForLog(maxChars: Int = 96): String {
        val value = this ?: return "<null>"
        if (value.isEmpty()) return "<empty>"
        return value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(maxChars)
    }

    fun extractAndSaveFingerprint(context: Context, loginFailedBody: ByteArray): Boolean {
        try {
            VpnLogRepository.log("SC extract-fingerprint: Attempting to extract fingerprint directly from intercepted LOGIN_FAILED packet (length=${loginFailedBody.size})...")
            val parsed = LoginFailedPrefix.parse(loginFailedBody)
            if (parsed == null) {
                VpnLogRepository.log("SC extract-fingerprint failed: Could not parse LoginFailedPrefix")
                return false
            }
            val tail = LoginFailedTail.parse(parsed.suffix)
            val fingerprintJson = extractFingerprintJson(parsed, tail)
            if (fingerprintJson == null) {
                VpnLogRepository.log("SC extract-fingerprint failed: Fingerprint JSON not found in prefix or tail (reason=${parsed.reason}, suffixLen=${parsed.suffix?.size ?: 0})")
                return false
            }
            val rootSha = extractRootSha(fingerprintJson)
            if (rootSha == null) {
                VpnLogRepository.log("SC extract-fingerprint failed: Root SHA not found in fingerprint JSON: $fingerprintJson")
                return false
            }
            val origins = linkedSetOf<String>().apply {
                parsed.contentDownloadUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                tail?.contentDownloadUrls
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?.forEach(::add)
            }
            
            saveLatest(context.filesDir, fingerprintJson, origins, rootSha)
            LoginFailedRewritePrewarmer.prewarm(context)
            refreshState(context)
            VpnLogRepository.log("SC extract-fingerprint success: Extracted and saved fingerprint directly! rootSha=$rootSha, origins=${origins.size}")
            return true
        } catch (e: Throwable) {
            VpnLogRepository.log("SC extract-fingerprint error: ${e::class.java.simpleName}: ${e.message}")
            return false
        }
    }
}
