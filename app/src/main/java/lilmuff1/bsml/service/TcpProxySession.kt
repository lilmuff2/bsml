package lilmuff1.bsml.service

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.random.Random
import lilmuff1.bsml.config.PATCH_NAMESPACE
import lilmuff1.bsml.config.localAssetBaseUrl
import lilmuff1.bsml.protocol.*
import lilmuff1.bsml.state.VpnLogRepository
import lilmuff1.bsml.vpn.*

interface TcpProxySessionCallbacks {
    fun protectSocket(socket: Socket): Boolean
    fun sendToTun(packet: ByteArray)
    fun removeSession(key: SessionKey)
    fun shouldRewriteContentHash(): Boolean
    fun onClientHelloContentHashObserved(contentHash: String)
    fun onClientHelloVersionObserved(version: String)
    fun onFinalOriginalClientHello(contentHash: String)
    fun rewriteLoginFailedBody(body: ByteArray, version: Int): LoginFailedRewriteResult
    fun maybeBuildLocalLoginFailedResponse(
        contentHash: String,
        clientHelloLog: String
    ): ByteArray?
}

private const val SOCKET_CONNECT_TIMEOUT_MS = 10_000
private const val SOCKET_READ_TIMEOUT_MS = 15_000
private const val SOCKET_WRITE_TIMEOUT_MS = 5_000
private const val MAX_TCP_PAYLOAD = 1400
private const val CLIENT_HELLO_ID = 10100
private const val LOGIN_FAILED_ID = 0x4E87

