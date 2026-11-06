package dev.ambientmemo.engine.domain.spec

import dev.ambientmemo.engine.domain.AskConfig
import dev.ambientmemo.engine.domain.AskPhase
import dev.ambientmemo.engine.domain.AskPrompts
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.MemoQuestionAnswerer
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.parseAnswer
import dev.ambientmemo.engine.domain.parseSearchPlan
import dev.ambientmemo.engine.domain.testing.FakeLlm
import dev.ambientmemo.engine.domain.testing.InMemoryMemoRepository
import dev.ambientmemo.engine.domain.testing.isSearchPlanRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// 質問の実行役。メモはメモリの Fake に持ち、LLM は依頼の種類ごとに決めた順で応答する Fake にする

private const val UTTERANCE_MILLIS = 2_000L
private const val UTTERANCE_CONFIDENCE = 0.9

internal fun askParsers(input: JsonObject): JsonElement = when (val op = input.str("op")) {
    "plan" -> obj(
        "plan" to parseSearchPlan(input.str("text"))?.let {
            obj("terms" to it.terms, "from" to it.from?.toString(), "to" to it.to?.toString())
        },
    )
    "answer" -> obj(
        "answer" to parseAnswer(input.str("text"), input.int("sourceCount"))?.let {
            obj("text" to it.text, "citations" to it.citations)
        },
    )
    else -> error("知らない op です: $op")
}

internal fun askPrompts(input: JsonObject): JsonElement = when (val op = input.str("op")) {
    "plan" -> request(AskPrompts.plan(input.str("question"), date(input, "today"), input.strings("tried")))
    "answer" -> request(AskPrompts.answer(input.str("question"), date(input, "today"), input.str("memos")))
    "constants" -> obj("noAnswer" to AskPrompts.NO_ANSWER, "calendarDays" to AskPrompts.CALENDAR_DAYS)
    else -> error("知らない op です: $op")
}

private fun askRequestKind(request: LlmRequest): String = if (request.isSearchPlanRequest) "plan" else "answer"

/** `llm` の形は memo-question.json の inputSchema にある。 */
private fun askingLlm(json: JsonObject?): FakeLlm {
    val values = json ?: JsonObject(emptyMap())
    values.requireKeys(setOf("plans", "answer", "failOn", "probes"), "llm")
    val plans = values.strings("plans")
    val answer = values.strOr("answer", "")
    val failOn = values.strOrNull("failOn")
    var planCalls = 0
    return FakeLlm { _, request ->
        val kind = askRequestKind(request)
        if (kind == failOn) throw SpecFailure("llm-failed")
        if (kind == "plan") plans.getOrElse(planCalls++) { "" } else answer
    }
}

private fun askConfig(json: JsonObject?): AskConfig {
    val d = AskConfig()
    val v = json ?: JsonObject(emptyMap())
    v.requireKeys(
        setOf("maxSearchRounds", "maxSources", "maxContextChars", "candidatesPerTerm", "maxQuotes", "maxQuoteChars"),
        "config",
    )
    return AskConfig(
        maxSearchRounds = v.intOr("maxSearchRounds", d.maxSearchRounds),
        maxSources = v.intOr("maxSources", d.maxSources),
        maxContextChars = v.intOr("maxContextChars", d.maxContextChars),
        candidatesPerTerm = v.intOr("candidatesPerTerm", d.candidatesPerTerm),
        maxQuotes = v.intOr("maxQuotes", d.maxQuotes),
        maxQuoteChars = v.intOr("maxQuoteChars", d.maxQuoteChars),
    )
}

/** 質問の操作を、話者と発話の世界の操作と一緒に流す。 */
private class AskWorld(input: JsonObject) {
    private val world = SpeakerWorld(input)
    private val memos = InMemoryMemoRepository(world.utterances)
    private val llmJson = input.objOrNull("llm")
    private val llm = askingLlm(llmJson)
    private val probes = llmJson?.strings("probes").orEmpty()
    private val answerer = MemoQuestionAnswerer(
        memos,
        world.speakers,
        llm,
        world.clock,
        timeZone(input),
        askConfig(input.objOrNull("config")),
    )

    suspend fun step(step: JsonObject): JsonObject = when (step.str("op")) {
        "memo" -> memo(step)
        "ask" -> ask(step)
        "llmCalls" -> obj(
            "calls" to llm.received.map { request ->
                obj("kind" to askRequestKind(request), "matched" to probes.filter { it in request.user })
            },
        )
        else -> world.stepOrNull(step) ?: error("知らない op です: ${step.str("op")}")
    }

    /** 発話を保存してからメモにする。発話は開始時刻から 2 秒ずつ続ける。 */
    private suspend fun memo(step: JsonObject): JsonObject {
        val start = step.long("startedAtMillis")
        val saved = step.objects("utterances").mapIndexed { index, utterance ->
            val begin = start + index * UTTERANCE_MILLIS
            world.utterances.insert(
                NewUtterance(
                    begin,
                    begin + UTTERANCE_MILLIS,
                    utterance.str("text"),
                    UTTERANCE_CONFIDENCE,
                    begin + UTTERANCE_MILLIS,
                    utterance.longOrNull("speakerId"),
                    null,
                ),
            )
        }
        val memo = NewMemo(
            startedAtMillis = start,
            endedAtMillis = saved.last().endedAtMillis,
            title = step.strOrNull("title"),
            summary = step.strOrNull("summary"),
            modelVersion = "fake-1",
            createdAtMillis = world.clock.nowMillis(),
            utteranceIds = saved.map { it.id },
        )
        return obj("memoId" to checkNotNull(memos.recordMemo(memo)).id)
    }

    private suspend fun ask(step: JsonObject): JsonObject {
        val phases = mutableListOf<AskPhase>()
        return try {
            val answer = answerer.answer(step.str("question")) { phases += it }
            obj(
                "outcome" to answer.outcome,
                "text" to answer.text,
                "sourceIds" to answer.sources.map { it.id },
                "searchRounds" to answer.searchRounds,
                "phases" to phases,
            )
        } catch (_: IllegalArgumentException) {
            errorJson("invalid-argument")
        } catch (e: SpecFailure) {
            obj("failure" to e.kind, "phases" to phases)
        }
    }
}

internal fun memoQuestion(input: JsonObject): JsonElement {
    val world = AskWorld(input)
    var results: List<JsonObject> = emptyList()
    runTest {
        input.objects("setup").forEach { world.step(it) }
        results = input.objects("steps").map { world.step(it) }
    }
    return obj("steps" to results)
}
