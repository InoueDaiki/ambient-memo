package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.OrganizeConditions
import dev.ambientmemo.engine.domain.OrganizeProgress
import dev.ambientmemo.engine.domain.OrganizeStopReason
import dev.ambientmemo.engine.domain.OrganizeTrigger
import dev.ambientmemo.engine.domain.stopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 今の実行の段階。 */
internal enum class OrganizePhase { IDLE, PREPARING, ORGANIZING, STOPPING }

/** 手動の実行の終わり方。画面と通知に出す。 */
internal enum class OrganizeEnding { COMPLETED, STOPPED, TOO_HOT, MODEL_MISSING, FAILED, TIME_LIMIT }

/** [ending] は手動の実行の最後の結果。定期実行の結果は画面に出さない。 */
internal data class OrganizeStatus(
    val phase: OrganizePhase = OrganizePhase.IDLE,
    val completedChunks: Int = 0,
    val totalChunks: Int = 0,
    val isManual: Boolean = false,
    val ending: OrganizeEnding? = null,
) {
    val isRunning: Boolean get() = phase != OrganizePhase.IDLE
}

/** [OrganizeRunner.start] の結果。 */
internal enum class OrganizeStart {
    STARTED,

    /** 手動の依頼。動いている実行を手動に切り替えた。 */
    JOINED,

    /** 定期実行の依頼。別の実行が動いているので何もしない。 */
    BUSY,
}

/** 端末の状態。 */
internal data class OrganizeDeviceState(val isCharging: Boolean, val isTooHot: Boolean)

/**
 * 整理を同時に1つだけ動かす。定期実行も手動の実行もここを通るので、`:organize` の中で重ならない。
 *
 * 定期実行の途中で手動の依頼が来たら、新しく始めず、その実行を手動に切り替える。画面を開くと端末は idle でなくなり、
 * 定期実行は WorkManager に止められるので、切り替えないとモデルを読み込み直すことになる。
 *
 * Android の型に依存しないので、JVM でテストする。
 */
