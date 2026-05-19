package lilmuff1.bsml.service

import lilmuff1.bsml.R
import lilmuff1.bsml.state.InstallFlowRepository
import lilmuff1.bsml.state.ModFilesRepository
import lilmuff1.bsml.state.VpnLogRepository
import lilmuff1.bsml.ui.MainActivity

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
import java.io.File
import lilmuff1.bsml.asset.LocalAssetProxyServer
import lilmuff1.bsml.asset.PatchedAsset
import lilmuff1.bsml.config.GENERATED_ASSET_DIR

class AssetProxyService : Service() {
    private val assetProxyServer = LocalAssetProxyServer(
        onLog = { message -> VpnLogRepository.log(message) },
        openPatchedAsset = { path ->
            val generated = File(filesDir, "$GENERATED_ASSET_DIR/$path")
            if (generated.isFile) {
                PatchedAsset(
                    length = generated.length(),
                    openStream = { generated.inputStream() }
                )
            } else {
                ModFilesRepository.findPreparedFile(applicationContext, path)?.let { entry ->
                    PatchedAsset(
                        length = entry.size,
                        openStream = {
                            applicationContext.contentResolver.openInputStream(android.net.Uri.parse(entry.uri))
                                ?: error("prepared asset open failed: ${entry.path}")
                        }
                    )
                }
            }
        },
        onFirstAssetRequest = {
        },
        onPatchedAssetServed = { path ->
            InstallFlowRepository.onPatchedAssetServed(applicationContext, path)
        }
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VpnLogRepository.initialize(applicationContext)
        when (intent?.action) {
            ACTION_STOP -> stopProxy()
            ACTION_UPDATE_ORIGINS -> {
                val origins = intent.getStringArrayListExtra(EXTRA_ORIGINS).orEmpty()
                val originalRootSha = intent.getStringExtra(EXTRA_ORIGINAL_ROOT_SHA)
                assetProxyServer.setRouting(origins, originalRootSha)
            }
            ACTION_START, null -> startProxy()
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
            .setContentTitle(getString(R.string.notification_asset_proxy_title))
            .setContentText(getString(R.string.notification_asset_proxy_text))
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
        const val ACTION_UPDATE_ORIGINS = "lilmuff1.bsml.action.UPDATE_ASSET_ORIGINS"
        const val EXTRA_ORIGINS = "lilmuff1.bsml.extra.ASSET_ORIGINS"
        const val EXTRA_ORIGINAL_ROOT_SHA = "lilmuff1.bsml.extra.ORIGINAL_ROOT_SHA"
        private const val NOTIFICATION_CHANNEL_ID = "bsml_asset_proxy"
        private const val NOTIFICATION_ID = 1002
    }
}
