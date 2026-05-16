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
import kotlin.concurrent.thread

class LocalAssetProxyServer(
    private val onLog: (String) -> Unit,
    private val onFirstAssetRequest: () -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var firstAssetRequestSeen = false

    fun start() {
        if (running) return

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("127.0.0.1", AssetProxyConfig.PORT))
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
                    if (!firstAssetRequestSeen) {
                        firstAssetRequestSeen = true
                        onFirstAssetRequest()
                    }
                    onLog("ASSET $method $path")
                    proxyToOrigin(method, path, headers, output)
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

    private fun proxyToOrigin(
        method: String,
        path: String,
        headers: Map<String, String>,
        output: BufferedOutputStream
    ) {
        val origins = listOf(
            AssetProxyConfig.PRIMARY_ASSET_BASE_ORIGIN,
            AssetProxyConfig.SECONDARY_ASSET_BASE_ORIGIN
        )

        var successfulConnection: HttpURLConnection? = null
        var lastConnection: HttpURLConnection? = null
        for (origin in origins) {
            val connection = openConnection(origin + path, method, headers)
            lastConnection = connection
            val code = connection.responseCode
            if (code !in listOf(404, 403)) {
                successfulConnection = connection
                break
            }
            connection.disconnect()
        }

        val connection = successfulConnection ?: lastConnection ?: throw IOException("No asset origin available")
        writeHttpResponse(connection, output)
        connection.disconnect()
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
            values.forEach { value ->
                val rewrittenValue = if (name.equals("Location", ignoreCase = true)) {
                    value
                        .replace(AssetProxyConfig.PRIMARY_ASSET_BASE_ORIGIN, AssetProxyConfig.LOCAL_BASE_URL)
                        .replace(AssetProxyConfig.SECONDARY_ASSET_BASE_ORIGIN, AssetProxyConfig.LOCAL_BASE_URL)
                } else {
                    value
                }
                output.write("$name: $rewrittenValue\r\n".encodeToByteArray())
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
}

private fun BufferedInputStream.readAsciiLine(): String? {
    val out = ByteArrayOutputStream()
    while (true) {
        val value = read()
        if (value == -1) {
            return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
        }
        if (value == '\n'.code) break
        if (value != '\r'.code) out.write(value)
    }
    return out.toString(Charsets.US_ASCII.name())
}
