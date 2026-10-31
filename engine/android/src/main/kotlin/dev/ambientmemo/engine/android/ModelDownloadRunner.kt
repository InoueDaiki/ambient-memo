package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.ModelDownloadException
import dev.ambientmemo.engine.domain.ModelDownloadFailure
import dev.ambientmemo.engine.domain.ModelDownloadPhase
import dev.ambientmemo.engine.domain.ModelDownloadProgress
import dev.ambientmemo.engine.domain.ModelDownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val PREPARING = ModelDownloadState.Running(ModelDownloadProgress(ModelDownloadPhase.DOWNLOADING, 0, 0))

/**
 * モデルの取得を1本だけ動かし、状態を公開する。
 *
 * 聞き取りの [CaptureRunner] とは状態も [scope] も共有しない。どちらかが止まっても、もう一方に影響しない。
 */
internal class ModelDownloadRunner(
    private val scope: CoroutineScope,
    private val onFailure: (Exception) -> Unit = {},
    private val download: suspend (onProgress: (ModelDownloadProgress) -> Unit) -> Unit,
) {
    private val mutableState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    private val lock = Any()
    private var job: Job? = null

    val state: StateFlow<ModelDownloadState> = mutableState.asStateFlow()

    /** Service に開始を頼む前に呼ぶ。動いていれば false を返す。 */
    fun markStarting(): Boolean = synchronized(lock) {
        if (mutableState.value is ModelDownloadState.Running) return false
        mutableState.value = PREPARING
        true
    }

    /** [markStarting] の後で、開始に進めなかったときに呼ぶ。 */
    fun abandonStart(failure: ModelDownloadFailure) {
        synchronized(lock) {
            if (job?.isActive != true) mutableState.value = ModelDownloadState.Failed(failure)
        }
    }

    /**
     * [markStarting] の後で Service から呼ぶ。その間に止められていたら始めない。
     * 止めた直後なら、前の取得が終わるのを待ってファイルを奪い合わない。
     */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true || mutableState.value != PREPARING) return
            val previous = job
            lateinit var current: Job
            current = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                run(current)
            }
            job = current
            current.start()
        }
    }

    /** すぐに [ModelDownloadState.Idle] にする。通信の読み取りで止まっている取得は、読み取りの期限で終わる。 */
    fun cancel() {
        synchronized(lock) {
            job?.cancel()
            mutableState.value = ModelDownloadState.Idle
        }
    }

    @Suppress("TooGenericExceptionCaught") // 想定外の失敗でも、取得中のままにしない
    private suspend fun run(current: Job) {
        try {
            download { progress -> update(current, ModelDownloadState.Running(progress)) }
            update(current, ModelDownloadState.Idle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelDownloadException) {
            onFailure(e)
            update(current, ModelDownloadState.Failed(e.failure, e.shortageBytes))
        } catch (e: Exception) {
            onFailure(e)
            update(current, ModelDownloadState.Failed(ModelDownloadFailure.UNKNOWN))
        }
    }

    /** 止めた後の取得の知らせで、状態を上書きしない。 */
    private fun update(current: Job, state: ModelDownloadState) {
        synchronized(lock) {
            if (job === current && current.isActive) mutableState.value = state
        }
    }
}
