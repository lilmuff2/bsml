package lilmuff1.bsml.service

import lilmuff1.bsml.config.*
import lilmuff1.bsml.protocol.buildSupercellMessage
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
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

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
    private var cleanupNormalHashSeen = false

    private val autoDisableGeneration = AtomicInteger(0)
    @Volatile
    private var installLoginFailedTemplateBody: ByteArray? = null
    @Volatile
    private var installLoginFailedTemplateVersion: Int = 11
    @Volatile
    private var installLoginFailedTemplateClientHash: String? = null
    private val localFirstLoginFailedInjected = AtomicBoolean(false)
    private val localSecondLoginFailedInjected = AtomicBoolean(false)

    private class InstallSessionState {
        @Volatile var originalAssetRootSha: String? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VpnLogRepository.initialize(applicationContext)
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
        AssetRoutingRepository.reset()
        cleanupWarmupSent = false
        cleanupNormalHashSeen = false
        if (cleanupMode) {
            VpnLogRepository.markDeleteCleanupPending()
        }
        installLoginFailedTemplateBody = null
        installLoginFailedTemplateVersion = 11
        installLoginFailedTemplateClientHash = null
        localFirstLoginFailedInjected.set(false)
        localSecondLoginFailedInjected.set(false)
        InstallFlowRepository.reset(appContext)
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
                val configuredTargets = parseList(captureSettings.ipFilterText, GAME_HOST)
                val configuredPackages = parseList(captureSettings.packageText, DEFAULT_CAPTURE_PACKAGES)
                val usePublicAssetHost = captureSettings.autoLaunchPackage == "com.tencent.tmgp.supercell.brawlstars" ||
                    configuredPackages.singleOrNull() == "com.tencent.tmgp.supercell.brawlstars"
                AssetRoutingRepository.setUsePublicAssetHost(usePublicAssetHost)
                val publicAssetAddresses = if (usePublicAssetHost) {
                    resolveTargetAddresses(listOf(PUBLIC_ASSET_HOST))
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
                    publicAssetAddresses.forEach { address ->
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
                    (targetAddresses + publicAssetAddresses).map { ipv4BytesToInt(it.address) }.toSet()
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
        val isAssetProxyTraffic = AssetRoutingRepository.usePublicAssetHostNow() &&
            (event.destinationPort == LOCAL_ASSET_PORT || event.sourcePort == LOCAL_ASSET_PORT)
        if (!isAssetProxyTraffic && event.destinationPort != targetPort && event.sourcePort != targetPort) {
            return
        }

        val flags = event.tcpFlags
        val clientPayload = event.payload()

        if (flags and TCP_SYN != 0 && (event.destinationPort == targetPort || isAssetProxyTraffic)) {
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

            override fun resolveRemoteAddress(serverIp: Int, serverPort: Int): InetSocketAddress {
                return if (AssetRoutingRepository.usePublicAssetHostNow() && serverPort == LOCAL_ASSET_PORT) {
                    InetSocketAddress(LOCAL_ASSET_HOST, LOCAL_ASSET_PORT)
                } else {
                    InetSocketAddress(intToIpv4(serverIp), serverPort)
                }
            }

            override fun shouldRewriteContentHash(): Boolean =
                InstallFlowRepository.isContentHashRewriteEnabled() &&
                    !InstallFlowRepository.wasFinalClientHelloSeen()

            override fun onClientHelloContentHashObserved(contentHash: String) {
                val normalized = contentHash.trim()
                if (normalized.isNotEmpty()) {
                    InstallFlowRepository.setCurrentClientHelloHash(normalized)
                    if (normalized.startsWith(PATCH_NAMESPACE)) {
                        InstallFlowRepository.disableContentHashRewrite()
                    }
                }
            }

            override fun onClientHelloVersionObserved(version: String) {
                VpnLogRepository.setLastClientVersion(appContext, version)
            }

            override fun onFinalOriginalClientHello(contentHash: String) {
                handleFinalOriginalClientHello(contentHash, sessionState)
            }

            override fun rewriteLoginFailedBody(body: ByteArray, version: Int): LoginFailedRewriteResult {
                return this@LocalVpnService.rewriteLoginFailedBody(body, version, sessionState)
            }

            override fun maybeBuildLocalLoginFailedResponse(
                contentHash: String,
                clientHelloLog: String
            ): ByteArray? {
                return this@LocalVpnService.maybeBuildLocalLoginFailedResponse(
                    contentHash = contentHash,
                    clientHelloLog = clientHelloLog,
                    sessionState = sessionState
                )
            }
        }
    }

    private fun rewriteLoginFailedBody(body: ByteArray, version: Int, sessionState: InstallSessionState): LoginFailedRewriteResult {
        if (InstallFlowRepository.wasFinalClientHelloSeen()) {
            return LoginFailedRewriteResult(body, FingerprintHashes(null, null), FingerprintPatchStats())
        }
        val currentClientHash = InstallFlowRepository.getCurrentClientHelloHash()
        if (!cleanupModeEnabled && shouldAbortInstallForPreparedHash(currentClientHash)) {
            InstallFlowRepository.disableContentHashRewrite()
            VpnLogRepository.log(
                "SC install aborted preparedRootSha=${ModFilesRepository.readPreparedRootSha(appContext)} clientHash=${currentClientHash ?: "<null>"}"
            )
            val result = loginFailedRewriter.rewriteCleanup(
                body = body,
                reasonCode = 1,
                reasonName = "LOGIN_FAILED",
                reasonText = getString(R.string.message_refresh_files_before_install),
                forceRewriteFingerprint = false,
                thoroughCleanup = false
            )
            handleLoginFailedRewriteResult(result, sessionState)
            return result
        }
        val currentHashAlreadyPatched = currentClientHash?.startsWith(PATCH_NAMESPACE) == true
        val shouldPatchRootFingerprintSha = ROOT_SHA_REWRITE_ENABLED &&
            !InstallFlowRepository.wasRootShaRewriteApplied() &&
            !currentHashAlreadyPatched
        if (!cleanupModeEnabled && shouldPatchRootFingerprintSha) {
            currentClientHash?.let { hash ->
                storeInstallLoginFailedTemplate(body, version, hash)
            }
            localSecondLoginFailedInjected.set(false)
        }
        val result = if (cleanupModeEnabled) {
            if (!cleanupWarmupSent) {
                cleanupWarmupSent = true
                loginFailedRewriter.rewriteCleanup(
                    body = body,
                    reasonCode = 7,
                    reasonName = "CLIENT_CONTENT_UPDATE",
                    reasonText = "BSML cleanup warmup",
                    thoroughCleanup = VpnLogRepository.isThoroughModDeleteEnabledNow()
                )
            } else {
                val activeReasonCode = if (cleanupNormalHashSeen) cleanupReasonCode else 1
                val activeReasonName = if (cleanupNormalHashSeen) cleanupReasonName else "LOGIN_FAILED"
                loginFailedRewriter.rewriteCleanup(
                    body = body,
                    reasonCode = activeReasonCode,
                    reasonName = activeReasonName,
                    reasonText = getString(R.string.message_restart_game_to_remove_mods),
                    thoroughCleanup = VpnLogRepository.isThoroughModDeleteEnabledNow()
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
        handleLoginFailedRewriteResult(result, sessionState)
        return result
    }

    private fun maybeBuildLocalLoginFailedResponse(
        contentHash: String,
        clientHelloLog: String,
        sessionState: InstallSessionState
    ): ByteArray? {
        if (cleanupModeEnabled) {
            if (InstallFlowRepository.wasFinalClientHelloSeen()) return null
            val rewriteStartedAt = System.nanoTime()
            val isPatchedHash = contentHash.startsWith(PATCH_NAMESPACE)
            val isWarmup = !isPatchedHash
            if (isWarmup && cleanupWarmupSent) return null
            val template = if (isPatchedHash) {
                installLoginFailedTemplateBody ?: return null
            } else {
                val cached = loadInstallLoginFailedTemplate(contentHash) ?: return null
                installLoginFailedTemplateBody = cached.body
                installLoginFailedTemplateVersion = cached.version
                installLoginFailedTemplateClientHash = cached.clientHelloHash
                cached.body
            }
            val version = installLoginFailedTemplateVersion
            val reasonCode = if (isWarmup) 7 else 1
            val reasonName = if (isWarmup) "CLIENT_CONTENT_UPDATE" else "LOGIN_FAILED"
            val reasonText = if (isWarmup) {
                "BSML cleanup warmup"
            } else {
                getString(R.string.message_restart_game_to_remove_mods)
            }
            if (isWarmup) {
                cleanupWarmupSent = true
            }
            VpnLogRepository.log(clientHelloLog)
            val result = loginFailedRewriter.rewriteCleanup(
                body = template,
                reasonCode = reasonCode,
                reasonName = reasonName,
                reasonText = reasonText,
                forceRewriteFingerprint = isWarmup,
                thoroughCleanup = VpnLogRepository.isThoroughModDeleteEnabledNow()
            )
            handleLoginFailedRewriteResult(result, sessionState)
            val body = result.body
            VpnLogRepository.log(
                formatLoginFailedLog(
                    result = result,
                    oldLength = template.size,
                    newLength = body.size,
                    version = version
                ) + " source=local-cleanup-template rewriteMs=${elapsedMs(rewriteStartedAt)}"
            )
            return buildSupercellMessage(LOGIN_FAILED_ID, body, version)
        }
        if (InstallFlowRepository.wasFinalClientHelloSeen()) return null
        val isPatchedHash = contentHash.startsWith(PATCH_NAMESPACE)
        if (!isPatchedHash && shouldAbortInstallForPreparedHash(contentHash)) {
            val cached = loadInstallLoginFailedTemplate(contentHash) ?: return null
            val version = cached.version
            val rewriteStartedAt = System.nanoTime()
            InstallFlowRepository.disableContentHashRewrite()
            VpnLogRepository.log(clientHelloLog)
            VpnLogRepository.log(
                "SC install aborted preparedRootSha=${ModFilesRepository.readPreparedRootSha(appContext)} clientHash=$contentHash"
            )
            val result = loginFailedRewriter.rewriteCleanup(
                body = cached.body,
                reasonCode = 1,
                reasonName = "LOGIN_FAILED",
                reasonText = getString(R.string.message_refresh_files_before_install),
                forceRewriteFingerprint = false,
                thoroughCleanup = false
            )
            handleLoginFailedRewriteResult(result, sessionState)
            val body = result.body
            VpnLogRepository.log(
                formatLoginFailedLog(
                    result = result,
                    oldLength = cached.body.size,
                    newLength = body.size,
                    version = version
                ) + " source=prepared-hash-mismatch rewriteMs=${elapsedMs(rewriteStartedAt)}"
            )
            return buildSupercellMessage(LOGIN_FAILED_ID, body, version)
        }
        val template = if (isPatchedHash) {
            if (!localSecondLoginFailedInjected.compareAndSet(false, true)) return null
            installLoginFailedTemplateBody ?: return null
        } else {
            if (InstallFlowRepository.wasRootShaRewriteApplied()) return null
            val cached = loadInstallLoginFailedTemplate(contentHash) ?: return null
            if (!localFirstLoginFailedInjected.compareAndSet(false, true)) return null
            installLoginFailedTemplateBody = cached.body
            installLoginFailedTemplateVersion = cached.version
            installLoginFailedTemplateClientHash = cached.clientHelloHash
            cached.body
        }
        val version = installLoginFailedTemplateVersion
        val rewriteStartedAt = System.nanoTime()
        VpnLogRepository.log(clientHelloLog)
        val result = loginFailedRewriter.rewrite(
            body = template,
            shouldPatchRootFingerprintSha = !isPatchedHash,
            includeTriggerAsset = isPatchedHash,
            currentClientHelloHash = contentHash
        )
        handleLoginFailedRewriteResult(result, sessionState)
        val body = result.body
        VpnLogRepository.log(
            formatLoginFailedLog(
                result = result,
                oldLength = template.size,
                newLength = body.size,
                version = version
            ) + " source=${if (isPatchedHash) "cache" else "disk-cache"} rewriteMs=${elapsedMs(rewriteStartedAt)}"
        )
        return buildSupercellMessage(LOGIN_FAILED_ID, body, version)
    }

    private fun shouldAbortInstallForPreparedHash(clientHash: String?): Boolean {
        val current = clientHash?.trim()?.ifEmpty { null } ?: return false
        if (current.startsWith(PATCH_NAMESPACE)) return false
        val preparedRootSha = ModFilesRepository.readPreparedRootSha(appContext)?.trim()?.ifEmpty { null } ?: return false
        return preparedRootSha != current
    }

    private fun storeInstallLoginFailedTemplate(body: ByteArray, version: Int, clientHelloHash: String) {
        val normalizedHash = clientHelloHash.trim()
        if (normalizedHash.isEmpty() || normalizedHash.startsWith(PATCH_NAMESPACE)) return
        installLoginFailedTemplateBody = body.copyOf()
        installLoginFailedTemplateVersion = version
        installLoginFailedTemplateClientHash = normalizedHash
        runCatching {
            val dir = installTemplateDir()
            dir.mkdirs()
            File(dir, INSTALL_TEMPLATE_BODY_FILE).writeBytes(body)
            File(dir, INSTALL_TEMPLATE_META_FILE).writeText(
                JSONObject()
                    .put("version", version)
                    .put("clientHelloHash", normalizedHash)
                    .toString()
            )
        }.onFailure { error ->
            VpnLogRepository.log("SC LOGIN_FAILED template save failed ${error::class.java.simpleName}: ${error.message ?: "unknown"}")
        }
    }

    private fun loadInstallLoginFailedTemplate(clientHelloHash: String): InstallLoginFailedTemplate? {
        val normalizedHash = clientHelloHash.trim()
        if (normalizedHash.isEmpty() || normalizedHash.startsWith(PATCH_NAMESPACE)) return null
        val memoryBody = installLoginFailedTemplateBody
        if (memoryBody != null && installLoginFailedTemplateClientHash == normalizedHash) {
            return InstallLoginFailedTemplate(
                body = memoryBody,
                version = installLoginFailedTemplateVersion,
                clientHelloHash = normalizedHash
            )
        }
        return runCatching {
            val dir = installTemplateDir()
            val metaFile = File(dir, INSTALL_TEMPLATE_META_FILE)
            val bodyFile = File(dir, INSTALL_TEMPLATE_BODY_FILE)
            if (!metaFile.isFile || !bodyFile.isFile) return@runCatching null
            val meta = JSONObject(metaFile.readText())
            val storedHash = meta.optString("clientHelloHash", "").trim()
            if (storedHash != normalizedHash) return@runCatching null
            InstallLoginFailedTemplate(
                body = bodyFile.readBytes(),
                version = meta.optInt("version", 11),
                clientHelloHash = storedHash
            )
        }.getOrNull()
    }

    private fun installTemplateDir(): File = File(filesDir, INSTALL_TEMPLATE_DIR)

    private data class InstallLoginFailedTemplate(
        val body: ByteArray,
        val version: Int,
        val clientHelloHash: String
    )

    private fun handleLoginFailedRewriteResult(
        result: LoginFailedRewriteResult,
        sessionState: InstallSessionState
    ) {
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
            InstallFlowRepository.markFinalClientHelloSeen()
            VpnLogRepository.clearDeleteCleanupPending()
            if (VpnLogRepository.isAutoVpnDisableEnabledNow()) {
                VpnLogRepository.log("SC cleanup normal CLIENT_HELLO; VPN interception disabled")
                disableVpnTunnelKeepService()
            } else {
                VpnLogRepository.log("SC cleanup normal CLIENT_HELLO; auto VPN disable is off")
            }
            return
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
        InstallFlowRepository.markFinalClientHelloSeen()
        if (cleanupModeEnabled) {
            VpnLogRepository.clearDeleteCleanupPending()
        } else {
            VpnLogRepository.clearDeleteCleanupPending()
            if (InstallFlowRepository.tryMarkInstallResultNotified()) {
                VpnNotificationFactory.notifyInstallResult(
                    context = this,
                    patchedCount = InstallFlowRepository.servedPatchedCount(appContext),
                    totalCount = ModFilesRepository.listPreparedPaths(appContext).size
                )
                VpnLogRepository.log("SC install result notification sent patched=${InstallFlowRepository.servedPatchedCount(appContext)}")
            }
        }
        if (VpnLogRepository.isAutoVpnDisableEnabledNow()) {
            VpnLogRepository.log("SC final CLIENT_HELLO original rootSha; VPN interception disabled")
            disableVpnTunnelKeepService()
        } else {
            VpnLogRepository.log("SC final CLIENT_HELLO original rootSha; auto VPN disable is off")
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
        private const val INSTALL_TEMPLATE_DIR = "login_failed_install_template"
        private const val INSTALL_TEMPLATE_BODY_FILE = "body.bin"
        private const val INSTALL_TEMPLATE_META_FILE = "meta.json"
        private const val ROOT_SHA_REWRITE_ENABLED = true
    }
}

private fun elapsedMs(startedAtNanos: Long): String {
    return String.format(java.util.Locale.US, "%.3f", (System.nanoTime() - startedAtNanos) / 1_000_000.0)
}
