package dev.ambientmemo.engine.domain.spec

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.AudioSource
import dev.ambientmemo.engine.domain.CaptureGapRepository
import dev.ambientmemo.engine.domain.CapturePipeline
import dev.ambientmemo.engine.domain.DiagnosticEvent
import dev.ambientmemo.engine.domain.EnrollmentConfig
import dev.ambientmemo.engine.domain.EnrollmentOutcome
import dev.ambientmemo.engine.domain.EnrollmentProgress
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.NoiseTextFilter
import dev.ambientmemo.engine.domain.SegmentProcessed
import dev.ambientmemo.engine.domain.SelfEnrollment
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.TimedTranscription
import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.TranscriptToken
import dev.ambientmemo.engine.domain.Transcription
import dev.ambientmemo.engine.domain.TranscriptionFilter
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.domain.UtteranceRepository
import dev.ambientmemo.engine.domain.testing.FakeAudioSource
import dev.ambientmemo.engine.domain.testing.InMemoryCaptureGapRepository
import dev.ambientmemo.engine.domain.testing.ScriptedVoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.CopyOnWriteArrayList

// 音声から保存までの流れと、自分の声の登録の実行役

/** 読んだフレームを数える音声。`audio` の形は capture-pipeline.json の inputSchema にある。 */
private class SpecAudioSource(json: JsonObject) : AudioSource {
    override val sampleRateHz = TEST_SAMPLE_RATE_HZ
    var framesRead = 0
    private val endless = json.objOrNull("endless")
    private val isFailingAtEnd = json.boolOr("failAtEnd", false)
    private val fake = if (endless == null) fakeSource(json) else null

    override fun frames(): Flow<AudioFrame> = flow {
        if (endless != null) {
            val silence = SyntheticAudio.silence(endless.int("frameMillis"))
            while (true) {
                // 読み取りを止められたフレームも数える
                framesRead++
                emit(AudioFrame((framesRead - 1L) * endless.long("frameMillis"), silence))
            }
        }
        checkNotNull(fake).frames().collect {
            framesRead++
            emit(it)
        }
        if (isFailingAtEnd) throw SpecFailure("source-failed")
    }

    private fun fakeSource(json: JsonObject): FakeAudioSource {
        val samples = json.objects("pieces").map { piece ->
            piece.longOrNull("tone")?.let { SyntheticAudio.tone(it.toInt()) }
                ?: SyntheticAudio.silence(piece.int("silence"))
        }.fold(ShortArray(0)) { all, piece -> all + piece }
        val jumps = json.objects("jumps").map { it.int("fromSample") to it.long("offsetMillis") }
        return FakeAudioSource(
            samples,
            startMillis = json.longOr("startMillis", 0),
            frameStartMillis = { position, default ->
                default + jumps.filter { (from, _) -> position >= from }.sumOf { (_, offset) -> offset }
            },
        )
    }
}

private fun vad(input: JsonObject) = ScriptedVoiceActivityDetector(
    input.getValue("vad").jsonArray.map { range ->
        val (start, end) = range.jsonArray.map { it.jsonPrimitive.long }
        start..<end
    },
)

/** 呼ばれた順の番号で応答を決める ASR の Fake。`responses` にない番号は「発話N」を返す。 */
private class ScriptedTranscriber(json: JsonObject?) : Transcriber {
    private val responses = json?.objects("responses").orEmpty()
    val received: MutableList<SpeechSegment> = CopyOnWriteArrayList()
    val hadSound: MutableList<Boolean> = CopyOnWriteArrayList()

    override suspend fun transcribe(segment: SpeechSegment): TimedTranscription {
        val index = received.size
        received += segment
        hadSound += segment.samples.any { it != 0.toShort() }
        val response = responses.getOrNull(index)
        if (response?.boolOr("fail", false) == true) throw SpecFailure("transcribe-failed")
        val text = response?.strOrNull("text") ?: "発話${index + 1}"
        val transcription = Transcription(text, response?.doubleOrNull("confidence"))
        val durationMillis = segment.endedAtMillis - segment.startedAtMillis
        val tokens = when (val given = response?.get("tokens")) {
            null -> text.mapIndexed { i, c -> TranscriptToken(c.toString(), i * durationMillis / text.length) }
            is JsonPrimitive -> emptyList<TranscriptToken>().also { check(given.content == "none") }
            else -> given.jsonArray.map(::token)
        }
        return TimedTranscription(transcription, tokens)
    }
}

private fun filter(json: JsonObject?): TranscriptionFilter {
    val texts = json?.strings("texts").orEmpty().toSet()
    return when (val type = json?.str("type") ?: "all") {
        "all" -> TranscriptionFilter { _, _ -> true }
        "none" -> TranscriptionFilter { _, _ -> false }
        "rejectTexts" -> TranscriptionFilter { text, _ -> text !in texts }
        "onlyTexts" -> TranscriptionFilter { text, _ -> text in texts }
        "noise" -> NoiseTextFilter(json?.longOrNull("longDurationMillis") ?: 2_000)
        else -> error("知らない filter です: $type")
    }
}

/** 指定した回の insertAll で失敗する。 */
private class FailingUtterances(
    private val delegate: UtteranceRepository,
    private val failingCalls: Set<Int>,
) : UtteranceRepository by delegate {
    private var calls = 0

    override suspend fun insertAll(utterances: List<NewUtterance>): List<Utterance> {
        val call = calls++
        if (call in failingCalls) throw SpecFailure("utterance-insert-failed")
        return delegate.insertAll(utterances)
    }
}

