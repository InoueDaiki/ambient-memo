package dev.ambientmemo.engine.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "organize"
private const val INTERVAL_HOURS = 6L

/** 実行基盤が動かないときに、同じ充電の間にやり直す回数の上限。次は定期実行に任せる。 */
private const val MAX_RUN_ATTEMPTS = 3

/** 充電が切れてから、処理中のまとまりを記録し終えるのを待つ時間。超えたら捨てて次回やり直す。 */
private const val STOP_GRACE_MILLIS = 60_000L

/** 充電中かつ端末を使っていない間に、定期的に整理する。 */
internal object OrganizeSchedule {
    /** 何度呼んでも予約は1つ。再起動をまたいで WorkManager が持つ。 */
    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<OrganizeWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/**
 * [OrganizeService] に整理を頼み、終わるのを待つ。LLM はこのプロセスで動かさない。
 *
 * 条件が外れて止められたら、区切りのよいところで止めるよう頼む。残りは未処理のまま残るので、次の実行が続きから処理する。
 * 手動の実行が動いていれば、すぐに終わった返事が来る。同時には動かさない。
 */
class OrganizeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = when (OrganizeConnection(applicationContext).run()) {
        OrganizeRunResult.FAILED -> if (runAttemptCount + 1 < MAX_RUN_ATTEMPTS) Result.retry() else Result.success()
        OrganizeRunResult.COMPLETED, OrganizeRunResult.STOPPED, OrganizeRunResult.MODEL_MISSING -> Result.success()
    }
}

/** [OrganizeService] への bind の1回分。相手のプロセスが落ちたら失敗として返す。 */
private class OrganizeConnection(private val context: Context) : ServiceConnection {
    private val finished = CompletableDeferred<OrganizeRunResult>()
    private val client = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                OrganizeService.MESSAGE_FINISHED -> finished.complete(OrganizeRunResult.entries[message.arg1])
                OrganizeService.MESSAGE_MEMO_CHANGED ->
                    Engine.get(context).notifyMemoChanged(MemoChange.entries[message.arg1])
            }
            true
        },
    )

    @Volatile
    private var service: Messenger? = null

    suspend fun run(): OrganizeRunResult {
        val intent = Intent(context, OrganizeService::class.java)
        // BIND_NOT_FOREGROUND: 聞き取りのプロセスの優先度を持ち込まず、CPU とメモリを聞き取りに譲る
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND
        if (!context.bindService(intent, this, flags)) return OrganizeRunResult.FAILED
        try {
            return finished.await()
        } catch (e: CancellationException) {
            send(OrganizeService.MESSAGE_STOP)
            withContext(NonCancellable) { withTimeoutOrNull(STOP_GRACE_MILLIS) { finished.await() } }
            throw e
        } finally {
            context.unbindService(this)
        }
    }

    private fun send(what: Int) {
        val message = Message.obtain(null, what).apply { replyTo = client }
        try {
            service?.send(message)
        } catch (_: RemoteException) {
            finished.complete(OrganizeRunResult.FAILED)
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        service = Messenger(binder)
        send(OrganizeService.MESSAGE_START)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        finished.complete(OrganizeRunResult.FAILED)
    }

    override fun onBindingDied(name: ComponentName?) {
        finished.complete(OrganizeRunResult.FAILED)
    }

    override fun onNullBinding(name: ComponentName?) {
        finished.complete(OrganizeRunResult.FAILED)
    }
}
