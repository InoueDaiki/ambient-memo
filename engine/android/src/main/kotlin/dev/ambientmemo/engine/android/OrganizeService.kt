package dev.ambientmemo.engine.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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
import dev.ambientmemo.engine.domain.DiagnosticProcess
import dev.ambientmemo.engine.domain.Llm
import dev.ambientmemo.engine.domain.LlmBackendChosen
import dev.ambientmemo.engine.domain.OrganizeJob
import dev.ambientmemo.engine.domain.OrganizeProgress
import dev.ambientmemo.engine.domain.OrganizeTrigger
import dev.ambientmemo.engine.domain.SystemClock
import dev.ambientmemo.engine.llm.LocalLlm
import dev.ambientmemo.engine.llm.android.LiteRtLlmLoader
import dev.ambientmemo.engine.sherpa.ModelCatalog
import dev.ambientmemo.engine.sherpa.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.io.File
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

private const val TAG = "AmbientMemoOrganize"
private const val MODELS_DIRECTORY = "models"
private const val LLM_CACHE_DIRECTORY = "llm"
private const val ATTEMPTS_FILE = "organize-attempts.properties"

/** 止まらなくなった実行が電池を使い続けないようにする上限。dataSync の 6 時間の制限と揃える。 */
private const val WAKE_LOCK_TIMEOUT_MILLIS = 6L * 60 * 60 * 1000

/** 読み込み中に止める依頼が来てから、プロセスごと終えるまでの猶予。 */
private const val LOAD_STOP_GRACE_MILLIS = 5_000L

/** 終わりの状態が見守り側に届くのを待つ時間。 */
private const val STATUS_DELIVERY_MILLIS = 500L

/**
 * 別のプロセス(`:organize`)で LLM を動かす。定期実行では [OrganizeWorker] が bind して使う。
 * 手動の実行では、画面の操作から dataSync 型の Foreground Service として開始し、アプリを離れても完了まで動く。
 *
 * 聞き取りはバックグラウンドから再開できないので、数 GB のモデルとネイティブのクラッシュを聞き取りのプロセスから離す。
 * [Engine] はこのプロセスでは作らない。ここで使うのは DB とモデルのファイルだけ。
 *
 * OS に再起動を任せない。整理は利用者の操作か定期実行でだけ始める。
 *
 * 文字起こしや例外の中身をログに出さない。
 */
