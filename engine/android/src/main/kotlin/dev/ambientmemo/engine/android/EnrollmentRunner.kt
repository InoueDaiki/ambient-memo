package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.EnrollmentOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicBoolean

/** 自分の声を登録できなかった理由。 */
enum class EnrollmentFailure {
    MICROPHONE_PERMISSION,
    MICROPHONE_UNAVAILABLE,
    SPEAKER_MODEL_UNAVAILABLE,
    LISTENING,
    NOT_ENOUGH_SPEECH,
    CANCELLED,
}

class SelfEnrollmentException(val failure: EnrollmentFailure) : Exception(failure.name)

/** 自分の声の登録を1本だけ動かし、止められるようにする。結果と失敗は [SelfEnrollmentException] にまとめる。 */
internal class EnrollmentRunner(
    private val scope: CoroutineScope,
    private val precondition: suspend () -> EnrollmentFailure?,
    private val enroll: suspend () -> EnrollmentOutcome,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    private var job: Deferred<EnrollmentFailure?>? = null

    val isRunning: Boolean
        get() = running.get()

    /** @throws SelfEnrollmentException 登録できなかったとき */
    suspend fun run() {
        if (!running.compareAndSet(false, true)) throw SelfEnrollmentException(EnrollmentFailure.LISTENING)
        try {
            attempt()?.let { throw SelfEnrollmentException(it) }
        } finally {
            job = null
            running.set(false)
        }
    }

    /** 動いていなければ何もしない。 */
    fun cancel() {
        job?.cancel()
    }

    // 前提の確認も止められるように、まとめて動かす。モデルのハッシュの計算に時間がかかることがある。
    // 呼び出し元のキャンセルと区別せずに cancelled にする。どちらも何も記録していない
    private suspend fun attempt(): EnrollmentFailure? = try {
        scope.async { precondition() ?: failureOf(enroll()) }.also { job = it }.await()
    } catch (_: CancellationException) {
        EnrollmentFailure.CANCELLED
    } catch (_: AudioCaptureException) {
        EnrollmentFailure.MICROPHONE_UNAVAILABLE
    }

    private fun failureOf(outcome: EnrollmentOutcome): EnrollmentFailure? =
        if (outcome == EnrollmentOutcome.ENROLLED) null else EnrollmentFailure.NOT_ENOUGH_SPEECH
}
