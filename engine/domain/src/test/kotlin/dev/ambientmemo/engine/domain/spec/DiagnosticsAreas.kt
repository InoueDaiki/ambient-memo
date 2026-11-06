package dev.ambientmemo.engine.domain.spec

import dev.ambientmemo.engine.domain.AskFinished
import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.AudioLevelCounter
import dev.ambientmemo.engine.domain.AudioLevelSummary
import dev.ambientmemo.engine.domain.CaptureChanged
import dev.ambientmemo.engine.domain.CaptureGap
import dev.ambientmemo.engine.domain.CaptureGapRecorded
import dev.ambientmemo.engine.domain.DeviceSample
import dev.ambientmemo.engine.domain.DiagnosticEvent
import dev.ambientmemo.engine.domain.LlmBackendChosen
import dev.ambientmemo.engine.domain.LlmGenerated
import dev.ambientmemo.engine.domain.LlmLoaded
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.OrganizeFinished
import dev.ambientmemo.engine.domain.QuietGatedVoiceActivityDetector
import dev.ambientmemo.engine.domain.RecordingCaptureGaps
import dev.ambientmemo.engine.domain.SegmentProcessed
import dev.ambientmemo.engine.domain.TimedLlm
import dev.ambientmemo.engine.domain.VoiceActivityDetector
import dev.ambientmemo.engine.domain.encodeDiagnostic
import dev.ambientmemo.engine.domain.levelDbfs
import dev.ambientmemo.engine.domain.testing.FakeLlm
import dev.ambientmemo.engine.domain.testing.InMemoryCaptureGapRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

// 静かな間の VAD の省略と、動作の記録の実行役

private const val DEFAULT_FRAME_SAMPLES = 160

private fun constantFrame(amplitude: Int, samples: Int) = AudioFrame(0, ShortArray(samples) { amplitude.toShort() })

internal fun quietGate(input: JsonObject): JsonElement = when (val op = input.str("op")) {
    "level" -> obj(
        "dbfs" to constantFrame(input.int("amplitude"), input.intOr("samples", DEFAULT_FRAME_SAMPLES)).levelDbfs(),
    )
    "gate" -> runGate(input)
    else -> error("知らない op です: $op")
}

/** 渡されたフレームと reset を数え、フレームごとに指定された判定を返す VAD。 */
private class CountingDetector : VoiceActivityDetector {
    var speaking = false
    var frames = 0
    var resets = 0

    override fun isSpeech(frame: AudioFrame): Boolean {
        frames++
        return speaking
    }

    override fun reset() {
        resets++
    }
}

private fun runGate(input: JsonObject): JsonElement {
    val delegate = CountingDetector()
    val levels = AudioLevelCounter()
    val gate = input.doubleOrNull("quietDbfs")
        ?.let { QuietGatedVoiceActivityDetector(delegate, it, levels) }
        ?: QuietGatedVoiceActivityDetector(delegate, levels = levels)
    val results = input.objects("steps").map { step ->
        when (val type = step.str("op")) {
            "frame" -> {
                delegate.speaking = step.bool("delegateSpeech")
                val frame = constantFrame(step.int("amplitude"), step.intOr("samples", DEFAULT_FRAME_SAMPLES))
                obj("isSpeech" to gate.isSpeech(frame))
            }
            "reset" -> obj().also { gate.reset() }
            "drain" -> obj("summary" to levels.drain()?.let(::levelSummary))
            else -> error("知らない op です: $type")
        }
    }
    return obj("steps" to results, "delegateFrames" to delegate.frames, "delegateResets" to delegate.resets)
}

private fun levelSummary(summary: AudioLevelSummary) = obj(
    "frames" to summary.frames,
    "skippedFrames" to summary.skippedFrames,
    "histogram" to summary.histogram.map { (band, count) -> listOf(band, count) },
)

internal fun diagnostics(input: JsonObject): JsonElement = when (val op = input.str("op")) {
    "encode" -> obj(
        "line" to encodeDiagnostic(input.long("atMillis"), input.enum("process"), event(input.obj("event"))),
    )
    "timed-llm" -> timedLlm(input)
    "recording-gaps" -> recordingGaps(input)
    else -> error("知らない op です: $op")
}

private fun JsonObject.obj(key: String): JsonObject = getValue(key) as JsonObject

