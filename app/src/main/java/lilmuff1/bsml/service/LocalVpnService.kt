package lilmuff1.bsml.service

import lilmuff1.bsml.config.*
import lilmuff1.bsml.protocol.debugLog
import lilmuff1.bsml.state.VpnLogRepository
import lilmuff1.bsml.vpn.*

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class LocalVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var starterThread: Thread? = null
    private var tunOutput: FileOutputStream? = null
    private val proxyState = ProxyState()
    private val loginFailedRewriter by lazy {
        LoginFailedRewriter(filesDir) { path ->
            runCatching {
                assets.open("patches/$path").use { input -> input.readBytes() }
            }.getOrNull()
        }
    }

    @Volatile
    private var active = false

    @Volatile
    private var starting = false

    @Volatile
    private var interceptionDisabled = false

    @Volatile
    private var loginFailedRewriteDone = false

    @Volatile
    private var originalRootAfterPatchSeen = false

    @Volatile
    private var finalOriginalClientHelloHandled = false

    @Volatile
    private var contentHashRewriteEnabled = false

    @Volatile
    private var originalAssetRootSha: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopVpn()
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
        originalRootAfterPatchSeen = false
        finalOriginalClientHelloHandled = false
        contentHashRewriteEnabled = true
        originalAssetRootSha = null
        VpnLogRepository.setStatus("Starting VPN...")
        VpnLogRepository.log("VPN start requested; contentHash rewrite enabled")

        val notification = VpnNotificationFactory.build(this, "TCP proxy is starting")
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
                    VpnLogRepository.log("VPN resolve failed for $GAME_HOST")
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
                VpnLogRepository.setStatus("Listening on $GAME_HOST:$GAME_PORT")
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
        if (event.destinationPort != GAME_PORT && event.sourcePort != GAME_PORT) {
            return
        }

        val flags = event.tcpFlags
        val clientPayload = event.payload()

        if (flags and TCP_SYN != 0 && event.destinationPort == GAME_PORT) {
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
                val addresses = InetAddress.getAllByName(GAME_HOST)
                    .filterIsInstance<Inet4Address>()
                    .distinctBy { it.hostAddress }
                if (addresses.isNotEmpty()) {
                    VpnLogRepository.log(
                        "VPN resolved $GAME_HOST attempt=$attempt ips=${addresses.joinToString { it.hostAddress ?: "?" }}"
                    )
                    return addresses
                }
                VpnLogRepository.log("VPN resolve empty $GAME_HOST attempt=$attempt")
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

    private fun createSessionCallbacks(): TcpProxySessionCallbacks {
        return object : TcpProxySessionCallbacks {
            override fun protectSocket(socket: Socket): Boolean = protect(socket)

            override fun sendToTun(packet: ByteArray) {
                this@LocalVpnService.sendToTun(packet)
            }

            override fun removeSession(key: SessionKey) {
                proxyState.remove(key)
            }

            override fun shouldRewriteContentHash(): Boolean = contentHashRewriteEnabled

            override fun onFinalOriginalClientHello(contentHash: String) {
                handleFinalOriginalClientHello(contentHash)
            }

            override fun rewriteLoginFailedBody(body: ByteArray): LoginFailedRewriteResult {
                return this@LocalVpnService.rewriteLoginFailedBody(body)
            }
        }
    }

    private fun rewriteLoginFailedBody(body: ByteArray): LoginFailedRewriteResult {
        val shouldPatchRootFingerprintSha = ROOT_SHA_REWRITE_ENABLED && !loginFailedRewriteDone
        val result = loginFailedRewriter.rewrite(body, shouldPatchRootFingerprintSha)
        if (result.assetUrlRewritten && contentHashRewriteEnabled) {
            contentHashRewriteEnabled = false
        }
        if (result.assetOrigins.isNotEmpty()) {
            startService(
                Intent(this, AssetProxyService::class.java)
                    .setAction(AssetProxyService.ACTION_UPDATE_ORIGINS)
                    .putExtra(AssetProxyService.EXTRA_ORIGINAL_ROOT_SHA, result.originalRootSha)
                    .putStringArrayListExtra(
                        AssetProxyService.EXTRA_ORIGINS,
                        ArrayList(result.assetOrigins)
                    )
            )
        }
        if (result.originalRootSha != null) {
            originalAssetRootSha = result.originalRootSha
        }
        handleOriginalRootAfterPatch(result.hashes.rootSha)
        if (result.rootShaRewriteApplied) {
            loginFailedRewriteDone = true
        }
        return result
    }

    private fun handleOriginalRootAfterPatch(rootSha: String?) {
        val originalRootSha = originalAssetRootSha ?: return
        if (
            !loginFailedRewriteDone ||
            originalRootAfterPatchSeen ||
            rootSha != originalRootSha
        ) {
            return
        }
        originalRootAfterPatchSeen = true
    }

    private fun handleFinalOriginalClientHello(contentHash: String) {
        val originalRootSha = originalAssetRootSha ?: return
        if (
            !originalRootAfterPatchSeen ||
            finalOriginalClientHelloHandled ||
            contentHash != originalRootSha
        ) {
            return
        }
        finalOriginalClientHelloHandled = true
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
                clientWindow = syn.windowSize,
                callbacks = createSessionCallbacks()
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

    companion object {
        const val ACTION_START = "lilmuff1.bsml.action.START_VPN"
        const val ACTION_STOP = "lilmuff1.bsml.action.STOP_VPN"

        private const val MTU = 32767
        private const val TUN_ADDRESS = "10.10.10.2"
        private const val NOTIFICATION_ID = 1001
        private const val SOCKET_CONNECT_TIMEOUT_MS = 10_000
        private const val SOCKET_READ_TIMEOUT_MS = 15_000
        private const val SOCKET_WRITE_TIMEOUT_MS = 5_000
        private const val DNS_RESOLVE_ATTEMPTS = 5
        private const val DNS_RESOLVE_RETRY_DELAY_MS = 1_000L
        private const val MAX_TCP_PAYLOAD = 1400
        private const val CLIENT_HELLO_ID = 10100
        private const val LOGIN_FAILED_ID = 0x4E87
        private const val ROOT_SHA_REWRITE_ENABLED = true
    }
}
