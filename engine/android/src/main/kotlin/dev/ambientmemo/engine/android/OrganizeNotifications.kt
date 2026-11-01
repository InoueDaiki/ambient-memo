package dev.ambientmemo.engine.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context

private const val REQUEST_STOP = 3
private const val ID_RESULT = 7

internal const val ORGANIZE_NOTIFICATION_ID = 6

/** 手動の整理の通知。文字起こしの中身は出さない。 */
internal class OrganizeNotifications(private val context: Context) {
    private val base = EngineNotifications(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** 実行の間ずっと出す通知。止める操作は Service へ届く。 */
    fun progress(status: OrganizeStatus): Notification {
        val stop = PendingIntent.getService(
            context,
            REQUEST_STOP,
            OrganizeService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = Notification.Action.Builder(null, context.getString(R.string.organize_stop), stop).build()
        return base.builder(CHANNEL_ORGANIZE)
            .setContentTitle(context.getString(R.string.organize_title))
            .setContentText(text(status))
            .setContentIntent(base.openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(status.totalChunks, status.completedChunks, status.totalChunks == 0)
            .apply { if (status.phase != OrganizePhase.STOPPING) addAction(stopAction) }
            .build()
    }

    fun update(status: OrganizeStatus) {
        manager.notify(ORGANIZE_NOTIFICATION_ID, progress(status))
    }

    /** 利用者が止めたときとモデルがないときは、画面で分かるので出さない。 */
    fun showEnding(ending: OrganizeEnding?) {
        when (ending) {
            OrganizeEnding.COMPLETED ->
                base.show(ID_RESULT, R.string.organize_completed_title, R.string.organize_completed_text)
            OrganizeEnding.TOO_HOT ->
                base.show(ID_RESULT, R.string.organize_stopped_title, R.string.organize_too_hot_text)
            OrganizeEnding.FAILED ->
                base.show(ID_RESULT, R.string.organize_failed_title, R.string.organize_failed_text)
            OrganizeEnding.TIME_LIMIT ->
                base.show(ID_RESULT, R.string.organize_stopped_title, R.string.organize_time_limit_text)
            OrganizeEnding.STOPPED, OrganizeEnding.MODEL_MISSING, null -> Unit
        }
    }

    private fun text(status: OrganizeStatus): String = when {
        status.phase == OrganizePhase.STOPPING -> context.getString(R.string.organize_stopping)
        status.totalChunks == 0 -> context.getString(R.string.organize_preparing)
        else -> context.getString(R.string.organize_progress, status.completedChunks, status.totalChunks)
    }
}
