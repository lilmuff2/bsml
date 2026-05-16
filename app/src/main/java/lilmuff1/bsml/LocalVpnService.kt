package lilmuff1.bsml

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.random.Random
import org.json.JSONObject

class LocalVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var starterThread: Thread? = null
    private var tunOutput: FileOutputStream? = null
    private val proxyState = ProxyState()

    @Volatile
    private var active = false

    @Volatile
    private var starting = false

    @Volatile
    private var interceptionDisabled = false

    @Volatile
    private var loginFailedRewriteDone = false

    @Volatile
    private var loginFailedSeenCount = 0

    @Volatile
    private var originalRootAfterPatchSeen = false

    @Volatile
    private var finalOriginalClientHelloHandled = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopVpn()
                ACTION_STOP_AFTER_ASSET -> disableVpnTunnelKeepService()
                ACTION_PATCHED_ASSET_SERVED -> onPatchedAssetServed()
                ACTION_START, null -> startVpn()
            }
        } catch (error: Throwable) {
            VpnLogRepository.log("FATAL onStartCommand ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
            stopVpn()
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun startVpn() {
        if (active || starting) {
            debugLog("VPN already running")
            return
        }

        starting = true
        interceptionDisabled = false
        loginFailedRewriteDone = false
        loginFailedSeenCount = 0
        originalRootAfterPatchSeen = false
        finalOriginalClientHelloHandled = false
        VpnLogRepository.setContentHashRewriteEnabled(true)
        VpnLogRepository.setStatus("Starting VPN...")
        VpnLogRepository.log(
            "VPN start requested; contentHash rewrite enabled " +
                "fingerprintRewrite=${VpnLogRepository.isFingerprintRewriteEnabledNow()} " +
                "fileShaRewrite=${VpnLogRepository.isFileShaRewriteEnabledNow()}"
        )

        val notification = buildNotification("TCP proxy is starting")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        starterThread = thread(name = "bsml-vpn-starter", start = true) {
            try {
                val targetAddresses = resolveTargetAddresses()
                if (targetAddresses.isEmpty()) {
                    VpnLogRepository.log("VPN resolve failed for $TARGET_HOST")
                    VpnLogRepository.setStatus("Resolve failed")
                    stopVpn()
                    return@thread
                }

                val builder = Builder()
                    .setSession("BSML Local VPN")
                    .setMtu(MTU)
                    .setBlocking(true)
                    .addAddress(TUN_ADDRESS, 32)

                targetAddresses.forEach { address ->
                    builder.addRoute(address.hostAddress ?: return@forEach, 32)
                }

                vpnInterface = builder.establish()
                val descriptor = vpnInterface
                if (descriptor == null) {
                    debugLog("Builder.establish() returned null")
                    VpnLogRepository.setStatus("VPN establish failed")
                    stopVpn()
                    return@thread
                }

                tunOutput = FileOutputStream(descriptor.fileDescriptor)
                active = true
                VpnLogRepository.setRunning(true)
                VpnLogRepository.setStatus("Listening on $TARGET_HOST:$TARGET_PORT")
                VpnLogRepository.log("VPN established for ${targetAddresses.joinToString { it.hostAddress ?: "?" }}")

                val targetIpInts = targetAddresses.map { ipv4BytesToInt(it.address) }.toSet()
                readerThread = thread(name = "bsml-vpn-reader", start = true) {
                    readLoop(targetIpInts)
                }
            } finally {
                starting = false
            }
        }
    }

    private fun stopVpn() {
        if (!active && !starting && vpnInterface == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        active = false
        starting = false

        readerThread?.interrupt()
        readerThread = null
        starterThread?.interrupt()
        starterThread = null

        proxyState.close()

        try {
            tunOutput?.close()
        } catch (_: IOException) {
        }
        tunOutput = null

        try {
            vpnInterface?.close()
        } catch (_: IOException) {
        }
        vpnInterface = null

        VpnLogRepository.setRunning(false)
        VpnLogRepository.setStatus("Stopped")
        debugLog("VPN stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun disableVpnTunnelKeepService() {
        interceptionDisabled = true
        proxyState.close()
        active = false
        starting = false
        readerThread?.interrupt()
        readerThread = null
        starterThread?.interrupt()
        starterThread = null
        try {
            tunOutput?.close()
        } catch (_: IOException) {
        }
        tunOutput = null
        try {
            vpnInterface?.close()
        } catch (_: IOException) {
        }
        vpnInterface = null
        VpnLogRepository.setRunning(false)
        VpnLogRepository.setStatus("Asset proxy active")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onPatchedAssetServed() {
        VpnLogRepository.setContentHashRewriteEnabled(false)
    }

    private fun readLoop(targetIpInts: Set<Int>) {
        val descriptor = vpnInterface ?: return

        try {
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val packet = ByteArray(MTU)
                while (active && !Thread.currentThread().isInterrupted) {
                    val length = input.read(packet)
                    if (length <= 0) continue

                    val event = PacketParser.parse(packet, length, targetIpInts) ?: continue
                    if (event.protocol != "TCP") {
                        continue
                    }
                    if (interceptionDisabled) {
                        continue
                    }
                    try {
                        handleTcpPacket(event)
                    } catch (error: Throwable) {
                        VpnLogRepository.log("FATAL handleTcpPacket ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
                    }
                }
            }
        } catch (error: IOException) {
            if (active) {
                debugLog("VPN reader error: ${error.message ?: "unknown"}")
                VpnLogRepository.setStatus("Reader error")
            }
        } catch (error: Throwable) {
            VpnLogRepository.log("FATAL readLoop ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
        } finally {
            if (active) {
                stopVpn()
            }
        }
    }

    private fun handleTcpPacket(event: PacketEvent) {
        if (event.destinationPort != TARGET_PORT && event.sourcePort != TARGET_PORT) {
            return
        }

        val flags = event.tcpFlags
        val clientPayload = event.payload()

        if (flags and TCP_SYN != 0 && event.destinationPort == TARGET_PORT) {
            debugLog("TCP SYN ${event.sourceIp}:${event.sourcePort} -> ${event.destinationIp}:${event.destinationPort}")
            if (!proxyState.tryStartSession(event)) {
                debugLog("Reject extra SYN ${event.sourcePort}, active session is already running")
                sendStandaloneReset(event)
            }
            return
        }

        val session = proxyState.find(event) ?: return
        if (!session.matches(event)) return

        if (flags and TCP_RST != 0) {
            debugLog("TCP RST from client")
            session.close()
            return
        }

        if (flags and TCP_ACK != 0) {
            debugLog(
                "Client ACK seq=${event.sequenceNumber} ack=${event.ackNumber} " +
                    "payload=${clientPayload.size} flags=${tcpFlagsToString(flags)} port=${event.sourcePort}"
            )
            session.onClientAck(event.ackNumber)
        }

        if (clientPayload.isNotEmpty()) {
            session.onClientPayload(event, clientPayload)
        }

        if (flags and TCP_FIN != 0) {
            session.onClientFin(event)
        }
    }

    private fun resolveTargetAddresses(): List<Inet4Address> {
        var lastError: Throwable? = null
        repeat(DNS_RESOLVE_ATTEMPTS) { index ->
            val attempt = index + 1
            try {
                val addresses = InetAddress.getAllByName(TARGET_HOST)
                    .filterIsInstance<Inet4Address>()
                    .distinctBy { it.hostAddress }
                if (addresses.isNotEmpty()) {
                    VpnLogRepository.log(
                        "VPN resolved $TARGET_HOST attempt=$attempt ips=${addresses.joinToString { it.hostAddress ?: "?" }}"
                    )
                    return addresses
                }
                VpnLogRepository.log("VPN resolve empty $TARGET_HOST attempt=$attempt")
            } catch (error: IOException) {
                lastError = error
                VpnLogRepository.log(
                    "VPN resolve attempt=$attempt failed ${error::class.java.simpleName}: ${error.message ?: "unknown"}"
                )
            }

            if (attempt < DNS_RESOLVE_ATTEMPTS) {
                try {
                    Thread.sleep(DNS_RESOLVE_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return emptyList()
                }
            }
        }

        lastError?.let { error ->
            VpnLogRepository.log(
                "VPN resolve failed final ${error::class.java.simpleName}: ${error.message ?: "unknown"}"
            )
        }
        return emptyList()
    }

    private fun sendToTun(bytes: ByteArray) {
        try {
            tunOutput?.write(bytes)
            tunOutput?.flush()
        } catch (error: IOException) {
            debugLog("Failed to write to TUN: ${error.message ?: "unknown"}")
        }
    }

    private fun sendStandaloneReset(event: PacketEvent) {
        val packet = TcpPacketBuilder.build(
            sourceIp = event.destinationIpInt,
            destinationIp = event.sourceIpInt,
            sourcePort = event.destinationPort,
            destinationPort = event.sourcePort,
            sequenceNumber = 0,
            acknowledgmentNumber = event.sequenceNumber + 1L,
            flags = TCP_RST or TCP_ACK,
            windowSize = 65535,
            payload = byteArrayOf()
        )
        sendToTun(packet)
    }

    private fun buildNotification(contentText: String): Notification {
        createNotificationChannel()

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("BSML Local VPN")
            .setContentText(contentText)
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
                "BSML Local VPN",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private inner class ProxyState {
        private val sessions = ConcurrentHashMap<SessionKey, TcpProxySession>()

        fun tryStartSession(syn: PacketEvent): Boolean {
            val key = SessionKey.fromEvent(syn)
            val existing = sessions[key]
            if (existing != null && !existing.isClosed()) {
                existing.onDuplicateSyn(syn)
                return true
            }

            if (sessions.values.any { !it.isClosed() }) {
                return false
            }

            sessions[key]?.close()
            sessions[key] = TcpProxySession(
                key = key,
                clientInitialSeq = syn.sequenceNumber,
                clientWindow = syn.windowSize
            ).also { it.start() }
            return true
        }

        fun find(event: PacketEvent): TcpProxySession? {
            return sessions[SessionKey.fromEvent(event)]
        }

        fun remove(key: SessionKey) {
            sessions.remove(key)
        }

        fun close() {
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
    }

    private inner class TcpProxySession(
        private val key: SessionKey,
        clientInitialSeq: Long,
        private val clientWindow: Int
    ) {
        private var remoteSocket: Socket? = null
        private var remoteChannel: SocketChannel? = null
        private val streamParser = SupercellStreamParser(
            onEncryptedStageReached = {},
            localhostAssetBaseUrl = AssetProxyService.PRIMARY_LOCAL_BASE_URL
        )
        private val serverMessageBuffer = ByteQueue()
        private val clientMessageBuffer = ByteQueue()
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

        @Volatile
        private var stopVpnAfterAssetRewrite = false

        fun start() {
            sendSynAck()
            thread(name = "bsml-remote-connect", start = true) {
                try {
                    val channel = SocketChannel.open()
                    val socket = channel.socket()
                    val protectOk = protect(socket)
                    debugLog("Protect socket result=$protectOk")
                    if (!protectOk) {
                        channel.close()
                        throw IOException("protect(socket) returned false")
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
                shouldRewriteContentHash = VpnLogRepository.isContentHashRewriteEnabledNow()
            )
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
        ): ByteArray {
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
                    val oldContentHash = readClientHelloContentHash(body) ?: "<null>"
                    if (shouldRewriteContentHash) {
                        rewriteClientHelloContentHash(body, FORCED_CONTENT_HASH)?.also {
                            changed = true
                            VpnLogRepository.log(
                                "SC CLIENT_HELLO contentHash=$oldContentHash->$FORCED_CONTENT_HASH len=$payloadLength ver=$version"
                            )
                        } ?: body
                    } else {
                        VpnLogRepository.log(
                            "SC CLIENT_HELLO contentHash=$oldContentHash len=$payloadLength ver=$version"
                        )
                        handleFinalOriginalClientHello(oldContentHash)
                        body
                    }
                } else {
                    body
                }

                writer.writeBytes(buildSupercellMessage(messageId, outgoingBody, version))
                offset += fullLength
            }

            if (offset < payload.size) {
                writer.writeBytes(payload.copyOfRange(offset, payload.size))
            }

            return if (changed) writer.toByteArray() else payload
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
            sendToTun(packet)
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
                    this@LocalVpnService.loginFailedSeenCount += 1
                    val attempt = this@LocalVpnService.loginFailedSeenCount
                    val rewriteResult = rewriteLoginFailedBody(body)
                    rewriteResult.body.also { transformed ->
                        VpnLogRepository.log(
                            "SC LOGIN_FAILED shaPatched=${rewriteResult.patchStats.fileShaPatched} " +
                                "rootSha=${rewriteResult.patchStats.rootShaOld ?: rewriteResult.hashes.rootSha ?: "<unknown>"}" +
                                rootShaChangeSuffix(rewriteResult.patchStats) +
                                " len=${loginFailedLengthText(body.size, transformed.size)} ver=$version"
                        )
                        dumpLoginFailedPacket(attempt, version, "original", body)
                        dumpLoginFailedPacket(attempt, version, "patched", transformed)
                    }
                } else {
                    body
                }
                val outgoing = buildSupercellMessage(messageId, transformedBody, version)
                sendPayloadFromServer(outgoing)
                logServerPayload(outgoing)
                stopVpnIfAssetRewriteCompleted()
            }
        }

        private fun rewriteLoginFailedBody(body: ByteArray): LoginFailedRewriteResult {
            val parsed = LoginFailedPrefix.parse(body) ?: return LoginFailedRewriteResult(body, FingerprintHashes(null, null), FingerprintPatchStats(), false)
            val prefixRoundTrip = parsed.encode()
            if (!prefixRoundTrip.contentEquals(body)) {
                VpnLogRepository.log(
                    "SC 20103 structure mismatch stage=prefix original=${body.size} encoded=${prefixRoundTrip.size} " +
                        "diff=${firstDiffIndex(body, prefixRoundTrip)}"
                )
                return LoginFailedRewriteResult(body, FingerprintHashes(null, null), FingerprintPatchStats(), false)
            }

            var assetUrlRewritten = false
            var finalRootSha: String? = null
            var expectedTailUrlCount: Int? = null
            var patchStats = FingerprintPatchStats()
            val shouldPatchFingerprint = VpnLogRepository.isFingerprintRewriteEnabledNow()
            val shouldPatchRootFingerprintSha =
                shouldPatchFingerprint && ROOT_SHA_REWRITE_ENABLED && !this@LocalVpnService.loginFailedRewriteDone
            val rewrittenFingerprint = if (shouldPatchFingerprint) parsed.fingerprint?.takeIf { it.trimStart().startsWith("{") }?.let { fingerprintJson ->
                rewriteFingerprintJson(fingerprintJson, shouldPatchRootFingerprintSha).also { result ->
                    finalRootSha = result.rootSha
                    patchStats += result.patchStats
                }.json
            } else parsed.fingerprint
            val rewrittenTail = LoginFailedTail.parse(parsed.suffix)?.let { tail ->
                val tailRoundTrip = tail.encode()
                if (!tailRoundTrip.contentEquals(parsed.suffix)) {
                    VpnLogRepository.log(
                        "SC 20103 structure mismatch stage=tail suffix=${parsed.suffix.size} encoded=${tailRoundTrip.size} " +
                            "diff=${firstDiffIndex(parsed.suffix, tailRoundTrip)}"
                    )
                    return LoginFailedRewriteResult(body, FingerprintHashes(finalRootSha, null), patchStats, false)
                }

                val rewrittenCompressedFingerprint = if (shouldPatchFingerprint) tail.compressedFingerprint?.let { bytes ->
                    rewriteCompressedFingerprintJson(bytes, shouldPatchRootFingerprintSha).also { result ->
                        finalRootSha = result.rootSha ?: finalRootSha
                        patchStats += result.patchStats
                    }.bytes
                } else tail.compressedFingerprint
                expectedTailUrlCount = tail.contentDownloadUrls.size
                val rewrittenUrls = tail.contentDownloadUrls.map { url ->
                    rewriteAssetUrlIfEnabled(url).also { rewrittenUrl ->
                        if (rewrittenUrl != url) {
                            assetUrlRewritten = true
                        }
                    }
                }
                tail.copy(
                    compressedFingerprint = rewrittenCompressedFingerprint,
                    contentDownloadUrls = rewrittenUrls,
                    preserveRawPrefixWhenCompressedNull = true
                ).encode()
            } ?: parsed.suffix
            val rewrittenContentDownloadUrl = parsed.contentDownloadUrl?.let { url ->
                rewriteAssetUrlIfEnabled(url).also { rewrittenUrl ->
                    if (rewrittenUrl != url) {
                        assetUrlRewritten = true
                    }
                }
            }
            if (assetUrlRewritten && VpnLogRepository.isContentHashRewriteEnabledNow()) {
                VpnLogRepository.setContentHashRewriteEnabled(false)
            } else if (!REWRITE_ASSET_URLS_TO_LOCALHOST && VpnLogRepository.isContentHashRewriteEnabledNow()) {
                VpnLogRepository.setContentHashRewriteEnabled(false)
            }
            val rewritten = parsed.copy(
                fingerprint = rewrittenFingerprint,
                contentDownloadUrl = rewrittenContentDownloadUrl,
                suffix = rewrittenTail
            )
            val rewrittenBody = rewritten.encode()
            val reparsed = LoginFailedPrefix.parse(rewrittenBody)
            if (reparsed == null) {
                VpnLogRepository.log("SC 20103 structure mismatch stage=patched-parse encoded=${rewrittenBody.size}")
                return LoginFailedRewriteResult(body, FingerprintHashes(finalRootSha, null), patchStats, false)
            }
            val patchedRoundTrip = reparsed.encode()
            if (!patchedRoundTrip.contentEquals(rewrittenBody)) {
                VpnLogRepository.log(
                    "SC 20103 structure mismatch stage=patched-roundtrip encoded=${rewrittenBody.size} " +
                        "roundtrip=${patchedRoundTrip.size} diff=${firstDiffIndex(rewrittenBody, patchedRoundTrip)}"
                )
                return LoginFailedRewriteResult(body, FingerprintHashes(finalRootSha, null), patchStats, false)
            }
            val validatedHashes = validatePatchedLoginFailedBody(reparsed, expectedTailUrlCount, shouldPatchFingerprint)
            if (validatedHashes == null) {
                return LoginFailedRewriteResult(body, FingerprintHashes(finalRootSha, null), patchStats, false)
            }
            handleOriginalRootAfterPatch(validatedHashes.rootSha)
            if (shouldPatchRootFingerprintSha) {
                this@LocalVpnService.loginFailedRewriteDone = true
            }
            return LoginFailedRewriteResult(rewrittenBody, validatedHashes, patchStats, true)
        }

        private fun rootShaChangeSuffix(stats: FingerprintPatchStats): String {
            val old = stats.rootShaOld ?: return ""
            val new = stats.rootShaNew ?: return ""
            return "->$new"
        }

        private fun loginFailedLengthText(oldLength: Int, newLength: Int): String {
            return if (oldLength == newLength) oldLength.toString() else "$oldLength->$newLength"
        }

        private fun handleOriginalRootAfterPatch(rootSha: String?) {
            if (
                !this@LocalVpnService.loginFailedRewriteDone ||
                this@LocalVpnService.originalRootAfterPatchSeen ||
                rootSha != ORIGINAL_ASSET_ROOT_SHA
            ) {
                return
            }
            this@LocalVpnService.originalRootAfterPatchSeen = true
        }

        private fun handleFinalOriginalClientHello(contentHash: String) {
            if (
                !this@LocalVpnService.originalRootAfterPatchSeen ||
                this@LocalVpnService.finalOriginalClientHelloHandled ||
                contentHash != ORIGINAL_ASSET_ROOT_SHA
            ) {
                return
            }
            this@LocalVpnService.finalOriginalClientHelloHandled = true
            if (VpnLogRepository.isAutoVpnDisableEnabledNow()) {
                VpnLogRepository.log("SC final CLIENT_HELLO original rootSha; VPN interception disabled")
                thread(name = "bsml-disable-after-original-root", start = true) {
                    Thread.sleep(500)
                    disableVpnTunnelKeepService()
                }
            } else {
                VpnLogRepository.log("SC final CLIENT_HELLO original rootSha; auto VPN disable is off")
            }
        }

        private fun validatePatchedLoginFailedBody(
            parsed: LoginFailedPrefix,
            expectedTailUrlCount: Int?,
            didPatchFingerprint: Boolean
        ): FingerprintHashes? {
            val tail = LoginFailedTail.parse(parsed.suffix)
            if (tail == null) {
                VpnLogRepository.log("SC 20103 structure mismatch stage=patched-tail-parse suffix=${parsed.suffix.size}")
                return null
            }
            val fingerprintHashes = extractFingerprintHashes(parsed.fingerprint, tail.compressedFingerprint)
            if (didPatchFingerprint && parsed.fingerprint != null) {
                VpnLogRepository.log("SC 20103 structure mismatch stage=patched-fingerprint expectedNull=false")
                return null
            }
            if (didPatchFingerprint && tail.compressedFingerprint == null) {
                VpnLogRepository.log("SC 20103 structure mismatch stage=patched-tail compressedFingerprint=null")
                return null
            }
            if (expectedTailUrlCount != null && tail.contentDownloadUrls.size != expectedTailUrlCount) {
                VpnLogRepository.log(
                    "SC 20103 structure mismatch stage=patched-tail urls=$expectedTailUrlCount->${tail.contentDownloadUrls.size}"
                )
                return null
            }
            return fingerprintHashes
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
                val files = root.optJSONArray("files")
                var fileSha: String? = null
                if (files != null) {
                    for (index in 0 until files.length()) {
                        val file = files.optJSONObject(index) ?: continue
                        if (file.optString("file") == PATCHED_ASSET_PATH) {
                            fileSha = file.optString("sha", "").ifEmpty { null }
                            break
                        }
                    }
                }
                FingerprintHashes(rootSha, fileSha)
            } catch (_: Throwable) {
                FingerprintHashes(null, null)
            }
        }

        private fun extractLoginFailedFingerprintHashes(body: ByteArray): FingerprintHashes {
            val parsed = LoginFailedPrefix.parse(body) ?: return FingerprintHashes(null, null)
            val tail = LoginFailedTail.parse(parsed.suffix)
            return extractFingerprintHashes(parsed.fingerprint, tail?.compressedFingerprint)
        }

        private fun rewriteAssetUrlIfEnabled(url: String): String {
            return if (REWRITE_ASSET_URLS_TO_LOCALHOST) rewriteAssetUrl(url) else url
        }

        private fun dumpLoginFailedPacket(
            attempt: Int,
            version: Int,
            label: String,
            body: ByteArray
        ) {
            if (!DUMP_LOGIN_FAILED_PACKETS) return
            try {
                val dir = File(this@LocalVpnService.filesDir, "packet_dumps")
                dir.mkdirs()
                val prefix = "20103_attempt${attempt}_${label}_v$version"
                File(dir, "$prefix.bin").writeBytes(body)
                File(dir, "$prefix.txt").writeText(describeLoginFailedBody(body), Charsets.UTF_8)
            } catch (error: Throwable) {
                VpnLogRepository.log("SC 20103 dump failed ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
            }
        }

        private fun describeLoginFailedBody(body: ByteArray): String {
            val parsed = LoginFailedPrefix.parse(body)
                ?: return "parse=false\nbodyLen=${body.size}\nfirst=${body.take(min(64, body.size)).toByteArray().toHexString()}\n"
            val tail = LoginFailedTail.parse(parsed.suffix)
            val fingerprint = parsed.fingerprint
            val rootSha = fingerprint?.let { runCatching { JSONObject(it).optString("sha", "<none>") }.getOrNull() }
            val fileSha = fingerprint?.let { json ->
                runCatching {
                    val files = JSONObject(json).optJSONArray("files")
                    var sha: String? = null
                    if (files != null) {
                        for (index in 0 until files.length()) {
                            val file = files.optJSONObject(index) ?: continue
                            if (file.optString("file") == PATCHED_ASSET_PATH) {
                                sha = file.optString("sha")
                                break
                            }
                        }
                    }
                    sha
                }.getOrNull()
            }

            return buildString {
                appendLine("parse=true")
                appendLine("bodyLen=${body.size}")
                appendLine("reason=${parsed.reason}")
                appendLine("fingerprintNull=${parsed.fingerprint == null}")
                appendLine("fingerprintLen=${fingerprint?.encodeToByteArray()?.size ?: 0}")
                appendLine("fingerprintFirst=${fingerprint?.take(64) ?: "<null>"}")
                appendLine("fingerprintRootSha=${rootSha ?: "<unknown>"}")
                appendLine("fingerprintFileSha=$PATCHED_ASSET_PATH:${fileSha ?: "<unknown>"}")
                appendLine("unknownString=${parsed.unknownString ?: "<null>"}")
                appendLine("contentDownloadUrl=${parsed.contentDownloadUrl ?: "<null>"}")
                appendLine("updateUrl=${parsed.updateUrl ?: "<null>"}")
                appendLine("reasonTextLen=${parsed.reasonText?.length ?: -1}")
                appendLine("maintenanceWaitSecs=${parsed.maintenanceWaitSecs}")
                appendLine("suffixLen=${parsed.suffix.size}")
                appendLine("tailParse=${tail != null}")
                if (tail != null) {
                    appendLine("tailUnknownBoolean=${tail.unknownBoolean}")
                    appendLine("tailCompressedFingerprintLen=${tail.compressedFingerprint?.size ?: -1}")
                    appendLine("tailCompressedFingerprintFirst=${tail.compressedFingerprint?.take(min(32, tail.compressedFingerprint.size))?.toByteArray()?.toHexString() ?: "<null>"}")
                    appendLine("tailContentDownloadUrlsCount=${tail.contentDownloadUrls.size}")
                    tail.contentDownloadUrls.forEachIndexed { index, url ->
                        appendLine("tailContentDownloadUrl[$index]=$url")
                    }
                    appendLine("tailRawSuffixLen=${tail.rawSuffix.size}")
                    appendLine("tailRawSuffixFirst=${tail.rawSuffix.take(min(64, tail.rawSuffix.size)).toByteArray().toHexString()}")
                    appendLine("tailRoundTrip=${tail.encode().contentEquals(parsed.suffix)}")
                }
                appendLine("roundTrip=${parsed.encode().contentEquals(body)}")
                appendLine("first=${body.take(min(64, body.size)).toByteArray().toHexString()}")
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
                for (index in 0 until files.length()) {
                    val file = files.optJSONObject(index) ?: continue
                    if (file.optString("file") == PATCHED_ASSET_PATH) {
                        val oldSha = file.optString("sha")
                        if (VpnLogRepository.isFileShaRewriteEnabledNow()) {
                            val patched = replaceFileShaPreservingJson(rewrittenJson, PATCHED_ASSET_PATH, oldSha, PATCHED_ASSET_SHA)
                            if (patched != null) {
                                rewrittenJson = patched
                                patchStats = patchStats.copy(fileShaPatched = patchStats.fileShaPatched + 1)
                            } else {
                                VpnLogRepository.log("SC fingerprint file=$PATCHED_ASSET_PATH in-place patch failed oldSha=$oldSha")
                            }
                        } else {
                            VpnLogRepository.log("SC fingerprint file=$PATCHED_ASSET_PATH shaRewrite=false sha=$oldSha")
                        }
                    }
                }
                val trigger = createGeneratedTriggerAsset()
                rewrittenJson = upsertFileShaPreservingJson(
                    json = rewrittenJson,
                    path = GENERATED_TRIGGER_PATH,
                    sha = trigger.sha
                )?.also {
                    patchStats = patchStats.copy(fileShaPatched = patchStats.fileShaPatched + 1)
                } ?: rewrittenJson.also {
                        VpnLogRepository.log("SC fingerprint file=$GENERATED_TRIGGER_PATH upsert failed")
                    }
                var finalRootSha = oldRootSha.ifEmpty { root.optString("sha", "<none>") }
                if (shouldPatchRootFingerprintSha && PATCHED_FINGERPRINT_SHA.isNotEmpty()) {
                    val patchedRootSha = PATCHED_FINGERPRINT_SHA + randomShaSuffix()
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
            val dir = File(this@LocalVpnService.filesDir, GENERATED_ASSET_DIR)
            dir.mkdirs()
            File(dir, GENERATED_TRIGGER_PATH).writeBytes(bytes)
            return GeneratedTriggerAsset(sha)
        }

        private fun sha1Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
            return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
        }

        private fun rewriteCompressedFingerprintJson(
            compressedFingerprint: ByteArray,
            shouldPatchRootFingerprintSha: Boolean
        ): CompressedFingerprintRewriteResult {
            val inflated = inflateFingerprint(compressedFingerprint) ?: return CompressedFingerprintRewriteResult(null, null, FingerprintPatchStats())
            val rewritten = rewriteFingerprintJson(inflated.second, shouldPatchRootFingerprintSha)
            if (rewritten.json == inflated.second) {
                return CompressedFingerprintRewriteResult(compressedFingerprint, rewritten.rootSha, rewritten.patchStats)
            }
            val bytes = deflateFingerprint(rewritten.json, inflated.first)
            return CompressedFingerprintRewriteResult(bytes, rewritten.rootSha, rewritten.patchStats)
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
            val output = ByteArrayOutputStream()
            DeflaterOutputStream(output, Deflater(Deflater.DEFAULT_COMPRESSION, nowrap)).use { deflater ->
                deflater.write(text.encodeToByteArray())
            }
            return output.toByteArray()
        }

        private fun deflatePrefixedZlib(text: String): ByteArray {
            val textBytes = text.encodeToByteArray()
            return writeLittleEndianInt(textBytes.size) + deflateZlib(text, nowrap = false)
        }

        private fun deflateGzip(text: String): ByteArray {
            val output = ByteArrayOutputStream()
            GZIPOutputStream(output).use { gzip ->
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

        private fun stopVpnIfAssetRewriteCompleted() = Unit

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
            proxyState.remove(key)
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

    companion object {
        const val ACTION_START = "lilmuff1.bsml.action.START_VPN"
        const val ACTION_STOP = "lilmuff1.bsml.action.STOP_VPN"
        const val ACTION_STOP_AFTER_ASSET = "lilmuff1.bsml.action.STOP_VPN_AFTER_ASSET"
        const val ACTION_PATCHED_ASSET_SERVED = "lilmuff1.bsml.action.PATCHED_ASSET_SERVED"

        private const val TARGET_HOST = "game.brawlstarsgame.com"
        private const val TARGET_PORT = 9339
        private const val MTU = 32767
        private const val TUN_ADDRESS = "10.10.10.2"
        private const val NOTIFICATION_CHANNEL_ID = "bsml_local_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val SOCKET_CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_READ_TIMEOUT_MS = 15_000
        private const val SOCKET_WRITE_TIMEOUT_MS = 5_000
        private const val DNS_RESOLVE_ATTEMPTS = 5
        private const val DNS_RESOLVE_RETRY_DELAY_MS = 1_000L
        private const val MAX_TCP_PAYLOAD = 1400
        private const val CLIENT_HELLO_ID = 10100
        private const val LOGIN_FAILED_ID = 20103
        private const val FORCED_CONTENT_HASH = "lilmuff1"
        private const val PATCHED_ASSET_PATH = "localization/ru.csv"
        private const val GENERATED_TRIGGER_PATH = "lilmuff1"
        private const val GENERATED_ASSET_DIR = "generated_assets"
        private const val GENERATED_TRIGGER_BYTES = 64
        private const val PATCHED_ASSET_SHA = "2753bb798d337e9157d7e68047d715a96a0e5d3b"
        private const val PATCHED_FINGERPRINT_SHA = "lilmuff1"
        private const val ORIGINAL_ASSET_ROOT_SHA = "39a61268951b6f55ca52a1eb33e3a31ab5e7e9e4"
        private const val ROOT_SHA_REWRITE_ENABLED = true
        private const val REWRITE_ASSET_URLS_TO_LOCALHOST = true
        private const val DUMP_LOGIN_FAILED_PACKETS = true
        private const val RANDOM_SHA_SUFFIX_LENGTH = 8
    }
}

private data class GeneratedTriggerAsset(
    val sha: String
)

private data class LoginFailedRewriteResult(
    val body: ByteArray,
    val hashes: FingerprintHashes,
    val patchStats: FingerprintPatchStats,
    val structureOk: Boolean
)

private data class FingerprintRewriteResult(
    val json: String,
    val rootSha: String?,
    val patchStats: FingerprintPatchStats
)

private data class CompressedFingerprintRewriteResult(
    val bytes: ByteArray?,
    val rootSha: String?,
    val patchStats: FingerprintPatchStats
)

private data class FingerprintPatchStats(
    val fileShaPatched: Int = 0,
    val rootShaOld: String? = null,
    val rootShaNew: String? = null
) {
    operator fun plus(other: FingerprintPatchStats): FingerprintPatchStats {
        return FingerprintPatchStats(
            fileShaPatched = fileShaPatched + other.fileShaPatched,
            rootShaOld = rootShaOld ?: other.rootShaOld,
            rootShaNew = rootShaNew ?: other.rootShaNew
        )
    }
}

private data class FingerprintHashes(
    val rootSha: String?,
    val fileSha: String?
)

private fun ByteArray.takeHex(count: Int): String {
    return take(count).joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
}
