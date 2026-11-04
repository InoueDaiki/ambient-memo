package dev.ambientmemo.engine.domain.spec

import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.RecentVoices
import dev.ambientmemo.engine.domain.SelfAdaptation
import dev.ambientmemo.engine.domain.SpeakerConfig
import dev.ambientmemo.engine.domain.SpeakerConsolidation
import dev.ambientmemo.engine.domain.SpeakerCorrections
import dev.ambientmemo.engine.domain.SpeakerDiagnosticsReader
import dev.ambientmemo.engine.domain.SpeakerEmbedding
import dev.ambientmemo.engine.domain.SpeakerIdentifier
import dev.ambientmemo.engine.domain.SpeakerTarget
import dev.ambientmemo.engine.domain.SpeakerTurn
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.TimedTranscription
import dev.ambientmemo.engine.domain.TranscriptToken
import dev.ambientmemo.engine.domain.Transcription
import dev.ambientmemo.engine.domain.TurnVoice
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeSpeakerEmbedder
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerDecisionLog
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerRepository
import dev.ambientmemo.engine.domain.testing.InMemoryUtteranceRepository
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// 話者の記録をメモリに持つ世界。話者の判定、まとめ直し、手の修正、声の登録、聞き取りの流れが共有する

private const val MILLIS_PER_SECOND = 1_000L
private const val LISTED_EMBEDDINGS = 100
private const val DEFAULT_MODEL = "fake-1"

/** JSON の `embedder` から、呼ばれた順の番号で応答を決める Fake を作る。 */
internal fun scriptedEmbedder(json: JsonObject?, defaultModel: String): FakeSpeakerEmbedder {
    val model = json?.strOrNull("modelVersion") ?: defaultModel
    val responses = json?.get("responses")?.jsonArray ?: return FakeSpeakerEmbedder(model)
    val isCycling = json.boolOr("cycle", false)
    return FakeSpeakerEmbedder(model) { index, _ ->
        val response = if (isCycling) responses[index % responses.size] else responses[index]
        when {
            response is JsonNull -> null
            response is JsonPrimitive && response.content == "fail" -> throw SpecFailure("embed-failed")
            else -> response.toVector()
        }
    }
}

/** 書いていない項目は既定値にする。 */
internal fun speakerConfig(json: JsonObject?): SpeakerConfig {
    val d = SpeakerConfig()
    val v = json ?: JsonObject(emptyMap())
    val f = { key: String, default: Float -> v[key]?.jsonPrimitive?.content?.toFloat() ?: default }
    val l = { key: String, default: Long -> v[key]?.jsonPrimitive?.long ?: default }
    val i = { key: String, default: Int -> v[key]?.jsonPrimitive?.content?.toInt() ?: default }
    val config = SpeakerConfig(
        selfThreshold = f("selfThreshold", d.selfThreshold),
        selfExitThreshold = f("selfExitThreshold", d.selfExitThreshold),
        unknownFloor = f("unknownFloor", d.unknownFloor),
        provisionalThreshold = f("provisionalThreshold", d.provisionalThreshold),
        maxProvisionalSpeakers = i("maxProvisionalSpeakers", d.maxProvisionalSpeakers),
        minNewSpeakerRunMillis = l("minNewSpeakerRunMillis", d.minNewSpeakerRunMillis),
        staleProvisionalMillis = l("staleProvisionalMillis", d.staleProvisionalMillis),
        consolidationThreshold = f("consolidationThreshold", d.consolidationThreshold),
        strongSelfThreshold = f("strongSelfThreshold", d.strongSelfThreshold),
        rivalMargin = f("rivalMargin", d.rivalMargin),
        selfOverrideMargin = f("selfOverrideMargin", d.selfOverrideMargin),
        adaptMinScore = f("adaptMinScore", d.adaptMinScore),
        adaptMinEnrolledScore = f("adaptMinEnrolledScore", d.adaptMinEnrolledScore),
        adaptRivalMargin = f("adaptRivalMargin", d.adaptRivalMargin),
        adaptMinRunMillis = l("adaptMinRunMillis", d.adaptMinRunMillis),
        adaptMaxBoost = f("adaptMaxBoost", d.adaptMaxBoost),
        adaptBaseMillis = l("adaptBaseMillis", d.adaptBaseMillis),
        adaptMaxMillis = l("adaptMaxMillis", d.adaptMaxMillis),
        windowMillis = l("windowMillis", d.windowMillis),
        hopMillis = l("hopMillis", d.hopMillis),
        trailingSilenceMillis = l("trailingSilenceMillis", d.trailingSilenceMillis),
        minWindowMillis = l("minWindowMillis", d.minWindowMillis),
        minReliableMillis = l("minReliableMillis", d.minReliableMillis),
        minReliableRunMillis = l("minReliableRunMillis", d.minReliableRunMillis),
        maxComparedSpeakers = i("maxComparedSpeakers", d.maxComparedSpeakers),
    )
    v.requireKeys(SPEAKER_CONFIG_KEYS, "speakerConfig")
    return config
}

