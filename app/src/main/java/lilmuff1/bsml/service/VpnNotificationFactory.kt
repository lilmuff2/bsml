package lilmuff1.bsml.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import lilmuff1.bsml.R
import lilmuff1.bsml.state.VpnLogRepository
import lilmuff1.bsml.ui.MainActivity

object VpnNotificationFactory {
    private const val CHANNEL_ID = "bsml_local_vpn"
    private const val RESULT_CHANNEL_ID = "bsml_results"
    private const val INSTALL_RESULT_NOTIFICATION_ID = 2001

    fun build(context: Context, contentText: String): Notification {
        createChannel(context)

        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    fun notifyInstallResult(context: Context, patchedCount: Int, totalCount: Int) {
        if (!VpnLogRepository.isInstallResultNotificationsEnabledNow()) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createResultChannel(context)

        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val alreadyApplied = patchedCount == 0
        val contentText = if (alreadyApplied) {
            context.getString(R.string.notification_install_result_already_applied_text)
        } else {
            context.getString(
                R.string.notification_install_result_text,
                patchedCount,
                totalCount
            )
        }
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(
                context.getString(
                    if (alreadyApplied) {
                        R.string.notification_install_result_already_applied_title
                    } else {
                        R.string.notification_install_result_title
                    }
                )
            )
            .setContentText(contentText)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (alreadyApplied) {
                        context.getString(R.string.notification_install_result_already_applied_text)
                    } else {
                        context.getString(
                            R.string.notification_install_result_big_text,
                            patchedCount,
                            totalCount
                        )
                    }
                )
            )
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(INSTALL_RESULT_NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun createResultChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                RESULT_CHANNEL_ID,
                context.getString(R.string.notification_install_result_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }
}
