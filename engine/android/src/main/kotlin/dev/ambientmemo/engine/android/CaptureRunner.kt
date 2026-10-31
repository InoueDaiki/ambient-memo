package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.AudioSource
import dev.ambientmemo.engine.domain.CapturePipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** 聞き取りの状態。 */
sealed interface CaptureState {
    /** 止まっている。[interrupted] は、操作ではなく読み取りの失敗などで止まったときに true。 */
    data class Stopped(val interrupted: Boolean = false) : CaptureState

    data object Starting : CaptureState

    data object Listening : CaptureState

    data object Stopping : CaptureState
}

/**
 * [CapturePipeline] を1本だけ動かし、状態を公開する。
 *
 * 最初のフレームが届いて初めて [CaptureState.Listening] にする。マイクを開けなかったときに、
 * 聞き取り中と見せないため。
 */
internal class CaptureRunner(
    private val scope: CoroutineScope,
    private val onFailure: (Exception) -> Unit = {},
    private val createPipeline: (AudioSource) -> CapturePipeline,
    private val audioSource: () -> AudioSource,
) {
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Stopped())
    private val lock = Any()
    private var job: Job? = null

    val state: StateFlow<CaptureState> = mutableState.asStateFlow()

    /** Service に開始を頼む前に呼ぶ。止まっていなければ false を返す。 */
    fun markStarting(): Boolean = synchronized(lock) {
        if (job?.isActive == true || mutableState.value !is CaptureState.Stopped) return false
        mutableState.value = CaptureState.Starting
        true
    }

    /** [markStarting] の後で、開始に進めなかったときに呼ぶ。 */
    fun abandonStart(interrupted: Boolean = false) {
        synchronized(lock) {
            if (job?.isActive != true) mutableState.value = CaptureState.Stopped(interrupted)
        }
    }

    /** 動いていなければ開始する。動いているときは何もしない。 */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            mutableState.value = CaptureState.Starting
            job = scope.launch { runPipeline() }
        }
    }

    /** 止まるまで待つ。未処理の音声を捨てて、欠落を記録し終えてから戻る。 */
    suspend fun stop() {
        val running = synchronized(lock) {
            val active = job?.takeIf { it.isActive } ?: return
            mutableState.value = CaptureState.Stopping
            active
        }
        running.cancelAndJoin()
    }

    @Suppress("TooGenericExceptionCaught") // ポートの実装が何を投げるかは決めていない
    private suspend fun runPipeline() {
        // マイクの音声に終わりはないので、頼まれずに戻ったときも中断として扱う
        var interrupted = true
        try {
            createPipeline(ObservedAudioSource(audioSource(), ::markListening)).run()
        } catch (e: CancellationException) {
            interrupted = false
            throw e
        } catch (e: Exception) {
            onFailure(e)
        } finally {
            mutableState.value = CaptureState.Stopped(interrupted)
        }
    }

    private fun markListening() {
        mutableState.compareAndSet(CaptureState.Starting, CaptureState.Listening)
    }
}

private class ObservedAudioSource(
    private val delegate: AudioSource,
    private val onFrame: () -> Unit,
) : AudioSource {
    override val sampleRateHz: Int
        get() = delegate.sampleRateHz

    override fun frames(): Flow<AudioFrame> = delegate.frames().onEach { onFrame() }
}