internal class OrganizeRunner(
    private val scope: CoroutineScope,
    private val session: (onProgress: (OrganizeProgress) -> Unit) -> OrganizeSession,
    private val device: () -> OrganizeDeviceState,
    private val onStatus: (OrganizeStatus) -> Unit,
    private val onFinished: (OrganizeRunResult, OrganizeEnding?) -> Unit,
) {
    private val lock = Any()
    private var job: Job? = null
    private var trigger = OrganizeTrigger.SCHEDULED
    private var stopRequested = false
    private var schedulerStopRequested = false
    private var stopReason: OrganizeStopReason? = null
    private var abandoned = false

    @Volatile
    var status = OrganizeStatus()
        private set

    val isRunning: Boolean get() = status.isRunning

    /** 止める依頼のあと、モデルの読み込みを待っている。まとまりに入る前なので、捨てても失うものがない。 */
    val isStoppingBeforeChunks: Boolean
        get() = status.let { it.phase == OrganizePhase.STOPPING && it.totalChunks == 0 }

    fun start(requested: OrganizeTrigger): OrganizeStart = synchronized(lock) {
        if (status.isRunning) return join(requested)
        trigger = requested
        stopRequested = false
        schedulerStopRequested = false
        stopReason = null
        abandoned = false
        update(OrganizeStatus(OrganizePhase.PREPARING, isManual = requested == OrganizeTrigger.MANUAL))
        job = scope.launch { finish(session(::progress).run(::shouldContinue)) }
        OrganizeStart.STARTED
    }

    private fun join(requested: OrganizeTrigger): OrganizeStart {
        if (requested == OrganizeTrigger.SCHEDULED) return OrganizeStart.BUSY
        trigger = OrganizeTrigger.MANUAL
        schedulerStopRequested = false
        update(status.copy(isManual = true))
        return OrganizeStart.JOINED
    }

    /** 利用者の依頼。まとまりの区切りで止める。 */
    fun requestStop() = synchronized(lock) {
        if (status.isRunning) {
            stopRequested = true
            update(status.copy(phase = OrganizePhase.STOPPING))
        }
    }

    /** WorkManager の依頼。手動に切り替えたあとは聞かない。 */
    fun requestSchedulerStop() = synchronized(lock) {
        if (trigger == OrganizeTrigger.SCHEDULED) schedulerStopRequested = true
    }

    /** OS に止められるので、区切りを待たずに捨てる。処理中のまとまりは次回やり直す。 */
    fun abandon(ending: OrganizeEnding) = synchronized(lock) {
        if (status.isRunning) {
            abandoned = true
            job?.cancel()
            update(OrganizeStatus(isManual = status.isManual, ending = ending.takeIf { status.isManual }))
        }
    }

    private fun shouldContinue(): Boolean {
        val state = device()
        return synchronized(lock) {
            val requested = stopRequested || schedulerStopRequested
            stopReason = trigger.stopReason(OrganizeConditions(requested, state.isCharging, state.isTooHot))
            stopReason == null
        }
    }

    private fun progress(progress: OrganizeProgress) = synchronized(lock) {
        if (!abandoned) {
            val phase = if (status.phase == OrganizePhase.STOPPING) status.phase else OrganizePhase.ORGANIZING
            update(status.copy(phase, progress.completedChunks, progress.totalChunks))
        }
    }

    private fun finish(result: OrganizeRunResult) {
        val ending = synchronized(lock) {
            if (abandoned) return
            val isManual = trigger == OrganizeTrigger.MANUAL
            val ending = if (isManual) endingOf(result) else null
            update(OrganizeStatus(isManual = isManual, ending = ending))
            ending
        }
        onFinished(result, ending)
    }

    private fun endingOf(result: OrganizeRunResult): OrganizeEnding = when (result) {
        OrganizeRunResult.COMPLETED -> OrganizeEnding.COMPLETED
        OrganizeRunResult.MODEL_MISSING -> OrganizeEnding.MODEL_MISSING
        OrganizeRunResult.FAILED -> OrganizeEnding.FAILED
        OrganizeRunResult.STOPPED ->
            if (stopReason == OrganizeStopReason.TOO_HOT) OrganizeEnding.TOO_HOT else OrganizeEnding.STOPPED
    }

    private fun update(next: OrganizeStatus) {
        status = next
        onStatus(next)
    }
}

/** メインのプロセスから見た状態。別のプロセスが落ちると何も届かないので、接続が切れたことで判断する。 */
internal class OrganizeStatusTracker {
    @Volatile
    var status = OrganizeStatus()
        private set

    private var isWaitingForService = false

    /** 開始を頼んだ。Service の返事が来るまでの間も、画面には動いていると見せる。 */
    @Synchronized
    fun onStartRequested() {
        if (status.isRunning) return
        isWaitingForService = true
        status = OrganizeStatus(OrganizePhase.PREPARING, isManual = true)
    }

    @Synchronized
    fun onStatus(next: OrganizeStatus) {
        // 開始の依頼の直後に、前の実行の止まった状態が届いても、依頼を消さない
        if (isWaitingForService && !next.isRunning && next.ending == null) return
        isWaitingForService = false
        status = next
    }

    /** 動いている手動の実行が失われたら true。 */
    @Synchronized
    fun onLost(): Boolean {
        val lost = status.isRunning && status.isManual
        isWaitingForService = false
        if (status.isRunning) {
            status = OrganizeStatus(isManual = status.isManual, ending = OrganizeEnding.FAILED.takeIf { lost })
        }
        return lost
    }

    /** 開始の失敗。 */
    @Synchronized
    fun onStartFailed() {
        isWaitingForService = false
        status = OrganizeStatus(isManual = true, ending = OrganizeEnding.FAILED)
    }

    /** Service から返事がないまま時間がたったら true。 */
    @Synchronized
    fun onStartTimedOut(): Boolean {
        if (!isWaitingForService) return false
        onStartFailed()
        return true
    }
}
