package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.DiagnosticsLog
import dev.ambientmemo.engine.domain.Llm
import dev.ambientmemo.engine.domain.LlmLoaded
import dev.ambientmemo.engine.domain.OrganizeFinished
import dev.ambientmemo.engine.domain.OrganizeJob
import dev.ambientmemo.engine.domain.OrganizeOutcome
import dev.ambientmemo.engine.domain.OrganizeProgress
import dev.ambientmemo.engine.domain.OrganizeResult
import dev.ambientmemo.engine.domain.TimedLlm
import dev.ambientmemo.engine.llm.LocalLlm
import kotlin.time.TimeSource

/** 1回の整理の結果。 */
enum class OrganizeRunResult {
    /** 今処理できるまとまりをすべて処理した。 */
    COMPLETED,

    /** 区切りよく止めた。残りは次の実行で続ける。 */
    STOPPED,

    /** モデルがないので読み込まなかった。 */
    MODEL_MISSING,

    /** 実行基盤が動かなかった。しばらくしてやり直す。 */
    FAILED,
}

/**
 * モデルを読み込み、整理して、解放するまでの1回分。モデルのメモリはこの間だけ持つ。
 *
 * 読み込みには数十秒かかり、数 GB を使うので、モデルがないときや止めるときは読み込まない。
 */
@Suppress("LongParameterList") // 読み込みと結果の受け口を、実行の組み立てごとに差し替える
internal class OrganizeSession(
    private val isModelReady: () -> Boolean,
    private val openLlm: () -> LocalLlm,
    private val jobFor: (Llm) -> OrganizeJob,
    private val onFailure: (Exception) -> Unit = {},
    private val onResult: (OrganizeResult) -> Unit = {},
    private val onProgress: (OrganizeProgress) -> Unit = {},
    private val diagnostics: DiagnosticsLog = DiagnosticsLog.NONE,
) {
    suspend fun run(shouldContinue: () -> Boolean): OrganizeRunResult = when {
        !isModelReady() -> OrganizeRunResult.MODEL_MISSING
        !shouldContinue() -> OrganizeRunResult.STOPPED
        else -> openLlm().use { llm ->
            if (load(llm)) organize(llm, shouldContinue) else OrganizeRunResult.FAILED
        }
    }

    // 実行基盤の失敗は型で絞れない。読み込めない端末でも、聞き取りには影響させない
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

    private suspend fun organize(llm: Llm, shouldContinue: () -> Boolean): OrganizeRunResult {
        val started = TimeSource.Monotonic.markNow()
        val result = jobFor(TimedLlm(llm, diagnostics)).run(shouldContinue, onProgress)
        diagnostics.record(
            OrganizeFinished(
                result.outcome,
                result.memoCount,
                result.todoCount,
                result.unreadableCount,
                started.elapsedNow().inWholeMilliseconds,
            ),
        )
        onResult(result)
        return when (result.outcome) {
            OrganizeOutcome.COMPLETED -> OrganizeRunResult.COMPLETED
            OrganizeOutcome.STOPPED -> OrganizeRunResult.STOPPED
            OrganizeOutcome.LLM_FAILED -> OrganizeRunResult.FAILED
        }
    }
}
