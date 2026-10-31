package dev.ambientmemo.engine.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.ambientmemo.engine.domain.ModelDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 通信が止まったままでも、取得が電池を使い続けないようにする上限。 */
private const val WAKE_LOCK_TIMEOUT_MILLIS = 6L * 60 * 60 * 1000

/**
 * モデルを取得する dataSync 型の Foreground Service。利用者の操作でだけ開始する。
 *
 * 取得は長く、アプリを離れても続けたいので Service にする。聞き取りの [CaptureService] とは別の Service で、
 * 型も通知も別にする。どちらかを止めても、もう一方は続く。
 *
 * OS に再起動を任せない。利用者の操作なしに通信を始めないため。プロセスが止まったら、次は画面の操作で続きから読む。
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val downloads: ModelDownloads
        get() = Engine.get(this).modelDownloads

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            start(startId)
        } else {
            downloads.cancel()
            if (watcher == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun start(startId: Int) {
        val notifications = ModelDownloadNotifications(this)
        val notification = notifications.progress(null, stopIntent(this))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    DOWNLOAD_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(DOWNLOAD_NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) {
            failStart(startId)
            return
        } catch (_: IllegalStateException) {
            failStart(startId)
            return
        }
        acquireWakeLock()
        downloads.run()
        watchUntilFinished(startId, notifications)
    }

    private fun failStart(startId: Int) {
        downloads.abandonStart()
        stopSelf(startId)
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ambient-memo:model-download").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }

    private fun watchUntilFinished(startId: Int, notifications: ModelDownloadNotifications) {
        watcher?.cancel()
        watcher = scope.launch {
            val finished = downloads.state.first { state ->
                if (state is ModelDownloadState.Running) {
                    notifications.update(state.progress, stopIntent(this@ModelDownloadService))
                }
                state !is ModelDownloadState.Running
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            when {
                finished is ModelDownloadState.Failed -> notifications.showFailed()
                downloads.plan(downloads.activeGroup).isComplete -> notifications.showFinished()
            }
            stopSelf(startId)
        }
    }

    /** dataSync の Service は、API 35 以降 1 日 6 時間を超えると OS に止められる。途中までは残るので続きから読める。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        downloads.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        downloads.cancel()
        super.onDestroy()
    }

    internal companion object {
        private const val ACTION_START = "dev.ambientmemo.engine.action.START_MODEL_DOWNLOAD"
        private const val ACTION_STOP = "dev.ambientmemo.engine.action.STOP_MODEL_DOWNLOAD"

        fun startIntent(context: Context): Intent =
            Intent(context, ModelDownloadService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, ModelDownloadService::class.java).setAction(ACTION_STOP)
    }
}
