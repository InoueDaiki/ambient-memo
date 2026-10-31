package dev.ambientmemo.engine.llm

import dev.ambientmemo.engine.domain.Llm
import dev.ambientmemo.engine.domain.LlmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 読み込んだモデル。実行基盤を隠す境界。[generate] は終わるまで戻らない。 */
interface NativeLlm : AutoCloseable {
    /**
     * [request] の出力を作る。実行基盤の履歴を次の呼び出しへ持ち越さない。
     *
     * @throws dev.ambientmemo.engine.domain.LlmInputTooLongException 入力が文脈長に収まらないとき
     */
    fun generate(request: LlmRequest): String

    /** 別のスレッドから呼ぶ。動いている [generate] をできるだけ早く終わらせる。 */
    fun cancel()
}

/** モデルをメモリに読み込む。数十秒かかることがある。 */
fun interface NativeLlmLoader {
    fun load(): NativeLlm
}

/**
 * ローカルの LLM。モデルのメモリを持つのはこのクラスだけ。
 *
 * 数 GB を使うので常駐させない。[load] で読み込み、[close] で必ず解放する。
 * `LocalLlm(...).use { it.load(); ... }` の形で、処理の間だけ持つ。
 *
 * 実行基盤は同時に1つしか動かせないので、[generate] は順番に動かす。キャンセルされたときは実行基盤に止めるよう頼み、
 * 実際に止まるまで待ってから例外を投げる。止まる前に [close] すると、動いている処理のメモリを解放してしまう。
 */
class LocalLlm(
    override val modelVersion: String,
    private val loader: NativeLlmLoader,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Llm, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = ReentrantLock()

    @Volatile
    private var native: NativeLlm? = null

    /** すでに読み込んでいれば何もしない。終わるまで戻らないので、Main では呼ばない。 */
    fun load() {
        lock.withLock {
            if (native == null) native = loader.load()
        }
    }

    override suspend fun generate(request: LlmRequest): String {
        val loaded = checkNotNull(native) { "モデルを読み込んでいません" }
        val running = scope.async { lock.withLock { checkNotNull(native) { "モデルを解放しました" }.generate(request) } }
        try {
            return running.await()
        } catch (e: CancellationException) {
            loaded.cancel()
            withContext(NonCancellable) { running.join() }
            throw e
        }
    }

    /** 動いている処理を止めてから解放する。何度呼んでもよい。 */
    override fun close() {
        native?.cancel()
        lock.withLock {
            native?.close()
            native = null
        }
    }
}