private val SPEAKER_CONFIG_KEYS = setOf(
    "selfThreshold", "selfExitThreshold", "unknownFloor", "provisionalThreshold", "maxProvisionalSpeakers",
    "minNewSpeakerRunMillis", "staleProvisionalMillis", "consolidationThreshold", "strongSelfThreshold",
    "rivalMargin", "selfOverrideMargin", "adaptMinScore", "adaptMinEnrolledScore", "adaptRivalMargin",
    "adaptMinRunMillis", "adaptMaxBoost", "adaptBaseMillis", "adaptMaxMillis", "windowMillis", "hopMillis",
    "trailingSilenceMillis", "minWindowMillis", "minReliableMillis", "minReliableRunMillis", "maxComparedSpeakers",
)

/**
 * 話者の記録と、それを使う処理をまとめて持つ。[input] の `modelVersion`、`clockMillis`、`speakerConfig`、
 * `recentVoicesCapacity`、`embedder` から作る。
 */
internal class SpeakerWorld(input: JsonObject) {
    val model = input.strOr("modelVersion", DEFAULT_MODEL)
    val clock = FakeClock(input.longOr("clockMillis", 0))
    val config = speakerConfig(input.objOrNull("speakerConfig"))
    val utterances = InMemoryUtteranceRepository()
    val speakers = InMemorySpeakerRepository(utterances)
    val voices = input.longOrNull("recentVoicesCapacity")?.let { RecentVoices(it.toInt()) } ?: RecentVoices()
    val embedder = scriptedEmbedder(input.objOrNull("embedder"), model)
    private val log = InMemorySpeakerDecisionLog()
    val identifier = SpeakerIdentifier(embedder, speakers, clock, config, log)
    private val corrections = SpeakerCorrections(speakers, utterances, voices, model, clock, config)

