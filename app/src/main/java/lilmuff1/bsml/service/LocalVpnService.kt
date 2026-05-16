package lilmuff1.bsml.service

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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import lilmuff1.bsml.R
import lilmuff1.bsml.logging.VpnLogRepository
import lilmuff1.bsml.ui.MainActivity
import lilmuff1.bsml.vpn.PacketEvent
import lilmuff1.bsml.vpn.PacketParser
import lilmuff1.bsml.vpn.SessionKey
import lilmuff1.bsml.vpn.TCP_ACK
import lilmuff1.bsml.vpn.TCP_FIN
import lilmuff1.bsml.vpn.TCP_RST
import lilmuff1.bsml.vpn.TCP_SYN
import lilmuff1.bsml.vpn.TcpPacketBuilder
import lilmuff1.bsml.vpn.TcpProxySession
import lilmuff1.bsml.vpn.VpnRelayConfig
import lilmuff1.bsml.vpn.debugLog
import lilmuff1.bsml.vpn.ipv4BytesToInt

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopVpn()
                ACTION_STOP_AFTER_ASSET -> stopVpn()
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
        if (active || starting) return

        starting = true
        VpnLogRepository.setStatus("Starting VPN...")

        val notification = buildNotification("TCP proxy is starting")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        starterThread = thread(name = "bsml-vpn-starter", start = true) {
            try {
                val targetAddresses = resolveTargetAddresses()
                if (targetAddresses.isEmpty()) {
                    VpnLogRepository.setStatus("Resolve failed")
                    stopVpn()
                    return@thread
                }

                val builder = Builder()
                    .setSession("BSML Local VPN")
                    .setMtu(VpnRelayConfig.MTU)
                    .setBlocking(true)
                    .addAddress(VpnRelayConfig.TUN_ADDRESS, 32)

                targetAddresses.forEach { address ->
                    builder.addRoute(address.hostAddress ?: return@forEach, 32)
                }

                vpnInterface = builder.establish()
                val descriptor = vpnInterface
                if (descriptor == null) {
                    VpnLogRepository.setStatus("VPN establish failed")
                    stopVpn()
                    return@thread
                }

                tunOutput = FileOutputStream(descriptor.fileDescriptor)
                active = true
                VpnLogRepository.setRunning(true)
                VpnLogRepository.setStatus("Listening on ${VpnRelayConfig.TARGET_HOST}:${VpnRelayConfig.TARGET_PORT}")

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
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun readLoop(targetIpInts: Set<Int>) {
        val descriptor = vpnInterface ?: return

        try {
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val packet = ByteArray(VpnRelayConfig.MTU)
                while (active && !Thread.currentThread().isInterrupted) {
                    val length = input.read(packet)
                    if (length <= 0) continue

                    val event = PacketParser.parse(packet, length, targetIpInts) ?: continue
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
            if (active) stopVpn()
        }
    }

    private fun handleTcpPacket(event: PacketEvent) {
        if (event.destinationPort != VpnRelayConfig.TARGET_PORT && event.sourcePort != VpnRelayConfig.TARGET_PORT) {
            return
        }

        val flags = event.tcpFlags
        val clientPayload = event.payload()

        if (flags and TCP_SYN != 0 && event.destinationPort == VpnRelayConfig.TARGET_PORT) {
            if (!proxyState.tryStartSession(event)) {
                sendStandaloneReset(event)
            }
            return
        }

        val session = proxyState.find(event) ?: return
        if (!session.matches(event)) return

        if (flags and TCP_RST != 0) {
            session.close()
            return
        }

        if (flags and TCP_ACK != 0) {
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
        return try {
            InetAddress.getAllByName(VpnRelayConfig.TARGET_HOST)
                .filterIsInstance<Inet4Address>()
                .distinctBy { it.hostAddress }
        } catch (_: IOException) {
            emptyList()
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
                clientWindow = syn.windowSize,
                protectSocket = { socket: Socket -> protect(socket) },
                sendToTun = ::sendToTun,
                onClosed = { closedKey -> sessions.remove(closedKey) }
            ).also { it.start() }
            return true
        }

        fun find(event: PacketEvent): TcpProxySession? = sessions[SessionKey.fromEvent(event)]

        fun close() {
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
    }

    companion object {
        const val ACTION_START = "lilmuff1.bsml.action.START_VPN"
        const val ACTION_STOP = "lilmuff1.bsml.action.STOP_VPN"
        const val ACTION_STOP_AFTER_ASSET = "lilmuff1.bsml.action.STOP_VPN_AFTER_ASSET"

        private const val NOTIFICATION_CHANNEL_ID = "bsml_local_vpn"
        private const val NOTIFICATION_ID = 1001
    }
}
