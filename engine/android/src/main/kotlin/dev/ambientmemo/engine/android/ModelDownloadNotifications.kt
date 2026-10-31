package dev.ambientmemo.engine.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.ambientmemo.engine.domain.ModelDownloadPhase
import dev.ambientmemo.engine.domain.ModelDownloadProgress

private const val REQUEST_STOP = 2
private const val ID_RESULT = 5
private const val PERCENT = 100

internal const val DOWNLOAD_NOTIFICATION_ID = 4

/** モデルの取得の通知。聞き取りの通知とは別に出す。 */
internal class ModelDownloadNotifications(private val context: Context) {
    private val base = EngineNotifications(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** 取得の間ずっと出す通知。[progress] が null なら、進み具合をまだ出さない。 */
    fun progress(progress: ModelDownloadProgress?, stopIntent: Intent): Notification {
        val stop = PendingIntent.getService(
            context,
            REQUEST_STOP,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = Notification.Action.Builder(null, context.getString(R.string.download_stop), stop).build()
        val percent = progress?.takeIf { it.totalBytes > 0 }
            ?.let { (it.completedBytes * PERCENT / it.totalBytes).toInt() }
        return base.builder(CHANNEL_MODEL_DOWNLOAD)
            .setContentTitle(context.getString(R.string.download_title))
            .setContentText(context.getString(phaseText(progress?.phase)))
            .setContentIntent(base.openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(PERCENT, percent ?: 0, percent == null)
            .addAction(stopAction)
            .build()
    }

    fun update(progress: ModelDownloadProgress, stopIntent: Intent) {
        manager.notify(DOWNLOAD_NOTIFICATION_ID, progress(progress, stopIntent))
    }

    fun showFinished() = base.show(ID_RESULT, R.string.download_finished_title, R.string.download_finished_text)

    fun showFailed() = base.show(ID_RESULT, R.string.download_failed_title, R.string.download_failed_text)

    private fun phaseText(phase: ModelDownloadPhase?): Int = when (phase) {
        null, ModelDownloadPhase.DOWNLOADING -> R.string.download_downloading
        ModelDownloadPhase.VERIFYING -> R.string.download_verifying
        ModelDownloadPhase.EXTRACTING -> R.string.download_extracting
    }
}
