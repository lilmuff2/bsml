package lilmuff1.bsml

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

class AssetProxyService : Service() {
    private val assetProxyServer = LocalAssetProxyServer(
        onLog = { message -> VpnLogRepository.log(message) },
        openPatchedAsset = { path ->
            val generated = runCatching {
                File(filesDir, "generated_assets/$path").takeIf { it.isFile }?.readBytes()
            }.getOrNull()
            generated ?: runCatching {
                assets.open("patches/$path").use { input -> input.readBytes() }
            }.getOrNull()
        },
        onPatchedTargetServed = {
            startService(
                Intent(this, LocalVpnService::class.java)
                    .setAction(LocalVpnService.ACTION_PATCHED_ASSET_SERVED)
            )
        },
        onFirstAssetRequest = {
        }
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopProxy()
            ACTION_START, null -> {
                assetProxyServer.setServePatchedTarget(
                    intent?.getBooleanExtra(EXTRA_SERVE_PATCHED_TARGET, true) ?: true
                )
                startProxy()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProxy() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        try {
            assetProxyServer.start()
            VpnLogRepository.setAssetProxyRunning(true)
            VpnLogRepository.setStatus("Asset proxy active")
        } catch (error: Throwable) {
            VpnLogRepository.log("ASSET fatal ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
            stopProxy()
        }
    }

    private fun stopProxy() {
        assetProxyServer.stop()
        VpnLogRepository.setAssetProxyRunning(false)
        if (!VpnLogRepository.isVpnRunningNow()) {
            VpnLogRepository.setStatus("Stopped")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("BSML Asset Proxy")
            .setContentText("Proxying game assets")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "BSML Asset Proxy",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val ACTION_START = "lilmuff1.bsml.action.START_ASSET_PROXY"
        const val ACTION_STOP = "lilmuff1.bsml.action.STOP_ASSET_PROXY"
        const val EXTRA_SERVE_PATCHED_TARGET = "lilmuff1.bsml.extra.SERVE_PATCHED_TARGET"
        const val PRIMARY_LOCAL_BASE_URL = "http://127.0.0.1:8787"
        const val PRIMARY_ASSET_BASE_ORIGIN = "https://game-assets.brawlstarsgame.com"
        const val SECONDARY_ASSET_BASE_ORIGIN = "https://game-assets-2.brawlstars.com"
        private const val NOTIFICATION_CHANNEL_ID = "bsml_asset_proxy"
        private const val NOTIFICATION_ID = 1002
    }
}

private class LocalAssetProxyServer(
    private val onLog: (String) -> Unit,
    private val openPatchedAsset: (String) -> ByteArray?,
    private val onPatchedTargetServed: () -> Unit,
    private val onFirstAssetRequest: () -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false
    @Volatile
    private var firstAssetRequestSeen = false
    @Volatile
    private var servePatchedTarget = true

    val localBaseUrl: String
        get() = AssetProxyService.PRIMARY_LOCAL_BASE_URL

    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", PORT))
        serverSocket = socket
        running = true
        acceptThread = thread(name = "bsml-asset-proxy-accept", start = true) {
            while (running) {
                try {
                    val client = socket.accept()
                    thread(name = "bsml-asset-proxy-client", start = true) {
                        handleClient(client)
                    }
                } catch (_: IOException) {
                    if (!running) return@thread
                }
            }
        }
    }

    fun setServePatchedTarget(isEnabled: Boolean) {
        servePatchedTarget = isEnabled
        onLog("ASSET serve patched target=$isEnabled")
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 15_000
        try {
            BufferedInputStream(client.getInputStream()).use { input ->
                BufferedOutputStream(client.getOutputStream()).use { output ->
                    val requestLine = input.readAsciiLine() ?: return
                    if (requestLine.isBlank()) return
                    val parts = requestLine.split(" ")
                    if (parts.size < 2) {
                        writeSimpleResponse(output, 400, "Bad Request")
                        return
                    }
                    val method = parts[0]
                    val path = parts[1]
                    val headers = readHeaders(input)
                    val assetPath = normalizeAssetPath(path.substringBefore('?'))
                    if (!firstAssetRequestSeen) {
                        firstAssetRequestSeen = true
                        onFirstAssetRequest()
                    }
                    handleAssetRequest(method, path, assetPath, headers, output)
                }
            }
        } catch (error: Throwable) {
            onLog("ASSET error ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
        } finally {
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun handleAssetRequest(
        method: String,
        path: String,
        assetPath: String,
        headers: Map<String, String>,
        output: BufferedOutputStream
    ) {
        val patchedAsset = openPatchedAsset(assetPath)
        if (patchedAsset != null && shouldServePatchedAsset(path, assetPath)) {
            onLog("ASSET $method $path normalized=$assetPath result=patched bytes=${patchedAsset.size}")
            if (assetPath == PATCHED_ASSET_PATH && VpnLogRepository.isContentHashRewriteEnabledNow()) {
                VpnLogRepository.setContentHashRewriteEnabled(false)
                onPatchedTargetServed()
            }
            writePatchedAssetResponse(method, assetPath, patchedAsset, output)
            return
        }

        val origins = listOf(
            AssetProxyService.PRIMARY_ASSET_BASE_ORIGIN,
            AssetProxyService.SECONDARY_ASSET_BASE_ORIGIN
        )

        var successfulConnection: HttpURLConnection? = null
        var lastConnection: HttpURLConnection? = null
        var lastCode = -1
        var lastUrl = ""
        val originPath = rewriteCustomRootPathForOrigin(path)
        for (origin in origins) {
            val originUrl = origin + originPath
            val connection = openConnection(originUrl, method, headers)
            lastConnection = connection
            val code = connection.responseCode
            lastCode = code
            lastUrl = originUrl
            if (code !in listOf(404, 403)) {
                successfulConnection = connection
                break
            }
            connection.disconnect()
        }

        val connection = successfulConnection ?: lastConnection ?: throw IOException("No asset origin available")
        onLog("ASSET $method $path normalized=$assetPath result=origin code=$lastCode url=$lastUrl")
        writeHttpResponse(connection, output)
        connection.disconnect()
    }

    private fun shouldServePatchedAsset(path: String, assetPath: String): Boolean {
        if (assetPath != PATCHED_ASSET_PATH) return true
        return servePatchedTarget
    }

    private fun rewriteCustomRootPathForOrigin(path: String): String {
        val query = path.substringAfter('?', "")
        val cleanPath = path.substringBefore('?')
        val leadingSlash = cleanPath.startsWith("/")
        val parts = cleanPath.removePrefix("/").split("/")
        if (parts.size <= 1 || !parts.first().startsWith(PATCHED_ROOT_PREFIX)) return path
        val rewritten = (if (leadingSlash) "/" else "") +
            (listOf(ORIGINAL_ASSET_ROOT_SHA) + parts.drop(1)).joinToString("/")
        return if (query.isEmpty()) rewritten else "$rewritten?$query"
    }

    private fun normalizeAssetPath(path: String): String {
        val cleanPath = path.removePrefix("/")
        if (cleanPath == PATCHED_ASSET_PATH || cleanPath.endsWith("/$PATCHED_ASSET_PATH")) {
            return PATCHED_ASSET_PATH
        }
        val parts = cleanPath.split("/")
        return if (
            parts.size > 1 &&
            (
                parts.first().startsWith(PATCHED_ROOT_PREFIX) ||
                    (parts.first().length == SHA1_HEX_LENGTH && parts.first().all { it in '0'..'9' || it in 'a'..'f' })
                )
        ) {
            parts.drop(1).joinToString("/")
        } else {
            cleanPath
        }
    }

    private fun writePatchedAssetResponse(
        method: String,
        path: String,
        bytes: ByteArray,
        output: BufferedOutputStream
    ) {
        val contentType = when {
            path.endsWith(".csv", ignoreCase = true) -> "text/csv"
            path.endsWith(".json", ignoreCase = true) -> "application/json"
            path.endsWith(".png", ignoreCase = true) -> "image/png"
            else -> "application/octet-stream"
        }
        output.write("HTTP/1.1 200 OK\r\n".encodeToByteArray())
        output.write("Content-Type: $contentType\r\n".encodeToByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".encodeToByteArray())
        output.write("Connection: close\r\n".encodeToByteArray())
        output.write("\r\n".encodeToByteArray())
        if (!method.equals("HEAD", ignoreCase = true)) {
            output.write(bytes)
        }
        output.flush()
    }

    private fun openConnection(
        urlString: String,
        method: String,
        headers: Map<String, String>
    ): HttpURLConnection {
        return (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept-Encoding", "identity")
            headers["Range"]?.let { setRequestProperty("Range", it) }
            headers["User-Agent"]?.let { setRequestProperty("User-Agent", it) }
        }
    }

    private fun writeHttpResponse(
        connection: HttpURLConnection,
        output: BufferedOutputStream
    ) {
        val responseCode = connection.responseCode
        val responseMessage = connection.responseMessage ?: "OK"
        output.write("HTTP/1.1 $responseCode $responseMessage\r\n".encodeToByteArray())

        connection.headerFields.forEach { (name, values) ->
            if (name == null || values.isNullOrEmpty()) return@forEach
            if (name.equals("Transfer-Encoding", ignoreCase = true)) return@forEach
            val rewrittenValues = values.map { value ->
                if (name.equals("Location", ignoreCase = true)) {
                    value
                        .replace(AssetProxyService.PRIMARY_ASSET_BASE_ORIGIN, AssetProxyService.PRIMARY_LOCAL_BASE_URL)
                        .replace(AssetProxyService.SECONDARY_ASSET_BASE_ORIGIN, AssetProxyService.PRIMARY_LOCAL_BASE_URL)
                } else {
                    value
                }
            }
            rewrittenValues.forEach { value ->
                output.write("$name: $value\r\n".encodeToByteArray())
            }
        }
        output.write("\r\n".encodeToByteArray())

        val responseStream = try {
            connection.inputStream
        } catch (_: IOException) {
            connection.errorStream
        }
        responseStream?.use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
        }
        output.flush()
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readAsciiLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
        }
        return headers
    }

    private fun writeSimpleResponse(output: BufferedOutputStream, code: Int, message: String) {
        output.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\n\r\n".encodeToByteArray())
        output.flush()
    }

    companion object {
        private const val PORT = 8787
        private const val SHA1_HEX_LENGTH = 40
        private const val PATCHED_ASSET_PATH = "localization/ru.csv"
        private const val PATCHED_ROOT_PREFIX = "lilmuff1"
        private const val ORIGINAL_ASSET_ROOT_SHA = "39a61268951b6f55ca52a1eb33e3a31ab5e7e9e4"
    }
}

private fun BufferedInputStream.readAsciiLine(): String? {
    val out = ByteArrayOutputStream()
    while (true) {
        val value = read()
        if (value == -1) {
            return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
        }
        if (value == '\n'.code) {
            break
        }
        if (value != '\r'.code) {
            out.write(value)
        }
    }
    return out.toString(Charsets.US_ASCII.name())
}

private fun debugAssetLog(message: String) {
    if (ENABLE_ASSET_REQUEST_LOGS) {
        VpnLogRepository.log(message)
    }
}

private const val ENABLE_ASSET_REQUEST_LOGS = false
