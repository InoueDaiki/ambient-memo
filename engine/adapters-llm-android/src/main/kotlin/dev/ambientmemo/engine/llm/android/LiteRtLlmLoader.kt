package dev.ambientmemo.engine.llm.android

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.SamplerConfig
import dev.ambientmemo.engine.domain.LlmInputTooLongException
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.llm.LlmSettings
import dev.ambientmemo.engine.llm.NativeLlm
import dev.ambientmemo.engine.llm.NativeLlmLoader
import dev.ambientmemo.engine.llm.isInputTooLong

/**
 * LiteRT-LM でモデルを読み込む。GPU を先に試し、使えない端末では CPU で動かす。
 *
 * 制約デコードは Gemma 4 で空の出力になるので使わない。thinking も使わない(docs/エンジン設計.md)。
 */
class LiteRtLlmLoader(
    private val modelPath: String,
    private val cacheDirectory: String,
) : NativeLlmLoader {
    override fun load(): NativeLlm = try {
        open(Backend.GPU()).also { it.confirmUsable() }
    } catch (_: LiteRtLmJniException) {
        open(Backend.CPU(LlmSettings.CPU_THREADS))
    }

    private fun open(backend: Backend): LiteRtNativeLlm {
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = LlmSettings.MAX_NUM_TOKENS,
                cacheDir = cacheDirectory,
            ),
        )
        return engine.closingOnFailure {
            initialize()
            LiteRtNativeLlm(this)
        }
    }
}

private inline fun <T> Engine.closingOnFailure(block: Engine.() -> T): T = try {
    block()
} catch (e: LiteRtLmJniException) {
    close()
    throw e
}

private class LiteRtNativeLlm(private val engine: Engine) : NativeLlm {
    @Volatile
    private var running: Conversation? = null

    /** OpenCL のない端末でも GPU の読み込みは成功し、最初の生成で失敗する。先に1語だけ生成して確かめる。 */
    fun confirmUsable() {
        engine.closingOnFailure {
            createConversation(ConversationConfig(maxOutputToken = 1)).use { it.sendMessage("はい") }
        }
    }

    override fun generate(request: LlmRequest): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(request.system),
            samplerConfig = SamplerConfig(
                topK = LlmSettings.TOP_K,
                topP = LlmSettings.TOP_P,
                temperature = LlmSettings.TEMPERATURE,
                seed = LlmSettings.SEED,
            ),
            maxOutputToken = request.maxOutputTokens,
        )
        // 履歴を持ち越すと文脈長を使い切るので、1回ごとに作り直す
        return engine.createConversation(config).use { conversation ->
            running = conversation
            try {
                conversation.sendMessage(request.user).contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
            } catch (e: LiteRtLmJniException) {
                if (isInputTooLong(e.message)) throw LlmInputTooLongException()
                throw e
            } finally {
                running = null
            }
        }
    }

    override fun cancel() {
        running?.cancelProcess()
    }

    override fun close() = engine.close()
}
