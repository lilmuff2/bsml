package lilmuff1.bsml.service

import lilmuff1.bsml.config.*
import lilmuff1.bsml.protocol.debugLog
import lilmuff1.bsml.R
import lilmuff1.bsml.state.InstallFlowRepository
import lilmuff1.bsml.state.LatestFingerprintRepository
import lilmuff1.bsml.state.ModFilesRepository
import lilmuff1.bsml.state.VpnLogRepository
import lilmuff1.bsml.vpn.*

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LocalVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private var starterThread: Thread? = null
    private var tunOutput: FileOutputStream? = null
    private val appContext by lazy { applicationContext }
    private val proxyState = ProxyState()
    private val loginFailedRewriter by lazy {
        LoginFailedRewriter(
            filesDir = filesDir,
            listPatchedAssetPaths = { ModFilesRepository.listPreparedPaths(appContext) },
            getPatchedAssetSha = { path -> ModFilesRepository.getPreparedSha(appContext, path) },
            openPatchedAsset = { path -> ModFilesRepository.openPreparedFile(appContext, path) },
            getCurrentModStateKey = { ModFilesRepository.getPreparedStateSignature(appContext) }
        )
    }

    @Volatile
    private var active = false

    @Volatile
    private var starting = false

    @Volatile
    private var interceptionDisabled = false

    @Volatile
    private var cleanupModeEnabled = false

    @Volatile
    private var cleanupReasonCode = 7

    @Volatile
    private var cleanupReasonName = "CLIENT_CONTENT_UPDATE"

    @Volatile
    private var cleanupWarmupSent = false

    @Volatile
    private var cleanupZeroVersionPending = false

    @Volatile
    private var cleanupNormalHashSeen = false

    private val autoDisableGeneration = AtomicInteger(0)

    private class InstallSessionState {
        @Volatile var originalAssetRootSha: String? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopVpn()
                ACTION_START, null -> startVpn(
                    cleanupMode = intent?.getBooleanExtra(EXTRA_CLEANUP_MODE, false) == true,
                    cleanupReasonCode = intent?.getIntExtra(EXTRA_CLEANUP_REASON_CODE, 7) ?: 7,
                    cleanupReasonName = intent?.getStringExtra(EXTRA_CLEANUP_REASON_NAME) ?: "CLIENT_CONTENT_UPDATE"
                )
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

    private fun startVpn(cleanupMode: Boolean, cleanupReasonCode: Int, cleanupReasonName: String) {
        if (active || starting) {
            debugLog("VPN already running")
            return
        }

        starting = true
        interceptionDisabled = false
        cleanupModeEnabled = cleanupMode
        this.cleanupReasonCode = cleanupReasonCode
        this.cleanupReasonName = cleanupReasonName
        cleanupWarmupSent = false
        cleanupZeroVersionPending = false
        cleanupNormalHashSeen = false
        InstallFlowRepository.reset()
        autoDisableGeneration.incrementAndGet()
        VpnLogRepository.setStatus("Starting VPN...")
        VpnLogRepository.log(
            if (cleanupMode) {
                "VPN start requested; contentHash rewrite enabled cleanupMode=true warmup=7 CLIENT_CONTENT_UPDATE pending=1 LOGIN_FAILED delete=$cleanupReasonCode $cleanupReasonName"
            } else {
                "VPN start requested; contentHash rewrite enabled"
            }
        )
        val captureSettings = VpnLogRepository.captureSettingsNow()

        val notification = VpnNotificationFactory.build(this, getString(R.string.notification_vpn_text))
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
                val targetAddresses = if (captureSettings.filterByIp) {
                    resolveTargetAddresses(parseList(captureSettings.ipFilterText, GAME_HOST))
                } else {
                    emptyList()
                }
                if (captureSettings.filterByIp && targetAddresses.isEmpty()) {
                    VpnLogRepository.log("VPN resolve failed for ${captureSettings.ipFilterText.ifBlank { GAME_HOST }}")
                    VpnLogRepository.setStatus("Resolve failed")
                    stopVpn()
                    return@thread
                }

                val builder = Builder()
                    .setSession("BSML Local VPN")
                    .setMtu(MTU)
                    .setBlocking(true)
                    .addAddress(TUN_ADDRESS, 32)

                val allowedPackages = parseList(captureSettings.packageText, DEFAULT_CAPTURE_PACKAGES)
                applyAllowedApplications(builder, allowedPackages)
                if (captureSettings.filterByIp) {
                    targetAddresses.forEach { address ->
                        builder.addRoute(address.hostAddress ?: return@forEach, 32)
                    }
                } else {
                    builder.addRoute("0.0.0.0", 0)
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
                VpnLogRepository.setStatus("Listening on port ${captureSettings.port}")
                if (captureSettings.filterByIp) {
                    VpnLogRepository.log(
                        "VPN established port=${captureSettings.port} ips=${targetAddresses.joinToString { it.hostAddress ?: "?" }} apps=${allowedPackages.size}"
                    )
                } else {
                    VpnLogRepository.log("VPN established port=${captureSettings.port} ips=all apps=${allowedPackages.size}")
                }

                val targetIpInts = if (captureSettings.filterByIp) {
                    targetAddresses.map { ipv4BytesToInt(it.address) }.toSet()
                } else {
                    null
                }
                readerThread = thread(name = "bsml-vpn-reader", start = true) {
                    readLoop(targetIpInts, captureSettings.port)
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
        autoDisableGeneration.incrementAndGet()

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
        autoDisableGeneration.incrementAndGet()
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

    private fun readLoop(targetIpInts: Set<Int>?, targetPort: Int) {
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
                        handleTcpPacket(event, targetPort)
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

    private fun handleTcpPacket(event: PacketEvent, targetPort: Int) {
        if (event.destinationPort != targetPort && event.sourcePort != targetPort) {
            return
        }

        val flags = event.tcpFlags
        val clientPayload = event.payload()

        if (flags and TCP_SYN != 0 && event.destinationPort == targetPort) {
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

    private fun resolveTargetAddresses(targetEntries: List<String>): List<Inet4Address> {
        var lastError: Throwable? = null
        val addresses = LinkedHashMap<String, Inet4Address>()
        repeat(DNS_RESOLVE_ATTEMPTS) { index ->
            val attempt = index + 1
            try {
                targetEntries.forEach { target ->
                    InetAddress.getAllByName(target)
                        .filterIsInstance<Inet4Address>()
                        .forEach { address ->
                            addresses[address.hostAddress ?: address.hostAddress] = address
                        }
                }
                if (addresses.isNotEmpty()) {
                    VpnLogRepository.log(
                        "VPN resolved attempt=$attempt ips=${addresses.values.joinToString { it.hostAddress ?: "?" }}"
                    )
                    return addresses.values.toList()
                }
                VpnLogRepository.log("VPN resolve empty attempt=$attempt")
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

    private fun parseList(text: String, fallback: String): List<String> {
        val entries = text
            .split(',', '\n', '\r', '\t', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return entries.ifEmpty { listOf(fallback) }
    }

    private fun applyAllowedApplications(builder: Builder, packages: List<String>) {
        packages.forEach { packageName ->
            try {
                builder.addAllowedApplication(packageName)
            } catch (error: PackageManager.NameNotFoundException) {
                VpnLogRepository.log("VPN app filter failed package=$packageName")
            }
        }
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

    private fun createSessionCallbacks(sessionState: InstallSessionState): TcpProxySessionCallbacks {
        return object : TcpProxySessionCallbacks {
            override fun protectSocket(socket: Socket): Boolean = protect(socket)

            override fun sendToTun(packet: ByteArray) {
                this@LocalVpnService.sendToTun(packet)
            }

            override fun removeSession(key: SessionKey) {
                proxyState.remove(key)
            }

            override fun shouldRewriteContentHash(): Boolean = InstallFlowRepository.isContentHashRewriteEnabled()

            override fun onClientHelloContentHashObserved(contentHash: String) {
                val normalized = contentHash.trim()
                if (normalized.isNotEmpty()) {
                    InstallFlowRepository.setCurrentClientHelloHash(normalized)
                    if (normalized.startsWith(PATCH_NAMESPACE)) {
                        InstallFlowRepository.markPatchedClientHelloSeen()
                        InstallFlowRepository.disableContentHashRewrite()
                    }
                }
            }

            override fun shouldZeroClientHelloGameVersion(): Boolean = cleanupModeEnabled && cleanupZeroVersionPending

            override fun onClientHelloGameVersionZeroApplied() {
                cleanupZeroVersionPending = false
            }

            override fun onFinalOriginalClientHello(contentHash: String) {
                handleFinalOriginalClientHello(contentHash, sessionState)
            }

            override fun rewriteLoginFailedBody(body: ByteArray): LoginFailedRewriteResult {
                return this@LocalVpnService.rewriteLoginFailedBody(body, sessionState)
            }
        }
    }

    private fun rewriteLoginFailedBody(body: ByteArray, sessionState: InstallSessionState): LoginFailedRewriteResult {
        val currentClientHash = InstallFlowRepository.getCurrentClientHelloHash()
        val currentHashAlreadyPatched = currentClientHash?.startsWith(PATCH_NAMESPACE) == true
        val shouldPatchRootFingerprintSha = ROOT_SHA_REWRITE_ENABLED &&
            !InstallFlowRepository.wasRootShaRewriteApplied() &&
            !currentHashAlreadyPatched
        val result = if (cleanupModeEnabled) {
            if (!cleanupWarmupSent) {
                cleanupWarmupSent = true
                cleanupZeroVersionPending = true
                loginFailedRewriter.rewriteCleanup(
                    body = body,
                    reasonCode = 7,
                    reasonName = "CLIENT_CONTENT_UPDATE",
                    reasonText = "BSML cleanup warmup"
                )
            } else {
                val activeReasonCode = if (cleanupNormalHashSeen) cleanupReasonCode else 1
                val activeReasonName = if (cleanupNormalHashSeen) cleanupReasonName else "LOGIN_FAILED"
                loginFailedRewriter.rewriteCleanup(
                    body = body,
                    reasonCode = activeReasonCode,
                    reasonName = activeReasonName,
                    reasonText = getString(R.string.message_restart_game_to_remove_mods)
                )
            }
        } else {
            val patchedAssetServed = InstallFlowRepository.wasPatchedModAssetServed()
            val includeTriggerAsset = !shouldPatchRootFingerprintSha && (
                patchedAssetServed ||
                    currentHashAlreadyPatched
                )
            loginFailedRewriter.rewrite(
                body = body,
                shouldPatchRootFingerprintSha = shouldPatchRootFingerprintSha,
                includeTriggerAsset = includeTriggerAsset,
                currentClientHelloHash = currentClientHash
            )
        }
        if (result.assetUrlRewritten) {
            InstallFlowRepository.disableContentHashRewrite()
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
            sessionState.originalAssetRootSha = result.originalRootSha
            InstallFlowRepository.setOriginalRootSha(result.originalRootSha)
        }
        handleOriginalRootAfterPatch(result.hashes.rootSha, sessionState)
        if (result.rootShaRewriteApplied) {
            InstallFlowRepository.markRootShaRewriteApplied()
        }
        return result
    }

    private fun handleOriginalRootAfterPatch(rootSha: String?, sessionState: InstallSessionState) {
        val originalRootSha = sessionState.originalAssetRootSha
            ?: InstallFlowRepository.getOriginalRootSha()
            ?: return
        if (
            !InstallFlowRepository.wasRootShaRewriteApplied() ||
            InstallFlowRepository.wasOriginalRootAfterPatchSeen() ||
            rootSha != originalRootSha
        ) {
            return
        }
        InstallFlowRepository.markOriginalRootAfterPatchSeen()
    }

    private fun handleFinalOriginalClientHello(contentHash: String, sessionState: InstallSessionState) {
        if (cleanupModeEnabled && !cleanupNormalHashSeen && !contentHash.startsWith(PATCH_NAMESPACE)) {
            cleanupNormalHashSeen = true
            VpnLogRepository.log("SC cleanup normal CLIENT_HELLO restored contentHash=$contentHash")
        }
        val originalRootSha = sessionState.originalAssetRootSha
            ?: InstallFlowRepository.getOriginalRootSha()
            ?: return
        if (
            !InstallFlowRepository.wasOriginalRootAfterPatchSeen() &&
            InstallFlowRepository.wasRootShaRewriteApplied() &&
            contentHash == originalRootSha
        ) {
            InstallFlowRepository.markOriginalRootAfterPatchSeen()
        }
        if (
            !InstallFlowRepository.wasOriginalRootAfterPatchSeen() ||
            contentHash != originalRootSha
        ) {
            return
        }
        VpnLogRepository.log("SC final CLIENT_HELLO detected contentHash=$contentHash")
        if (!cleanupModeEnabled) {
            if (InstallFlowRepository.tryMarkInstallResultNotified()) {
                VpnNotificationFactory.notifyInstallResult(
                    context = this,
                    patchedCount = InstallFlowRepository.servedPatchedCount(),
                    totalCount = ModFilesRepository.listPreparedPaths(appContext).size
                )
                VpnLogRepository.log("SC install result notification sent patched=${InstallFlowRepository.servedPatchedCount()}")
            }
        }
        if (VpnLogRepository.isAutoVpnDisableEnabledNow()) {
            scheduleAutoDisableAfterQuietTraffic()
        } else {
            VpnLogRepository.log("SC final CLIENT_HELLO original rootSha; auto VPN disable is off")
        }
    }

    private fun scheduleAutoDisableAfterQuietTraffic() {
        val generation = autoDisableGeneration.incrementAndGet()
        thread(name = "bsml-disable-after-quiet-traffic", start = true) {
            var observedTrafficVersion = VpnLogRepository.significantTrafficVersionNow()
            while (
                active &&
                !Thread.currentThread().isInterrupted &&
                autoDisableGeneration.get() == generation
            ) {
                try {
                    Thread.sleep(AUTO_DISABLE_QUIET_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }

                val currentTrafficVersion = VpnLogRepository.significantTrafficVersionNow()
                if (currentTrafficVersion == observedTrafficVersion) {
                    VpnLogRepository.log("SC final CLIENT_HELLO original rootSha; VPN interception disabled")
                    disableVpnTunnelKeepService()
                    return@thread
                }
                observedTrafficVersion = currentTrafficVersion
            }
        }
    }

    private inner class ProxyState {
        private val sessions = ConcurrentHashMap<SessionKey, TcpProxySession>()

        fun tryStartSession(syn: PacketEvent): Boolean {
            val key = SessionKey.fromEvent(syn)
            LatestFingerprintRepository.storeObservedGameServer(appContext, intToIpv4(key.serverIp))
            val existing = sessions[key]
            if (existing != null && !existing.isClosed()) {
                existing.onDuplicateSyn(syn)
                return true
            }

            sessions[key]?.close()
            val sessionState = InstallSessionState()
            sessions[key] = TcpProxySession(
                key = key,
                clientInitialSeq = syn.sequenceNumber,
                clientWindow = syn.windowSize,
                callbacks = createSessionCallbacks(sessionState)
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
        const val EXTRA_CLEANUP_MODE = "lilmuff1.bsml.extra.CLEANUP_MODE"
        const val EXTRA_CLEANUP_REASON_CODE = "lilmuff1.bsml.extra.CLEANUP_REASON_CODE"
        const val EXTRA_CLEANUP_REASON_NAME = "lilmuff1.bsml.extra.CLEANUP_REASON_NAME"

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
        private const val AUTO_DISABLE_QUIET_MS = 5_000L
    }
}
