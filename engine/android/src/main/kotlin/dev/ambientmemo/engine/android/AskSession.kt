package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.AnswerOutcome
import dev.ambientmemo.engine.domain.AskFinished
import dev.ambientmemo.engine.domain.AskPhase
import dev.ambientmemo.engine.domain.DiagnosticsLog
import dev.ambientmemo.engine.domain.Llm
import dev.ambientmemo.engine.domain.LlmLoaded
import dev.ambientmemo.engine.domain.MemoQuestionAnswerer
import dev.ambientmemo.engine.domain.TimedLlm
import dev.ambientmemo.engine.llm.LocalLlm
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/** 質問の進み具合。読み込みの段階は、モデルを持つ側だけが知っている。 */
internal enum class AskStep { PREPARING, SEARCHING, ANSWERING }

/** 質問の終わり方。 */
internal enum class AskEnding {
    ANSWERED,
    NOT_FOUND,
    MODEL_MISSING,

    /** 整理か別の質問が動いている。モデルを 2 つ読み込むとメモリが足りない。 */
    BUSY,

    /** 実行基盤が動かなかったか、`:organize` が落ちた。 */
    FAILED,
}

/** [sourceIds] は答えが根拠にしたメモで、答えが挙げた順。 */
internal class AskReply(
    val ending: AskEnding,
    val text: String? = null,
    val sourceIds: List<Long> = emptyList(),
)

/** JS の境界が使う、質問の操作。 */
internal interface AskControl {
    /** 質問を動かす。キャンセルされたら [AskCancelledException] を投げる。 */
    suspend fun ask(question: String, onStep: (AskStep) -> Unit): AskReply

    /** 動いている質問を止める。動いていなければ何もしない。 */
    fun cancel()
}

/** 利用者が質問を止めた。 */
class AskCancelledException : Exception()

/**
 * モデルを読み込み、質問に答えて、解放するまでの1回分。整理と同じく、モデルのメモリはこの間だけ持つ。
 *
 * 質問のたびに読み込むので、答えまでに読み込みの時間がかかる。キャンセルはそのまま投げる。
 */
internal class AskSession(
    private val isModelReady: () -> Boolean,
    private val openLlm: () -> LocalLlm,
    private val answererFor: (Llm) -> MemoQuestionAnswerer,
    private val onFailure: (Exception) -> Unit = {},
    private val diagnostics: DiagnosticsLog = DiagnosticsLog.NONE,
) {
    suspend fun run(question: String, onStep: (AskStep) -> Unit): AskReply {
        if (!isModelReady()) return AskReply(AskEnding.MODEL_MISSING)
        onStep(AskStep.PREPARING)
        return openLlm().use { llm -> if (load(llm)) answer(llm, question, onStep) else AskReply(AskEnding.FAILED) }
    }

    // 実行基盤の失敗は型で絞れない
    @Suppress("TooGenericExceptionCaught")
    private fun load(llm: LocalLlm): Boolean {
        val started = TimeSource.Monotonic.markNow()
        val isLoaded = try {
            llm.load()
            true
        } catch (e: Exception) {
            onFailure(e)
            false
        }
        diagnostics.record(LlmLoaded(started.elapsedNow().inWholeMilliseconds, isLoaded))
        return isLoaded
    }

    @Suppress("TooGenericExceptionCaught") // 同上
    private suspend fun answer(llm: Llm, question: String, onStep: (AskStep) -> Unit): AskReply {
        val started = TimeSource.Monotonic.markNow()
        val answer = try {
            answererFor(TimedLlm(llm, diagnostics)).answer(question) { phase ->
                onStep(
                    when (phase) {
                        AskPhase.SEARCHING -> AskStep.SEARCHING
                        AskPhase.ANSWERING -> AskStep.ANSWERING
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(e)
            return AskReply(AskEnding.FAILED)
        }
        val totalMillis = started.elapsedNow().inWholeMilliseconds
        diagnostics.record(AskFinished(answer.outcome, answer.searchRounds, answer.sources.size, totalMillis))
        return when (answer.outcome) {
            AnswerOutcome.ANSWERED -> AskReply(AskEnding.ANSWERED, answer.text, answer.sources.map { it.id })
            AnswerOutcome.NOT_FOUND -> AskReply(AskEnding.NOT_FOUND)
        }
    }
}
