package dev.ambientmemo.engine.android

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AmbientMemoEngine"
private const val KEY_PHASE = "phase"
private const val KEY_COMPLETED = "completed"
private const val KEY_TOTAL = "total"
private const val KEY_MANUAL = "manual"
private const val KEY_ENDING = "ending"
private const val NO_ENDING = -1

/** `:organize` が起動して返事をするまでに待つ時間。 */
private const val START_TIMEOUT_MILLIS = 30_000L

internal fun OrganizeStatus.toBundle() = Bundle().apply {
    putInt(KEY_PHASE, phase.ordinal)
    putInt(KEY_COMPLETED, completedChunks)
    putInt(KEY_TOTAL, totalChunks)
    putBoolean(KEY_MANUAL, isManual)
    putInt(KEY_ENDING, ending?.ordinal ?: NO_ENDING)
}

internal fun Bundle.toOrganizeStatus() = OrganizeStatus(
    phase = OrganizePhase.entries[getInt(KEY_PHASE)],
    completedChunks = getInt(KEY_COMPLETED),
    totalChunks = getInt(KEY_TOTAL),
    isManual = getBoolean(KEY_MANUAL),
    ending = OrganizeEnding.entries.getOrNull(getInt(KEY_ENDING)),
)

/** JS の境界が使う、手動の整理の操作。 */
internal interface OrganizeControl {
    val status: OrganizeStatus

    fun isModelReady(): Boolean

    /** 開始を頼む。頼めなければ false。 */
    fun start(): Boolean

    fun stop()
}

/**
 * メインのプロセスで整理を見守る。LLM は持たない。
 *
 * `BIND_AUTO_CREATE` なしで bind したままにする。`:organize` が動き出すと繋がり、定期実行も手動の実行も同じように見える。
 * アプリを開き直しても、動いている実行に繋がる。`BIND_WAIVE_PRIORITY` で、聞き取りのプロセスの優先度を持ち込まない。
 *
 * プロセスが落ちると接続が切れるだけで、このプロセスには何も起きない。
 */
@Suppress("TooManyFunctions") // ServiceConnection の入口と Messenger の受け口を1か所で読めるようにする
internal class OrganizeMonitor(
    context: Context,
    private val modelReady: () -> Boolean,
    private val onMemoChanged: (MemoChange) -> Unit,
) : OrganizeControl, ServiceConnection {
    private val context = context.applicationContext
    private val tracker = OrganizeStatusTracker()
    private val states = MutableStateFlow(tracker.status)
    private val main = Handler(Looper.getMainLooper())
    private val client = Messenger(Handler(Looper.getMainLooper(), ::handle))
    private val startTimeout = Runnable {
        if (tracker.onStartTimedOut()) lost()
    }

    @Volatile
    private var service: Messenger? = null

    val statuses: StateFlow<OrganizeStatus> = states.asStateFlow()

    override val status: OrganizeStatus get() = tracker.status

    init {
        bind()
    }

    private fun bind() {
        context.bindService(OrganizeService.bindIntent(context), this, Context.BIND_WAIVE_PRIORITY)
    }

    override fun isModelReady(): Boolean = modelReady()

    /** Foreground Service を開始するので、アプリが前面にある間に呼ぶ。 */
    override fun start(): Boolean {
        tracker.onStartRequested()
        publish()
        try {
            val intent = OrganizeService.startIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: IllegalStateException) {
            // アプリが前面にないと、API 31 以降は ForegroundServiceStartNotAllowedException になる
            Log.e(TAG, "整理の Service を開始できません: ${e.javaClass.simpleName}")
            tracker.onStartFailed()
            publish()
            return false
        }
        main.postDelayed(startTimeout, START_TIMEOUT_MILLIS)
        return true
    }

    override fun stop() {
        send(OrganizeService.MESSAGE_STOP_BY_USER)
    }

    private fun handle(message: Message): Boolean {
        when (message.what) {
            OrganizeService.MESSAGE_STATUS -> {
                val next = message.data.toOrganizeStatus()
                if (next.isRunning || next.ending != null) main.removeCallbacks(startTimeout)
                tracker.onStatus(next)
                publish()
            }
            OrganizeService.MESSAGE_MEMO_CHANGED -> onMemoChanged(MemoChange.entries[message.arg1])
        }
        return true
    }

    private fun publish() {
        states.value = tracker.status
    }

    private fun send(what: Int) {
        val message = Message.obtain(null, what).apply { replyTo = client }
        try {
            service?.send(message)
        } catch (_: RemoteException) {
            Log.w(TAG, "整理のプロセスに届きません")
        }
    }

    private fun lost() {
        publish()
        OrganizeNotifications(context).showEnding(OrganizeEnding.FAILED)
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        service = Messenger(binder)
        send(OrganizeService.MESSAGE_SUBSCRIBE)
    }

    /** 正常に終わったときは、最後の状態が先に届いている。動いているまま切れたら、プロセスが落ちた。 */
    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
        main.removeCallbacks(startTimeout)
        if (tracker.onLost()) lost() else publish()
    }

    /** Service が終わるとこの接続は使えなくなるので、次の実行のために繋ぎ直す。 */
    override fun onBindingDied(name: ComponentName?) {
        context.unbindService(this)
        bind()
    }
}