private fun event(json: JsonObject): DiagnosticEvent = when (val type = json.str("type")) {
    "segment" -> SegmentProcessed(
        json.long("segmentMillis"),
        json.long("waitMillis"),
        json.long("transcribeMillis"),
        json.long("saveMillis"),
        json.int("textLength"),
        json.int("turns"),
        json.enum("outcome"),
    )
    "gap" -> CaptureGapRecorded(json.enum("reason"), json.long("gapMillis"))
    "capture" -> CaptureChanged(json.bool("isListening"), json.bool("isInterrupted"))
    "llm_backend" -> LlmBackendChosen(json.enum("backend"))
    "llm_load" -> LlmLoaded(json.long("loadMillis"), json.bool("isLoaded"))
    "llm_generate" -> LlmGenerated(
        json.int("inputChars"),
        json.int("maxOutputTokens"),
        json.int("outputChars"),
        json.long("generateMillis"),
        json.bool("isFailed"),
    )
    "organize" -> OrganizeFinished(
        json.enum("outcome"),
        json.int("memoCount"),
        json.int("unreadableCount"),
        json.long("totalMillis"),
    )
    "ask" -> AskFinished(json.enum("outcome"), json.int("searchRounds"), json.int("sourceCount"), json.long("totalMillis"))
    "device" -> deviceSample(json)
    "audio_level" -> AudioLevelSummary(
        json.int("frames"),
        json.int("skippedFrames"),
        json.getValue("histogram").jsonArray.map { pair ->
            val (band, count) = (pair as JsonArray).map { it.jsonPrimitive.int }
            band to count
        },
    )
    else -> error("知らない type です: $type")
}

private fun deviceSample(json: JsonObject) = DeviceSample(
    batteryPercent = json.int("batteryPercent"),
    chargeMicroAmpereHours = json.long("chargeMicroAmpereHours"),
    temperatureDeciCelsius = json.int("temperatureDeciCelsius"),
    isCharging = json.bool("isCharging"),
    thermalStatus = json.int("thermalStatus"),
    isScreenOn = json.bool("isScreenOn"),
    isDeviceIdle = json.bool("isDeviceIdle"),
    isPowerSave = json.bool("isPowerSave"),
    elapsedMillis = json.long("elapsedMillis"),
    uptimeMillis = json.long("uptimeMillis"),
    processCpuMillis = json.long("processCpuMillis"),
    rssKilobytes = json.long("rssKilobytes"),
    peakRssKilobytes = json.long("peakRssKilobytes"),
)

/** 生成の時間は実行環境で変わるので比べない。 */
private fun timedLlm(input: JsonObject): JsonElement {
    val events = mutableListOf<DiagnosticEvent>()
    val reply = input.strOrNull("reply")
    val llm = TimedLlm(FakeLlm { _, _ -> reply ?: throw SpecFailure("generate-failed") }, events::add)
    var output: String? = null
    var isRethrown = false
    runTest {
        try {
            output = llm.generate(LlmRequest(input.str("system"), input.str("user"), input.int("maxOutputTokens")))
        } catch (_: SpecFailure) {
            isRethrown = true
        }
    }
    return obj(
        "output" to output,
        "rethrown" to isRethrown,
        "events" to events.map { event ->
            val generated = event as LlmGenerated
            obj(
                "inputChars" to generated.inputChars,
                "maxOutputTokens" to generated.maxOutputTokens,
                "outputChars" to generated.outputChars,
                "isFailed" to generated.isFailed,
            )
        },
    )
}

private fun recordingGaps(input: JsonObject): JsonElement {
    val events = mutableListOf<DiagnosticEvent>()
    val gaps = InMemoryCaptureGapRepository()
    val gap = input.obj("gap").toGap()
    runTest { RecordingCaptureGaps(gaps, events::add).insert(gap) }
    val saved = mutableListOf<CaptureGap>()
    runTest { saved += gaps.listOverlapping(Long.MIN_VALUE, Long.MAX_VALUE) }
    return obj(
        "saved" to saved.map(::gapJson),
        "events" to events.map { event ->
            val recorded = event as CaptureGapRecorded
            obj("reason" to recorded.reason, "gapMillis" to recorded.gapMillis)
        },
    )
}

internal fun JsonObject.toGap() = CaptureGap(long("startedAtMillis"), long("endedAtMillis"), enum("reason"))

internal fun gapJson(gap: CaptureGap) =
    obj("startedAtMillis" to gap.startedAtMillis, "endedAtMillis" to gap.endedAtMillis, "reason" to gap.reason)

/** Fake が投げる、種類の名前の付いた失敗。実行役はこれを捕まえて種類を結果に書く。 */
internal class SpecFailure(val kind: String) : java.io.IOException(kind)
