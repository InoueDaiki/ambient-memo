package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.Llm
import dev.ambientmemo.engine.domain.OrganizeJob
import dev.ambientmemo.engine.domain.OrganizeOutcome
import dev.ambientmemo.engine.domain.OrganizeProgress
import dev.ambientmemo.engine.domain.OrganizeResult
import dev.ambientmemo.engine.llm.LocalLlm

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
internal class OrganizeSession(
    private val isModelReady: () -> Boolean,
    private val openLlm: () -> LocalLlm,
    private val jobFor: (Llm) -> OrganizeJob,
    private val onFailure: (Exception) -> Unit = {},
    private val onResult: (OrganizeResult) -> Unit = {},
    private val onProgress: (OrganizeProgress) -> Unit = {},
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
    private fun load(llm: LocalLlm): Boolean = try {
        llm.load()
        true
    } catch (e: Exception) {
        onFailure(e)
        false
    }

    private suspend fun organize(llm: Llm, shouldContinue: () -> Boolean): OrganizeRunResult {
        val result = jobFor(llm).run(shouldContinue, onProgress)
        onResult(result)
        return when (result.outcome) {
            OrganizeOutcome.COMPLETED -> OrganizeRunResult.COMPLETED
            OrganizeOutcome.STOPPED -> OrganizeRunResult.STOPPED
            OrganizeOutcome.LLM_FAILED -> OrganizeRunResult.FAILED
        }
    }
}
