package lilmuff1.bsml.vpn

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
import lilmuff1.bsml.logging.VpnLogRepository
import lilmuff1.bsml.protocol.ByteQueue
import lilmuff1.bsml.protocol.SupercellConstants
import lilmuff1.bsml.protocol.SupercellMessageRewriter
import lilmuff1.bsml.protocol.SupercellStreamParser
import lilmuff1.bsml.protocol.buildSupercellMessage
import lilmuff1.bsml.protocol.readUInt16
import lilmuff1.bsml.protocol.readUInt24

class TcpProxySession(
    private val key: SessionKey,
    clientInitialSeq: Long,
    private val clientWindow: Int,
    private val protectSocket: (Socket) -> Boolean,
    private val sendToTun: (ByteArray) -> Unit,
    private val onClosed: (SessionKey) -> Unit
) {
    private var remoteSocket: Socket? = null
    private var remoteChannel: SocketChannel? = null
    private val streamParser = SupercellStreamParser()
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

    fun start() {
        sendSynAck()
        thread(name = "bsml-remote-connect", start = true) {
            try {
                val channel = SocketChannel.open()
                val socket = channel.socket()
                if (!protectSocket(socket)) {
                    channel.close()
                    throw IOException("protect(socket) returned false")
                }

                socket.tcpNoDelay = true
                socket.soTimeout = VpnRelayConfig.SOCKET_READ_TIMEOUT_MS
                socket.connect(
                    InetSocketAddress(intToIpv4(key.serverIp), key.serverPort),
                    VpnRelayConfig.SOCKET_CONNECT_TIMEOUT_MS
                )
                channel.configureBlocking(false)
                remoteSocket = socket
                remoteChannel = channel
                remoteConnected = true
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

    fun matches(event: PacketEvent): Boolean = SessionKey.fromEvent(event) == key

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
            if (remoteConnected) {
                flushPendingClientPayloads()
            }
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

        if (!remoteConnected || !sawClientAck) {
            pendingClientPayloads += payload.copyOf()
            sendAck()
            return
        }

        writeClientPayload(payload)
    }

    fun onClientFin(event: PacketEvent) {
        if (closed) return
        clientNextSeq = maxOf(clientNextSeq, event.sequenceNumber + 1L)
        serverAck = clientNextSeq
        sendAck()
        sendFinAck()
        close()
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
        onClosed(key)
    }

    private fun writeClientPayload(payload: ByteArray) {
        if (closed || !remoteConnected) return

        synchronized(outboundLock) {
            outboundQueue.addLast(payload)
            outboundLock.notifyAll()
        }
    }

    private fun flushPendingClientPayloads() {
        if (closed || !remoteConnected || !sawClientAck || pendingClientPayloads.isEmpty()) return

        val queued = pendingClientPayloads.toList()
        pendingClientPayloads.clear()
        queued.forEach(::writeClientPayload)
    }

    private fun startRemoteWriter() {
        remoteWriterThread = thread(name = "bsml-remote-writer", start = true) {
            while (!closed) {
                val payload = try {
                    synchronized(outboundLock) {
                        while (!closed && outboundQueue.isEmpty()) {
                            outboundLock.wait()
                        }
                        if (closed) null else outboundQueue.removeFirst()
                    }
                } catch (_: InterruptedException) {
                    close()
                    break
                } ?: break

                try {
                    val channel = remoteChannel ?: break
                    writeFullyToRemote(channel, payload)
                    sendAck()
                    streamParser.onClientBytes(payload)
                } catch (_: InterruptedException) {
                    close()
                    break
                } catch (error: Exception) {
                    debugLog("Remote write failed: ${error.message ?: "unknown"}")
                    sendReset()
                    close()
                    break
                } catch (error: Throwable) {
                    VpnLogRepository.log("FATAL remote-writer ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
                    sendReset()
                    close()
                    break
                }
            }
        }
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
                    handleServerBytes(chunk)
                }
            } catch (_: InterruptedException) {
            } catch (_: IOException) {
            } catch (error: Throwable) {
                VpnLogRepository.log("FATAL remote-reader ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
            } finally {
                if (!closed) {
                    sendFinAck()
                    close()
                }
            }
        }
    }

    private fun handleServerBytes(bytes: ByteArray) {
        serverMessageBuffer.append(bytes)
        while (serverMessageBuffer.size >= SupercellConstants.HEADER_SIZE) {
            val header = serverMessageBuffer.peek(SupercellConstants.HEADER_SIZE)
            val messageId = readUInt16(header, 0)
            val payloadLength = readUInt24(header, 2)
            val version = readUInt16(header, 5)
            val fullLength = SupercellConstants.HEADER_SIZE + payloadLength
            if (serverMessageBuffer.size < fullLength) return

            serverMessageBuffer.skip(SupercellConstants.HEADER_SIZE)
            val body = serverMessageBuffer.read(payloadLength)
            val transformedBody = if (messageId == SupercellConstants.LOGIN_FAILED) {
                SupercellMessageRewriter.rewriteLoginFailedAssets(body)
            } else {
                body
            }
            val outgoing = buildSupercellMessage(messageId, transformedBody, version)
            sendPayloadFromServer(outgoing)
            streamParser.onServerBytes(outgoing)
        }
    }

    private fun sendSynAck() {
        sendTcpPacket(flags = TCP_SYN or TCP_ACK, seq = serverInitialSeq, ack = clientNextSeq)
        serverSeq = serverInitialSeq + 1L
    }

    private fun sendAck() {
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
            val chunkSize = min(VpnRelayConfig.MAX_TCP_PAYLOAD, payload.size - offset)
            val chunk = payload.copyOfRange(offset, offset + chunkSize)
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
        sendToTun(packet)
    }

    private fun writeFullyToRemote(channel: SocketChannel, payload: ByteArray) {
        val buffer = ByteBuffer.wrap(payload)
        val deadline = System.currentTimeMillis() + VpnRelayConfig.SOCKET_WRITE_TIMEOUT_MS
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
