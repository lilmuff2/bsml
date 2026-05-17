package lilmuff1.bsml.asset

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import lilmuff1.bsml.config.LOCAL_ASSET_HOST
import lilmuff1.bsml.config.LOCAL_ASSET_PORT
import lilmuff1.bsml.config.PATCH_NAMESPACE
import lilmuff1.bsml.config.localAssetBaseUrl

class LocalAssetProxyServer(
    private val onLog: (String) -> Unit,
    private val openPatchedAsset: (String) -> ByteArray?,
    private val onFirstAssetRequest: () -> Unit,
    private val onPatchedAssetServed: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var firstAssetRequestSeen = false

    @Volatile
    private var origins: List<String> = emptyList()

    @Volatile
    private var originalRootSha: String? = null
    private val originsCacheByRootSha = ConcurrentHashMap<String, List<String>>()

    fun setRouting(newOrigins: List<String>, newOriginalRootSha: String?) {
        val normalizedOrigins = newOrigins
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()
        val normalizedRootSha = newOriginalRootSha?.takeIf { it.isNotBlank() }
        val cachedOrigins = normalizedRootSha?.let { originsCacheByRootSha[it] }
        val nextOrigins = when {
            normalizedOrigins.isNotEmpty() -> normalizedOrigins
            cachedOrigins != null -> cachedOrigins
            else -> origins
        }
        origins = nextOrigins
        originalRootSha = normalizedRootSha ?: originalRootSha
        normalizedRootSha?.let { rootSha ->
            if (normalizedOrigins.isNotEmpty()) {
                originsCacheByRootSha[rootSha] = normalizedOrigins
            }
        }
    }

    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(LOCAL_ASSET_HOST, LOCAL_ASSET_PORT))
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
        if (patchedAsset != null) {
            onPatchedAssetServed(assetPath)
            onLog("ASSET $method $path normalized=$assetPath result=patched bytes=${patchedAsset.size}")
            writePatchedAssetResponse(method, assetPath, patchedAsset, output)
            return
        }

        val originsSnapshot = origins
        if (originsSnapshot.isEmpty()) {
            onLog("ASSET $method $path normalized=$assetPath result=no-origin")
            writeSimpleResponse(output, 502, "No Origin")
            return
        }

        var successfulConnection: HttpURLConnection? = null
        var lastConnection: HttpURLConnection? = null
        var lastCode = -1
        var lastUrl = ""
        val originPath = rewriteCustomRootPathForOrigin(path)
        for (origin in originsSnapshot) {
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

    private fun rewriteCustomRootPathForOrigin(path: String): String {
        val query = path.substringAfter('?', "")
        val cleanPath = path.substringBefore('?')
        val leadingSlash = cleanPath.startsWith("/")
        val parts = cleanPath.removePrefix("/").split("/")
        if (parts.size <= 1 || !parts.first().startsWith(PATCH_NAMESPACE)) return path
        val rootSha = originalRootSha ?: return path
        val rewritten = (if (leadingSlash) "/" else "") +
            (listOf(rootSha) + parts.drop(1)).joinToString("/")
        return if (query.isEmpty()) rewritten else "$rewritten?$query"
    }

    private fun normalizeAssetPath(path: String): String {
        val cleanPath = path.removePrefix("/")
        val parts = cleanPath.split("/")
        return if (
            parts.size > 1 &&
            (
                parts.first().startsWith(PATCH_NAMESPACE) ||
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
                    rewriteLocationHeader(value)
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

    private fun rewriteLocationHeader(value: String): String {
        var rewritten = value
        origins.forEach { origin ->
            rewritten = rewritten.replace(origin, localAssetBaseUrl())
        }
        return rewritten
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
        private const val SHA1_HEX_LENGTH = 40
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
