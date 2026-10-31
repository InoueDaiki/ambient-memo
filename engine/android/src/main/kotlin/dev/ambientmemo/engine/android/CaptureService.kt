package dev.ambientmemo.engine.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 聞き取りを動かすマイク型の Foreground Service。
 *
 * [Engine] の状態を見て、止まったら自分で終わる。そのため画面からの停止も通知からの停止も同じ流れになる。
 * OS に再起動を任せない。マイクの Foreground Service はバックグラウンドから開始できず、失敗するため。
 */
class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    private val engine: Engine
        get() = Engine.get(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            start(startId)
        } else {
            scope.launch { engine.stopCapture() }
            watchUntilStopped(startId)
        }
        return START_NOT_STICKY
    }

    private fun start(startId: Int) {
        val notifications = EngineNotifications(this)
        val notification = notifications.capture(stopIntent(this))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    CAPTURE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(CAPTURE_NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) {
            failStart(startId)
            return
        } catch (_: IllegalStateException) {
            failStart(startId)
            return
        }
        notifications.clearReminders()
        engine.runService()
        watchUntilStopped(startId)
    }

    private fun failStart(startId: Int) {
        engine.abandonServiceStart()
        stopSelf(startId)
    }

    private fun watchUntilStopped(startId: Int) {
        watcher?.cancel()
        watcher = scope.launch {
            var wasListening = false
            val stopped = engine.captureState
                .onEach { if (it is CaptureState.Listening) wasListening = true }
                .filterIsInstance<CaptureState.Stopped>()
                .first()
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (stopped.interrupted && wasListening) EngineNotifications(this@CaptureService).showInterrupted()
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        engine.stopCaptureInBackground()
        super.onDestroy()
    }

    internal companion object {
        private const val ACTION_START = "dev.ambientmemo.engine.action.START"
        private const val ACTION_STOP = "dev.ambientmemo.engine.action.STOP"

        fun startIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }
}