@Suppress("TooManyFunctions") // Service の入口と、実行の組み立てを1か所で読めるようにする
class OrganizeService : Service() {
    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(
            {
                // 実行基盤の作業のスレッドは、作ったスレッドの優先度を引き継ぐ。
                // Foreground Service でもこの優先度のままにして、聞き取りに CPU を譲る
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            },
            "organize",
        )
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val database by lazy { openAndroidDatabase(this) }
    private val notifications by lazy { OrganizeNotifications(this) }
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<Messenger>()
    private val runner = OrganizeRunner(scope, ::session, ::deviceState, ::publish, ::finished)
    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                MESSAGE_START -> startScheduled(message.replyTo)
                MESSAGE_STOP -> runner.requestSchedulerStop()
                MESSAGE_STOP_BY_USER -> stopByUser()
                MESSAGE_SUBSCRIBE -> subscribe(message.replyTo)
            }
            true
        },
    )
    private val journal by lazy { DiagnosticsJournal(filesDir, DiagnosticProcess.ORGANIZE, SystemClock) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    @Volatile
    private var client: Messenger? = null

    @Volatile
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> startManual(startId)
            ACTION_STOP -> {
                stopByUser()
                if (!runner.isRunning) stopSelf(startId)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun stopByUser() {
        runner.requestStop()
        main.postDelayed(::endStuckLoad, LOAD_STOP_GRACE_MILLIS)
    }

    /** モデルの読み込みはネイティブの中で止められず、終わらないこともあるので、別プロセスごと終える。 */
    private fun endStuckLoad() {
        if (!runner.isStoppingBeforeChunks) return
        runner.abandon(OrganizeEnding.STOPPED)
        leaveForeground(OrganizeEnding.STOPPED)
        main.postDelayed({ Process.killProcess(Process.myPid()) }, STATUS_DELIVERY_MILLIS)
    }

    private fun startScheduled(replyTo: Messenger?) {
        if (runner.isRunning) {
            send(MESSAGE_FINISHED, OrganizeRunResult.COMPLETED.ordinal, replyTo)
            return
        }
        client = replyTo
        runner.start(OrganizeTrigger.SCHEDULED)
    }

    private fun startManual(startId: Int) {
        val notification = notifications.progress(runner.status)
        try {
            // startForegroundService のたびに呼ぶ。呼ばないと OS にプロセスごと落とされる
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ORGANIZE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ORGANIZE_NOTIFICATION_ID, notification)
            }
        } catch (e: IllegalStateException) {
            failStart(startId, e)
            return
        } catch (e: SecurityException) {
            failStart(startId, e)
            return
        }
        isForeground = true
        acquireWakeLock()
        runner.start(OrganizeTrigger.MANUAL)
    }

    /** 1 日の dataSync の上限を使い切ると、ここで ForegroundServiceStartNotAllowedException になる。 */
    private fun failStart(startId: Int, cause: Exception) {
        Log.e(TAG, "前面で動かせません: ${cause.javaClass.simpleName}")
        publish(OrganizeStatus(isManual = true, ending = OrganizeEnding.FAILED))
        stopSelf(startId)
    }

    /** 画面を消して充電も切れていると、CPU が眠って止まる。Foreground Service だけでは防げない。 */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ambient-memo:organize").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun subscribe(observer: Messenger?) {
        observer ?: return
        observers += observer
        send(observer, statusMessage(runner.status))
    }

    private fun statusMessage(status: OrganizeStatus) =
        Message.obtain(null, MESSAGE_STATUS).apply { data = status.toBundle() }

    private fun publish(status: OrganizeStatus) {
        observers.forEach { send(it, statusMessage(status)) }
        if (isForeground && status.isRunning) notifications.update(status)
    }

    private fun finished(result: OrganizeRunResult, ending: OrganizeEnding?) {
        send(MESSAGE_FINISHED, result.ordinal, client)
        main.post { leaveForeground(ending) }
    }

    private fun leaveForeground(ending: OrganizeEnding?) {
        // 終わる間際に次の手動の依頼が来ていたら、その実行のために前面に残る
        if (!isForeground || runner.isRunning) return
        isForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        notifications.showEnding(ending)
        stopSelf(lastStartId)
    }

    /**
     * dataSync の Service は、API 35 以降バックグラウンドで 1 日 6 時間を超えると OS に止められる。
     * 数秒で止まらないとクラッシュするので、処理中のまとまりを捨てる。未処理のまま残るので次回やり直す。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        runner.abandon(OrganizeEnding.TIME_LIMIT)
        leaveForeground(OrganizeEnding.TIME_LIMIT)
        stopSelf(startId)
    }

    private fun session(onProgress: (OrganizeProgress) -> Unit): OrganizeSession {
        // 読み込みが終わらないときも、始めたことと、その時の端末の状態を残す
        journal.record(deviceSample(this))
        val store = ModelStore(File(filesDir, MODELS_DIRECTORY))
        val model = ModelCatalog.ACTIVE_LLM
        return OrganizeSession(
            isModelReady = { store.verify(model.modelPackage) },
            openLlm = {
                val cache = File(cacheDir, LLM_CACHE_DIRECTORY).apply { mkdirs() }
                val path = store.pathOf(model.modelPackage, model.model)
                val loader = LiteRtLlmLoader(path, cache.path) { journal.record(LlmBackendChosen(it)) }
                LocalLlm(model.version, loader, dispatcher)
            },
            jobFor = ::jobFor,
            onFailure = { Log.e(TAG, "モデルを読み込めません: ${it.javaClass.simpleName}") },
            onResult = {
                Log.i(TAG, "整理: ${it.outcome} メモ ${it.memoCount} 読めない ${it.unreadableCount}")
                journal.record(deviceSample(this))
            },
            onProgress = onProgress,
            diagnostics = journal,
        )
    }

    private fun jobFor(llm: Llm) = OrganizeJob(
        // 画面はメインのプロセスにあるので、変更は bind を通して知らせる
        memos = ObservableMemoRepository(SqliteMemoRepository(database)) { change ->
            // 見守りがいないときは、定期実行の Worker が受け取る
            observers.ifEmpty { listOfNotNull(client) }.forEach { send(MESSAGE_MEMO_CHANGED, change.ordinal, it) }
        },
        speakers = SqliteSpeakerRepository(database),
        llm = llm,
        clock = SystemClock,
        timeZone = TimeZone.getDefault(),
        attempts = FileOrganizeAttempts(File(filesDir, ATTEMPTS_FILE)),
    )

    /** まとまりの間で確かめる。WorkManager の条件が外れたときの [MESSAGE_STOP] より先に気づくこともある。 */
    private fun deviceState() = OrganizeDeviceState(isCharging(), isTooHot())

    private fun isCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun isTooHot(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

    private fun send(what: Int, value: Int, target: Messenger?) {
        target?.let { send(it, Message.obtain(null, what, value, 0)) }
    }

    private fun send(target: Messenger, message: Message) {
        try {
            target.send(message)
        } catch (_: RemoteException) {
            // 相手のプロセスがいないので、知らせる先がない
            observers -= target
            Log.w(TAG, "結果を伝えられません")
        }
    }

    /** bind が切れて、手動の実行もなければ、処理中のまとまりは捨てて止める。記録していないまとまりは次回やり直す。 */
    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    internal companion object {
        const val MESSAGE_START = 1
        const val MESSAGE_STOP = 2
        const val MESSAGE_FINISHED = 3
        const val MESSAGE_MEMO_CHANGED = 4
        const val MESSAGE_STOP_BY_USER = 5
        const val MESSAGE_SUBSCRIBE = 6
        const val MESSAGE_STATUS = 7

        private const val ACTION_START = "dev.ambientmemo.engine.action.START_ORGANIZE"
        private const val ACTION_STOP = "dev.ambientmemo.engine.action.STOP_ORGANIZE"

        fun bindIntent(context: Context): Intent = Intent(context, OrganizeService::class.java)

        fun startIntent(context: Context): Intent = bindIntent(context).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent = bindIntent(context).setAction(ACTION_STOP)
    }
}
