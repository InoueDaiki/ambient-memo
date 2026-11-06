package dev.ambientmemo.engine.domain.spec

import dev.ambientmemo.engine.domain.InMemoryOrganizeAttempts
import dev.ambientmemo.engine.domain.LlmInputTooLongException
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.Memo
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.OrganizeJob
import dev.ambientmemo.engine.domain.OrganizeProgress
import dev.ambientmemo.engine.domain.organizeBacklog
import dev.ambientmemo.engine.domain.testing.FakeLlm
import dev.ambientmemo.engine.domain.testing.InMemoryMemoRepository
import dev.ambientmemo.engine.domain.testing.isTitleRequest
import dev.ambientmemo.engine.domain.testing.isTopicRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// 整理ジョブの実行役。記録はメモリの Fake に持ち、LLM は依頼の種類と本文で応答を決める Fake にする

private const val SAY_MILLIS = 2_000L
private const val SAY_CONFIDENCE = 0.9

/** 依頼の種類。FakeLlm.organizing と同じ順で見分ける。 */
private fun requestKind(request: LlmRequest): String = when {
    request.isTopicRequest -> "topics"
    request.isTitleRequest -> "title"
    else -> "summary"
}

/** `llm` の `rules` の 1 件。書いた条件をすべて満たす最初の規則で応答を決める。 */
private class LlmRule(json: JsonObject) {
    private val kind = json.strOrNull("kind")
    private val contains = json.strings("contains")
    private val nthOfKind = json.intOrNull("nthOfKind")
    private val calls = json["calls"]?.takeUnless { it is JsonNull }?.jsonArray?.map { it.jsonPrimitive.int }?.toSet()
    private val reply = json.strOrNull("reply")
    private val failure = json.strOrNull("failure")

    init {
        json.requireKeys(setOf("kind", "contains", "nthOfKind", "calls", "reply", "failure"), "rules")
        require((reply == null) != (failure == null)) { "rules は reply か failure のどちらかを書きます" }
    }

    fun matches(request: LlmRequest, kind: String, nth: Int, call: Int): Boolean =
        (this.kind == null || this.kind == kind) &&
            contains.all { it in request.user } &&
            (nthOfKind == null || nthOfKind == nth) &&
            (calls == null || call in calls)

    fun respond(): String = when (failure) {
        null -> checkNotNull(reply)
        "failed" -> throw SpecFailure("llm-failed")
        "too-long" -> throw LlmInputTooLongException()
        else -> error("知らない failure です: $failure")
    }
}

private fun JsonObject.intOrNull(key: String): Int? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.int

/** `llm` の形は organize-job.json の inputSchema にある。 */
private fun scriptedLlm(json: JsonObject?): FakeLlm {
    val values = json ?: JsonObject(emptyMap())
    values.requireKeys(
        setOf("modelVersion", "summary", "title", "topics", "rules", "probes"),
        "llm",
    )
    val summary = values.strOr("summary", "- 要約")
    val title = values.strOr("title", summary)
    val topics = values.strOr("topics", "1")
    val rules = values.objects("rules").map(::LlmRule)
    val kindCounts = mutableMapOf<String, Int>()
    return FakeLlm(values.strOr("modelVersion", "fake-1")) { index, request ->
        val kind = requestKind(request)
        val nth = kindCounts.getOrDefault(kind, 0)
        kindCounts[kind] = nth + 1
        rules.firstOrNull { it.matches(request, kind, nth, index) }?.respond() ?: when (kind) {
            "topics" -> topics
            "title" -> title
            else -> summary
        }
    }
}

/** 整理の操作を、話者と発話の世界の操作と一緒に流す。 */
private class OrganizeWorld(input: JsonObject) {
    private val world = SpeakerWorld(input)
    private val memos = InMemoryMemoRepository(world.utterances)
    private val llmJson = input.objOrNull("llm")
    private val llm = scriptedLlm(llmJson)
    private val probes = llmJson?.strings("probes").orEmpty()
    private val zone = timeZone(input)
    private val config = organizeConfig(input.objOrNull("config"))
    private val attempts = InMemoryOrganizeAttempts()
    private val job = OrganizeJob(memos, world.speakers, llm, world.clock, zone, config, attempts)

    suspend fun step(step: JsonObject): JsonObject = when (step.str("op")) {
        "say" -> say(step)
        "run" -> run(step)
        "setAttempts" -> obj().also { attempts.set(step.long("utteranceId"), step.int("count")) }
        "attempts" -> obj("count" to attempts.count(step.long("utteranceId")))
        "backlog" -> organizeBacklog(memos, world.speakers.listSpeakers(), world.clock, zone, config).let {
            obj("readyChunks" to it.readyChunks, "hasOngoing" to it.hasOngoing)
        }
        "records" -> obj(
            "memos" to memos.listMemos().map { memoJson(it, memos) },
            "unorganized" to memos.listUnorganizedUtterances().map { it.id },
        )
        "llmCalls" -> obj(
            "calls" to llm.received.map { request ->
                obj("kind" to requestKind(request), "matched" to probes.filter { it in request.user })
            },
        )
        else -> world.stepOrNull(step) ?: error("知らない op です: ${step.str("op")}")
    }

    private suspend fun say(step: JsonObject): JsonObject {
        val start = step.long("startedAtMillis")
        val end = start + step.longOr("durationMillis", SAY_MILLIS)
        val utterance = NewUtterance(
            start,
            end,
            step.str("text"),
            SAY_CONFIDENCE,
            end,
            step.longOrNull("speakerId"),
            step.doubleOrNull("speakerConfidence"),
        )
        return obj("utteranceId" to world.utterances.insert(utterance).id)
    }

    private suspend fun run(step: JsonObject): JsonObject {
        val checks = step.intOrNull("continueChecks") ?: Int.MAX_VALUE
        var checked = 0
        val progress = mutableListOf<OrganizeProgress>()
        val result = job.run(shouldContinue = { ++checked <= checks }, onProgress = { progress += it })
        return obj(
            "result" to obj(
                "outcome" to result.outcome,
                "memoCount" to result.memoCount,
                "unreadableCount" to result.unreadableCount,
            ),
            "progress" to progress.map { listOf(it.completedChunks, it.totalChunks) },
        )
    }
}

internal fun organizeJob(input: JsonObject): JsonElement {
    val world = OrganizeWorld(input)
    var results: List<JsonObject> = emptyList()
    runTest {
        input.objects("setup").forEach { world.step(it) }
        results = input.objects("steps").map { world.step(it) }
    }
    return obj("steps" to results)
}

private suspend fun memoJson(memo: Memo, memos: InMemoryMemoRepository) = obj(
    "id" to memo.id,
    "startedAtMillis" to memo.startedAtMillis,
    "endedAtMillis" to memo.endedAtMillis,
    "title" to memo.title,
    "summary" to memo.summary,
    "modelVersion" to memo.modelVersion,
    "createdAtMillis" to memo.createdAtMillis,
    "utteranceIds" to memos.listUtteranceIds(memo.id),
)