    /** `setup` を結果を見ずに流す。 */
    suspend fun setUp(input: JsonObject) {
        input.objects("setup").forEach { step(it) }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // 操作の一覧をそのまま並べている
    suspend fun step(step: JsonObject): JsonObject = when (val op = step.str("op")) {
        "enrollSelf" -> obj(
            "speakerId" to speakers.saveSelfEmbedding(embedding(step), step.intOr("sampleCount", 3), now(step)).id,
        )
        "createProvisional" -> obj(
            "speakerId" to speakers
                .createProvisionalSpeaker(embedding(step), now(step), step.longOr("voicedMillis", 0))
                .id,
        )
        "setManualName" -> obj("ok" to speakers.setManualName(step.long("speakerId"), step.str("name")))
        "setInferredName" -> obj("ok" to speakers.setInferredName(step.long("speakerId"), step.str("name")))
        "saveSelfAdaptation" -> obj().also {
            val vector = step.getValue("vector").toVector()
            speakers.saveSelfAdaptation(SelfAdaptation(vector, step.long("voicedMillis"), modelOf(step)), now(step))
        }
        "insertUtterance" -> obj("utteranceId" to utterances.insert(newUtterance(step)).id)
        "advanceClock" -> obj().also { clock.advanceBy(step.long("millis")) }
        "rememberVoice" -> obj().also {
            val voice = TurnVoice(step.getValue("vector").toVector(), step.long("voicedMillis"))
            voices.remember(step.long("utteranceId"), voice)
        }
        "takeVoice" -> obj("voice" to voices.take(step.long("utteranceId"))?.let(::voiceJson))
        "identify" -> identify(step)
        "consolidate" -> obj("merged" to SpeakerConsolidation(speakers, model, clock, config).run())
        "reassign" -> obj("ok" to corrections.reassign(step.long("utteranceId"), target(step.getValue("target"))))
        "merge" -> obj("ok" to corrections.merge(step.long("fromId"), step.long("intoId")))
        "rename" -> obj("ok" to corrections.rename(step.long("speakerId"), step.str("name")))
        "snapshot" -> snapshot(step.strings("include"))
        else -> error("知らない op です: $op")
    }

    private fun now(step: JsonObject) = step.longOr("nowMillis", 0)

    private fun modelOf(step: JsonObject) = step.strOr("modelVersion", model)

    private fun embedding(step: JsonObject) = SpeakerEmbedding(step.getValue("vector").toVector(), modelOf(step))

    private fun newUtterance(step: JsonObject) = NewUtterance(
        startedAtMillis = step.longOr("startedAtMillis", 0),
        endedAtMillis = step.longOr("endedAtMillis", 1),
        text = step.strOr("text", "本文"),
        confidence = step.doubleOrNull("confidence"),
        createdAtMillis = step.longOr("createdAtMillis", 1),
        speakerId = step.longOrNull("speakerId"),
        speakerConfidence = if (step.containsKey("speakerConfidence")) step.doubleOrNull("speakerConfidence") else 0.5,
    )

    private fun target(json: JsonElement): SpeakerTarget = when {
        json is JsonObject -> SpeakerTarget.Existing(json.long("existing"))
        json.jsonPrimitive.content == "self" -> SpeakerTarget.Self
        json.jsonPrimitive.content == "unknown" -> SpeakerTarget.Unknown
        json.jsonPrimitive.content == "newOther" -> SpeakerTarget.NewOther
        else -> error("知らない target です: $json")
    }

    /** 区間の音声は 0 で、特徴量は `embedder` の応答で決まる。`save` なら聞き取りと同じく保存し、声を覚える。 */
    private suspend fun identify(step: JsonObject): JsonObject {
        val repeat = step.intOr("repeat", 1)
        val calls = List(repeat) { identifyOnce(step) }
        return if (step.containsKey("repeat")) {
            obj("speakerIds" to calls.map { turns -> turns.single().speaker?.speakerId })
        } else {
            obj("turns" to calls.single().map(::turnJson))
        }
    }

    private suspend fun identifyOnce(step: JsonObject): List<SpeakerTurn> {
        val start = step.longOr("startedAtMillis", 0)
        val millis = step.long("millis")
        val samples = step.intOr("samples", (millis * TEST_SAMPLE_RATE_HZ / MILLIS_PER_SECOND).toInt())
        val segment = SpeechSegment(start, start + millis, TEST_SAMPLE_RATE_HZ, ShortArray(samples))
        val tokens = step["tokens"]?.jsonArray?.map(::token).orEmpty()
        val text = Transcription(step.strOr("text", "本文"), step.doubleOrNull("confidence"))
        val transcription = TimedTranscription(text, tokens)
        val turns = segment.use { identifier.identify(it, transcription) }
        if (step.boolOr("save", false)) {
            val saved = utterances.insertAll(turns.map { it.toNewUtterance(transcription, clock.nowMillis()) })
            saved.zip(turns).forEach { (row, turn) -> turn.voice?.let { voices.remember(row.id, it) } }
        }
        return turns
    }

    private suspend fun snapshot(include: List<String>): JsonObject {
        val parts = include.associateWith { part ->
            when (part) {
                "speakers" -> json(speakers.listSpeakers().map(::speakerJson))
                "embeddings" -> json(speakers.listEmbeddings(model, LISTED_EMBEDDINGS).map(::embeddingJson))
                "adaptation" -> json(
                    speakers.findSelfAdaptation(model)
                        ?.let { obj("vector" to it.vector, "voicedMillis" to it.voicedMillis) },
                )
                "utterances" -> json(utterances.listOverlapping(Long.MIN_VALUE, Long.MAX_VALUE).map(::utteranceJson))
                "embedderCalls" -> json(embedder.received.map(::segmentCallJson))
                "diagnostics" -> diagnosticsJson()
                else -> error("知らない include です: $part")
            }
        }
        return JsonObject(parts)
    }

    private suspend fun diagnosticsJson(): JsonObject {
        val read = SpeakerDiagnosticsReader(log, speakers, model, clock, config).read()
        return obj(
            "days" to read.days,
            "selfRuns" to read.selfRuns,
            "otherRuns" to read.otherRuns,
            "unknownRuns" to read.unknownRuns,
            "selfMillis" to read.selfMillis,
            "otherMillis" to read.otherMillis,
            "unknownMillis" to read.unknownMillis,
            "histogram" to read.histogram.map {
                obj(
                    "floor" to it.floor,
                    "selfRuns" to it.selfRuns,
                    "otherRuns" to it.otherRuns,
                    "unknownRuns" to it.unknownRuns,
                )
            },
            "provisionalSpeakers" to read.provisionalSpeakers,
            "hasAdaptedVoice" to read.hasAdaptedVoice,
        )
    }
}

/** 区間ごとに world を作って流す。 */
internal fun speakerWorld(input: JsonObject): JsonElement {
    val world = SpeakerWorld(input)
    var results: List<JsonObject> = emptyList()
    runTest {
        world.setUp(input)
        results = input.objects("steps").map { world.step(it) }
    }
    return obj("steps" to results)
}

internal fun token(json: JsonElement): TranscriptToken {
    val (text, offset) = json as JsonArray
    return TranscriptToken(text.jsonPrimitive.content, offset.jsonPrimitive.long)
}

internal fun SpeakerTurn.toNewUtterance(transcription: TimedTranscription, createdAtMillis: Long) = NewUtterance(
    startedAtMillis = startedAtMillis,
    endedAtMillis = endedAtMillis,
    text = text,
    confidence = transcription.transcription.confidence,
    createdAtMillis = createdAtMillis,
    speakerId = speaker?.speakerId,
    speakerConfidence = speaker?.confidence,
)

private fun turnJson(turn: SpeakerTurn) = obj(
    "startedAtMillis" to turn.startedAtMillis,
    "endedAtMillis" to turn.endedAtMillis,
    "text" to turn.text,
    "speakerId" to turn.speaker?.speakerId,
    "confidence" to turn.speaker?.confidence,
    "voice" to turn.voice?.let(::voiceJson),
)

private fun voiceJson(voice: TurnVoice) = obj("vector" to voice.vector, "voicedMillis" to voice.voicedMillis)

internal fun speakerJson(speaker: dev.ambientmemo.engine.domain.Speaker) = obj(
    "id" to speaker.id,
    "displayName" to speaker.displayName,
    "nameSource" to speaker.nameSource,
    "isSelf" to speaker.isSelf,
)

internal fun embeddingJson(stored: dev.ambientmemo.engine.domain.StoredSpeakerEmbedding) = obj(
    "speakerId" to stored.speakerId,
    "isSelf" to stored.isSelf,
    "vector" to stored.vector,
    "sampleCount" to stored.sampleCount,
    "voicedMillis" to stored.voicedMillis,
    "updatedAtMillis" to stored.updatedAtMillis,
)

internal fun utteranceJson(row: dev.ambientmemo.engine.domain.Utterance) = obj(
    "id" to row.id,
    "startedAtMillis" to row.startedAtMillis,
    "endedAtMillis" to row.endedAtMillis,
    "text" to row.text,
    "confidence" to row.confidence,
    "createdAtMillis" to row.createdAtMillis,
    "speakerId" to row.speakerId,
    "speakerConfidence" to row.speakerConfidence,
)

internal fun segmentCallJson(segment: SpeechSegment) = obj(
    "startedAtMillis" to segment.startedAtMillis,
    "endedAtMillis" to segment.endedAtMillis,
    "sampleCount" to segment.sampleCount,
    "released" to segment.isReleased,
)