private class FailingGaps(delegate: CaptureGapRepository) : CaptureGapRepository by delegate {
    override suspend fun insert(gap: dev.ambientmemo.engine.domain.CaptureGap) = throw SpecFailure("gap-insert-failed")
}

internal fun capturePipeline(input: JsonObject): JsonElement {
    val speakerInput = input.objOrNull("speakers")
    val world = SpeakerWorld(
        JsonObject(speakerInput.orEmpty() + ("clockMillis" to JsonPrimitive(input.long("clockMillis")))),
    )
    val gaps = InMemoryCaptureGapRepository()
    val detector = vad(input)
    val transcriber = ScriptedTranscriber(input.objOrNull("transcriber"))
    val events = CopyOnWriteArrayList<DiagnosticEvent>()
    val failures = input.objOrNull("failures")
    val failingInserts = failures?.get("utteranceInsertAll")?.jsonArray?.map { it.jsonPrimitive.content.toInt() }
    val pipeline = CapturePipeline(
        audioSource = SpecAudioSource(input.getValue("audio").jsonObject),
        voiceActivityDetector = detector,
        transcriber = transcriber,
        utterances = failingInserts?.let { FailingUtterances(world.utterances, it.toSet()) } ?: world.utterances,
        captureGaps = if (failures?.boolOr("gapInsert", false) == true) FailingGaps(gaps) else gaps,
        clock = world.clock,
        config = captureConfig(input.objOrNull("captureConfig")),
        transcriptionFilter = filter(input.objOrNull("filter")),
        speakerIdentifier = speakerInput?.let { world.identifier },
        diagnostics = events::add,
    )
    var failure: String? = null
    var parts = JsonObject(emptyMap())
    runTest {
        speakerInput?.let { world.setUp(it) }
        try {
            repeat(input.intOr("runs", 1)) { pipeline.run() }
        } catch (e: SpecFailure) {
            failure = e.kind
        }
        val include = input.strings("include")
        val worldParts = world.step(obj("op" to "snapshot", "include" to include.filter { it in WORLD_PARTS }))
        val own = include.filterNot { it in WORLD_PARTS }.associateWith { part ->
            when (part) {
                "gaps" -> json(gaps.listOverlapping(Long.MIN_VALUE, Long.MAX_VALUE).map(::gapJson))
                "transcriberCalls" -> json(
                    transcriber.received.mapIndexed { index, segment ->
                        val hadSound = JsonPrimitive(transcriber.hadSound[index])
                        JsonObject(segmentCallJson(segment) + ("hadSound" to hadSound))
                    },
                )
                "allTranscriberReleased" -> json(transcriber.received.all { it.isReleased })
                "vadResets" -> json(detector.resetCount)
                "segments" -> json(events.filterIsInstance<SegmentProcessed>().map(::segmentEventJson))
                else -> error("知らない include です: $part")
            }
        }
        parts = JsonObject(worldParts + own)
    }
    return JsonObject(mapOf("failure" to json(failure)) + parts)
}

private val WORLD_PARTS = setOf("speakers", "embeddings", "adaptation", "utterances", "embedderCalls", "diagnostics")

/** 処理の時間は実行環境で変わるので比べない。待ちの時間は Fake の時計で決まるので比べる。 */
private fun segmentEventJson(event: SegmentProcessed) = obj(
    "segmentMillis" to event.segmentMillis,
    "waitMillis" to event.waitMillis,
    "textLength" to event.textLength,
    "turns" to event.turns,
    "outcome" to event.outcome,
)

internal fun selfEnrollment(input: JsonObject): JsonElement {
    val world = SpeakerWorld(input)
    val source = SpecAudioSource(input.getValue("audio").jsonObject)
    val progress = mutableListOf<EnrollmentProgress>()
    val enrollment = SelfEnrollment(
        audioSource = source,
        voiceActivityDetector = vad(input),
        embedder = world.embedder,
        speakers = world.speakers,
        clock = world.clock,
        captureConfig = captureConfig(input.objOrNull("captureConfig")),
        config = enrollmentConfig(input.objOrNull("enrollmentConfig")),
        onProgress = progress::add,
    )
    var outcome: EnrollmentOutcome? = null
    var failure: String? = null
    var state = JsonObject(emptyMap())
    runTest {
        world.setUp(input)
        try {
            outcome = enrollment.run()
        } catch (e: SpecFailure) {
            failure = e.kind
        }
        state = world.step(obj("op" to "snapshot", "include" to listOf("speakers", "embeddings", "embedderCalls")))
    }
    return JsonObject(
        mapOf(
            "outcome" to json(outcome),
            "failure" to json(failure),
            "progress" to json(
                progress.map { obj("acceptedSamples" to it.acceptedSamples, "isRejected" to it.isRejected) },
            ),
            "framesRead" to json(source.framesRead),
        ) + state,
    )
}

private fun enrollmentConfig(json: JsonObject?): EnrollmentConfig {
    val default = EnrollmentConfig()
    val values = json ?: JsonObject(emptyMap())
    values.requireKeys(setOf("requiredSamples", "minSampleMillis", "maxListeningMillis"), "enrollmentConfig")
    return EnrollmentConfig(
        requiredSamples = values.intOr("requiredSamples", default.requiredSamples),
        minSampleMillis = values.longOr("minSampleMillis", default.minSampleMillis),
        maxListeningMillis = values.longOr("maxListeningMillis", default.maxListeningMillis),
    )
}
