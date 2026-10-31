package dev.ambientmemo.engine.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

private const val CHANNEL_CAPTURE = "capture"
private const val CHANNEL_REMINDER = "reminder"
private const val ID_REBOOT = 2
private const val ID_INTERRUPTED = 3
private const val REQUEST_OPEN_APP = 0
private const val REQUEST_STOP = 1

internal const val CAPTURE_NOTIFICATION_ID = 1

/** エンジンの通知。POST_NOTIFICATIONS がないと表示されないが、呼んでも失敗はしない。 */
internal class EngineNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** 聞き取り中ずっと出す通知。 */
    fun capture(stopIntent: Intent): Notification {
        val stop = PendingIntent.getService(
            context,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = Notification.Action.Builder(null, context.getString(R.string.capture_stop), stop).build()
        return builder(CHANNEL_CAPTURE)
            .setContentTitle(context.getString(R.string.capture_title))
            .setContentText(context.getString(R.string.capture_text))
            .setContentIntent(openApp())
            .setOngoing(true)
            .addAction(stopAction)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    fun showRebootReminder() {
        show(ID_REBOOT, R.string.reboot_title, R.string.reboot_text)
    }

    fun showInterrupted() {
        show(ID_INTERRUPTED, R.string.interrupted_title, R.string.interrupted_text)
    }

    /** 聞き取りを始めたら、再開を促す通知はいらない。 */
    fun clearReminders() {
        manager.cancel(ID_REBOOT)
        manager.cancel(ID_INTERRUPTED)
    }

    private fun show(id: Int, title: Int, text: Int) {
        val notification = builder(CHANNEL_REMINDER)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    private fun builder(channelId: String): Notification.Builder {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION") // API 26 未満にはチャンネルがない
            return Notification.Builder(context).setSmallIcon(R.drawable.ic_stat_engine)
        }
        createChannels()
        return Notification.Builder(context, channelId).setSmallIcon(R.drawable.ic_stat_engine)
    }

    private fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_CAPTURE,
                    context.getString(R.string.channel_capture),
                    NotificationManager.IMPORTANCE_LOW,
                ),
                NotificationChannel(
                    CHANNEL_REMINDER,
                    context.getString(R.string.channel_reminder),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    private fun openApp(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