class TcpProxySession(
    private val key: SessionKey,
    clientInitialSeq: Long,
    private val clientWindow: Int,
    private val callbacks: TcpProxySessionCallbacks
) {
    private var remoteSocket: Socket? = null
    private var remoteChannel: SocketChannel? = null
    private val streamParser = SupercellStreamParser(
        onEncryptedStageReached = {},
        localhostAssetBaseUrl = localAssetBaseUrl()
    )
    private val serverMessageBuffer = ByteQueue()
    private val serverInitialSeq = Random.nextLong(1, Int.MAX_VALUE.toLong())

    @Volatile
    private var closed = false

    @Volatile
    private var remoteConnected = false

    private var clientNextSeq = clientInitialSeq + 1L
    private var serverSeq = serverInitialSeq
    private var serverAck = clientNextSeq
    private var sawClientAck = false
    private var remoteThread: Thread? = null
    private var remoteWriterThread: Thread? = null
    private val pendingClientPayloads = ArrayList<ByteArray>()
    private val outboundQueue = ArrayDeque<ByteArray>()
    private val outboundLock = Object()
    @Volatile
    private var closeReason = "unknown"

    fun start() {
        sendSynAck()
        thread(name = "bsml-remote-connect", start = true) {
            try {
                val channel = SocketChannel.open()
                val socket = channel.socket()
                val protectOk = callbacks.protectSocket(socket)
                debugLog("Protect socket result=$protectOk")
                if (!protectOk) {
                    channel.close()
                    throw IOException("callbacks.protectSocket(socket) returned false")
                }

                socket.tcpNoDelay = true
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                socket.connect(
                    InetSocketAddress(intToIpv4(key.serverIp), key.serverPort),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
                channel.configureBlocking(false)
                remoteSocket = socket
                remoteChannel = channel
                remoteConnected = true
                debugLog(
                    "Remote connected ${intToIpv4(key.serverIp)}:${key.serverPort} " +
                        "from ${socket.localAddress.hostAddress}:${socket.localPort}"
                )
                startRemoteWriter()
                flushPendingClientPayloads()
                startRemoteReader()
            } catch (error: IOException) {
                debugLog("Remote connect failed: ${error.message ?: "unknown"}")
                sendReset()
                close()
            } catch (error: Throwable) {
                VpnLogRepository.log("FATAL remote-connect ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
                sendReset()
                close()
            }
        }
    }

    fun matches(event: PacketEvent): Boolean {
        return SessionKey.fromEvent(event) == key
    }

    fun isClosed(): Boolean = closed

    fun onDuplicateSyn(event: PacketEvent) {
        if (closed) return
        clientNextSeq = event.sequenceNumber + 1L
        serverAck = clientNextSeq
        sendSynAck()
    }

    fun onClientAck(ackNumber: Long) {
        if (closed) return
        if (ackNumber == serverInitialSeq + 1L) {
            sawClientAck = true
            debugLog("Client accepted SYN-ACK ack=$ackNumber")
            if (remoteConnected) {
                flushPendingClientPayloads()
            }
        } else {
            debugLog("Client ACK update ack=$ackNumber serverSeq=$serverSeq")
        }
    }

    fun onClientPayload(event: PacketEvent, payload: ByteArray) {
        if (closed) return

        if (event.sequenceNumber != clientNextSeq) {
            debugLog("Client seq mismatch expected=$clientNextSeq got=${event.sequenceNumber}")
            sendAck()
            return
        }

        clientNextSeq += payload.size.toLong()
        serverAck = clientNextSeq
        debugLog(
            "Handle client payload size=${payload.size} seq=${event.sequenceNumber} " +
                "firstBytes=${payload.take(min(16, payload.size)).toByteArray().toHexString()}"
        )

        if (!remoteConnected || !sawClientAck) {
            pendingClientPayloads += payload.copyOf()
            debugLog(
                "Queue client payload size=${payload.size} " +
                    "remoteConnected=$remoteConnected sawClientAck=$sawClientAck"
            )
            sendAck()
            return
        }

        writeClientPayload(payload)
    }

    private fun writeClientPayload(payload: ByteArray) {
        if (closed) return
        if (!remoteConnected) return

        val outgoingPayload = rewriteClientPayload(
            payload = payload,
            shouldRewriteContentHash = callbacks.shouldRewriteContentHash()
        )
        if (outgoingPayload == null) {
            debugLog("Client payload consumed locally size=${payload.size}")
            sendAck()
            return
        }
        debugLog(
            "Queue remote write size=${outgoingPayload.size} " +
                "firstBytes=${outgoingPayload.take(min(16, outgoingPayload.size)).toByteArray().toHexString()}"
        )
        synchronized(outboundLock) {
            outboundQueue.addLast(outgoingPayload)
            outboundLock.notifyAll()
        }
    }

    private fun rewriteClientPayload(
        payload: ByteArray,
        shouldRewriteContentHash: Boolean
    ): ByteArray? {
        var offset = 0
        var changed = false
        val writer = ByteWriter()

        while (offset + SUPERCELL_HEADER_SIZE <= payload.size) {
            val messageId = readUInt16(payload, offset)
            val payloadLength = readUInt24(payload, offset + 2)
            val version = readUInt16(payload, offset + 5)
            val fullLength = SUPERCELL_HEADER_SIZE + payloadLength
            if (payloadLength < 0 || offset + fullLength > payload.size) break

            val bodyStart = offset + SUPERCELL_HEADER_SIZE
            val body = payload.copyOfRange(bodyStart, bodyStart + payloadLength)
            val outgoingBody = if (messageId == CLIENT_HELLO_ID) {
                val rewriteStartedAt = System.nanoTime()
                readClientHelloVersion(body)?.let { callbacks.onClientHelloVersionObserved(it.displayName) }
                val oldContentHash = readClientHelloContentHash(body) ?: "<null>"
                callbacks.onClientHelloContentHashObserved(oldContentHash)
                val shouldRewriteThisClientHello =
                    shouldRewriteContentHash && !oldContentHash.startsWith(PATCH_NAMESPACE)
                val clientHelloLog = buildString {
                    append("SC CLIENT_HELLO contentHash=")
                    if (shouldRewriteThisClientHello) {
                        append("$oldContentHash->$PATCH_NAMESPACE")
                    } else {
                        append(oldContentHash)
                    }
                    append(" local LOGIN_FAILED rewriteMs=${elapsedMs(rewriteStartedAt)}")
                    append(" len=$payloadLength ver=$version")
                }
                val localLoginFailed = callbacks.maybeBuildLocalLoginFailedResponse(oldContentHash, clientHelloLog)
                if (localLoginFailed != null) {
                    changed = true
                    sendPayloadFromServer(localLoginFailed)
                    null
                } else if (shouldRewriteThisClientHello) {
                    rewriteClientHello(
                        body = body,
                        forcedContentHash = PATCH_NAMESPACE,
                        zeroGameVersion = false
                    )?.also {
                        changed = true
                        VpnLogRepository.log(
                            buildString {
                                append("SC CLIENT_HELLO contentHash=")
                                append("$oldContentHash->$PATCH_NAMESPACE")
                                append(" rewriteMs=${elapsedMs(rewriteStartedAt)}")
                                append(" len=$payloadLength ver=$version")
                            }
                        )
                    } ?: body
                } else {
                    VpnLogRepository.log(
                        "SC CLIENT_HELLO contentHash=$oldContentHash rewriteMs=${elapsedMs(rewriteStartedAt)} len=$payloadLength ver=$version"
                    )
                    callbacks.onFinalOriginalClientHello(oldContentHash)
                    body
                }
            } else {
                body
            }

            if (outgoingBody != null) {
                writer.writeBytes(buildSupercellMessage(messageId, outgoingBody, version))
            }
            offset += fullLength
        }

        if (offset < payload.size) {
            writer.writeBytes(payload.copyOfRange(offset, payload.size))
        }

        return if (changed) writer.toByteArray().takeIf { it.isNotEmpty() } else payload
    }

    private fun flushPendingClientPayloads() {
        if (closed || !remoteConnected || !sawClientAck || pendingClientPayloads.isEmpty()) {
            return
        }

        val queued = pendingClientPayloads.toList()
        pendingClientPayloads.clear()
        debugLog("Flush queued client payloads count=${queued.size}")
        queued.forEach { payload ->
            writeClientPayload(payload)
        }
    }

    private fun startRemoteWriter() {
        remoteWriterThread = thread(name = "bsml-remote-writer", start = true) {
            while (!closed) {
                val payload = try {
                    synchronized(outboundLock) {
                        while (!closed && outboundQueue.isEmpty()) {
                            outboundLock.wait()
                        }
                        if (closed) {
                            null
                        } else {
                            outboundQueue.removeFirst()
                        }
                    }
                } catch (_: InterruptedException) {
                    closeReason = "remote-writer-interrupted"
                    close()
                    break
                } ?: break

                try {
                    val channel = remoteChannel ?: break
                    debugLog("Remote write start size=${payload.size}")
                    writeFullyToRemote(channel, payload)
                    debugLog("Remote write done size=${payload.size}")
                    sendAck()
                    logClientPayload(payload)
                } catch (_: InterruptedException) {
                    closeReason = "remote-writer-interrupted"
                    close()
                    break
                } catch (error: Exception) {
                    debugLog("Remote write failed: ${error.message ?: "unknown"}")
                    closeReason = "remote-write-failed"
                    sendReset()
                    close()
                    break
                } catch (error: Throwable) {
                    VpnLogRepository.log("FATAL remote-writer ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
                    closeReason = "fatal-remote-writer"
                    sendReset()
                    close()
                    break
                }
            }
        }
    }

    fun onClientFin(event: PacketEvent) {
        if (closed) return
        clientNextSeq = maxOf(clientNextSeq, event.sequenceNumber + 1L)
        serverAck = clientNextSeq
        sendAck()
        sendFinAck()
        closeReason = "client-fin"
        close()
    }

    private fun startRemoteReader() {
        remoteThread = thread(name = "bsml-remote-reader", start = true) {
            try {
                val channel = remoteChannel ?: return@thread
                val buffer = ByteBuffer.allocate(8192)
                while (!closed) {
                    buffer.clear()
                    val read = channel.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        Thread.sleep(5)
                        continue
                    }

                    buffer.flip()
                    val chunk = ByteArray(read)
                    buffer.get(chunk)
                    debugLog("Remote read size=$read for ${key.clientPort}")
                    handleServerBytes(chunk)
                }
                debugLog("Remote EOF for ${key.clientPort}")
            } catch (_: InterruptedException) {
                debugLog("Remote reader interrupted for ${key.clientPort}")
            } catch (_: IOException) {
                debugLog("Remote reader closed for ${key.clientPort}")
            } catch (error: Throwable) {
                VpnLogRepository.log("FATAL remote-reader ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
                closeReason = "fatal-remote-reader"
            } finally {
                if (!closed) {
                    closeReason = if (closeReason == "unknown") "remote-reader-finally" else closeReason
                    sendFinAck()
                    close()
                }
            }
        }
    }

    private fun sendSynAck() {
        debugLog("Send SYN-ACK seq=$serverInitialSeq ack=$clientNextSeq")
        sendTcpPacket(flags = TCP_SYN or TCP_ACK, seq = serverInitialSeq, ack = clientNextSeq)
        serverSeq = serverInitialSeq + 1L
    }

    private fun sendAck() {
        debugLog("Send ACK seq=$serverSeq ack=$serverAck")
        sendTcpPacket(flags = TCP_ACK, seq = serverSeq, ack = serverAck)
    }

    private fun sendFinAck() {
        sendTcpPacket(flags = TCP_FIN or TCP_ACK, seq = serverSeq, ack = serverAck)
        serverSeq += 1L
    }

    private fun sendReset() {
        sendTcpPacket(flags = TCP_RST or TCP_ACK, seq = serverSeq, ack = serverAck)
    }

    private fun sendPayloadFromServer(payload: ByteArray) {
        var offset = 0
        while (offset < payload.size && !closed) {
            val chunkSize = min(MAX_TCP_PAYLOAD, payload.size - offset)
            val chunk = payload.copyOfRange(offset, offset + chunkSize)
            debugLog(
                "Inject server payload seq=$serverSeq ack=$serverAck chunk=$chunkSize " +
                    "firstBytes=${chunk.take(8).toByteArray().toHexString()}"
            )
            sendTcpPacket(
                flags = TCP_ACK or TCP_PSH,
                seq = serverSeq,
                ack = serverAck,
                payload = chunk
            )
            serverSeq += chunk.size.toLong()
            offset += chunkSize
        }
    }

    private fun sendTcpPacket(
        flags: Int,
        seq: Long,
        ack: Long,
        payload: ByteArray = byteArrayOf()
    ) {
        val packet = TcpPacketBuilder.build(
            sourceIp = key.serverIp,
            destinationIp = key.clientIp,
            sourcePort = key.serverPort,
            destinationPort = key.clientPort,
            sequenceNumber = seq,
            acknowledgmentNumber = ack,
            flags = flags,
            windowSize = clientWindow.coerceAtLeast(65535),
            payload = payload
        )
        callbacks.sendToTun(packet)
    }

    private fun logClientPayload(payload: ByteArray) {
        streamParser.onClientBytes(payload)
    }

    private fun logServerPayload(payload: ByteArray) {
        streamParser.onServerBytes(payload)
    }

    private fun handleServerBytes(bytes: ByteArray) {
        serverMessageBuffer.append(bytes)
        while (serverMessageBuffer.size >= SUPERCELL_HEADER_SIZE) {
            val header = serverMessageBuffer.peek(SUPERCELL_HEADER_SIZE)
            val messageId = readUInt16(header, 0)
            val payloadLength = readUInt24(header, 2)
            val version = readUInt16(header, 5)
            val fullLength = SUPERCELL_HEADER_SIZE + payloadLength
            if (serverMessageBuffer.size < fullLength) {
                return
            }

            serverMessageBuffer.skip(SUPERCELL_HEADER_SIZE)
            val body = serverMessageBuffer.read(payloadLength)
            val transformedBody = if (messageId == LOGIN_FAILED_ID) {
                val rewriteStartedAt = System.nanoTime()
                val rewriteResult = callbacks.rewriteLoginFailedBody(body, version)
                rewriteResult.body.also { transformed ->
                    VpnLogRepository.log(
                        formatLoginFailedLog(
                            result = rewriteResult,
                            oldLength = body.size,
                            newLength = transformed.size,
                            version = version
                        ) + " rewriteMs=${elapsedMs(rewriteStartedAt)}"
                    )
                }
            } else {
                body
            }
            val outgoing = buildSupercellMessage(messageId, transformedBody, version)
            sendPayloadFromServer(outgoing)
            logServerPayload(outgoing)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            remoteChannel?.close()
        } catch (_: IOException) {
        }
        remoteChannel = null
        try {
            remoteSocket?.close()
        } catch (_: IOException) {
        }
        remoteSocket = null
        remoteThread?.interrupt()
        remoteThread = null
        synchronized(outboundLock) {
            outboundQueue.clear()
            outboundLock.notifyAll()
        }
        remoteWriterThread?.interrupt()
        remoteWriterThread = null
        callbacks.removeSession(key)
    }

    private fun writeFullyToRemote(channel: SocketChannel, payload: ByteArray) {
        val buffer = ByteBuffer.wrap(payload)
        val deadline = System.currentTimeMillis() + SOCKET_WRITE_TIMEOUT_MS
        var writtenTotal = 0

        while (buffer.hasRemaining() && !closed) {
            val written = channel.write(buffer)
            if (written > 0) {
                writtenTotal += written
                continue
            }

            if (System.currentTimeMillis() > deadline) {
                throw SocketTimeoutException("remote write timed out after $writtenTotal/${payload.size} bytes")
            }
            Thread.sleep(2)
        }

        if (closed) {
            throw IOException("session closed during remote write")
        }
    }
}

private fun elapsedMs(startedAtNanos: Long): String {
    return ((System.nanoTime() - startedAtNanos) / 1_000_000.0).toStringWithThreeDecimals()
}

private fun Double.toStringWithThreeDecimals(): String {
    return String.format(java.util.Locale.US, "%.3f", this)
}
