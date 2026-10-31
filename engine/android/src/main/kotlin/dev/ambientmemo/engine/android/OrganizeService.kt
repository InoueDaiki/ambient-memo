package dev.ambientmemo.engine.android

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import android.os.RemoteException
import android.util.Log
import dev.ambientmemo.engine.adapters.FileOrganizeAttempts
import dev.ambientmemo.engine.adapters.SqliteMemoRepository
import dev.ambientmemo.engine.adapters.SqliteSpeakerRepository
import dev.ambientmemo.engine.adapters.openAndroidDatabase
import dev.ambientmemo.engine.domain.Llm
import dev.ambientmemo.engine.domain.OrganizeJob
import dev.ambientmemo.engine.domain.SystemClock
import dev.ambientmemo.engine.llm.LocalLlm
import dev.ambientmemo.engine.llm.android.LiteRtLlmLoader
import dev.ambientmemo.engine.sherpa.ModelCatalog
import dev.ambientmemo.engine.sherpa.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executors

private const val TAG = "AmbientMemoOrganize"
private const val MODELS_DIRECTORY = "models"
private const val LLM_CACHE_DIRECTORY = "llm"
private const val ATTEMPTS_FILE = "organize-attempts.properties"

/**
 * 別のプロセス(`:organize`)で LLM を動かす。[OrganizeWorker] が bind して使う。
 *
 * 聞き取りはバックグラウンドから再開できないので、数 GB のモデルとネイティブのクラッシュを聞き取りのプロセスから離す。
 * [Engine] はこのプロセスでは作らない。ここで使うのは DB とモデルのファイルだけ。
 *
 * 文字起こしや例外の中身をログに出さない。
 */
class OrganizeService : Service() {
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(
            {
                // 実行基盤の作業のスレッドは、作ったスレッドの優先度を引き継ぐ
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            },
            "organize",
        )
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val database by lazy { openAndroidDatabase(this) }
    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                MESSAGE_START -> start(message.replyTo)
                MESSAGE_STOP -> stopRequested = true
            }
            true
        },
    )
    private var run: Job? = null

    @Volatile
    private var client: Messenger? = null

    @Volatile
    private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun start(replyTo: Messenger?) {
        if (run?.isActive == true) return
        stopRequested = false
        client = replyTo
        run = scope.launch {
            val result = session().run(::shouldContinue)
            send(MESSAGE_FINISHED, result.ordinal)
        }
    }

    private fun session(): OrganizeSession {
        val store = ModelStore(File(filesDir, MODELS_DIRECTORY))
        val model = ModelCatalog.ACTIVE_LLM
        return OrganizeSession(
            isModelReady = { store.verify(model.modelPackage) },
            openLlm = {
                val cache = File(cacheDir, LLM_CACHE_DIRECTORY).apply { mkdirs() }
                val path = store.pathOf(model.modelPackage, model.model)
                LocalLlm(model.version, LiteRtLlmLoader(path, cache.path), dispatcher)
            },
            jobFor = ::jobFor,
            onFailure = { Log.e(TAG, "モデルを読み込めません: ${it.javaClass.simpleName}") },
            onResult = { Log.i(TAG, "整理: ${it.outcome} メモ ${it.memoCount} 読めない ${it.unreadableCount}") },
        )
    }

    private fun jobFor(llm: Llm) = OrganizeJob(
        // 画面はメインのプロセスにあるので、変更は bind を通して知らせる
        memos = ObservableMemoRepository(SqliteMemoRepository(database)) { change ->
            send(MESSAGE_MEMO_CHANGED, change.ordinal)
        },
        speakers = SqliteSpeakerRepository(database),
        llm = llm,
        clock = SystemClock,
        timeZone = TimeZone.getDefault(),
        attempts = FileOrganizeAttempts(File(filesDir, ATTEMPTS_FILE)),
    )

    /** まとまりの間で確かめる。WorkManager の条件が外れたときの [MESSAGE_STOP] より先に気づくこともある。 */
    private fun shouldContinue(): Boolean = !stopRequested && isCharging() && !isTooHot()

    private fun isCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun isTooHot(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

    private fun send(what: Int, value: Int) {
        try {
            client?.send(Message.obtain(null, what, value, 0))
        } catch (_: RemoteException) {
            // 相手のプロセスがいないので、知らせる先がない
            Log.w(TAG, "結果を伝えられません")
        }
    }

    /** bind が切れたら、処理中のまとまりは捨てて止める。記録していないまとまりは次回やり直す。 */
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    internal companion object {
        const val MESSAGE_START = 1
        const val MESSAGE_STOP = 2
        const val MESSAGE_FINISHED = 3
        const val MESSAGE_MEMO_CHANGED = 4
    }
}
