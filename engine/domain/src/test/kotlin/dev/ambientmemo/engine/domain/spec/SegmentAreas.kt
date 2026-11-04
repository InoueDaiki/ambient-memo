package dev.ambientmemo.engine.domain.spec

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.SegmentBacklog
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.UtteranceSegmenter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// 発話の切り出しと、文字起こし待ちの置き場所の実行役

private const val MILLIS_PER_SECOND = 1_000L

internal fun utteranceSegmenter(input: JsonObject): JsonElement {
    val sampleRateHz = input.int("sampleRateHz")
    val frameMillis = input.long("frameMillis")
    val segmenter = UtteranceSegmenter(captureConfig(input.objOrNull("config")), sampleRateHz)
    val stride = input.intOr("probeStride", 0)
    var position = 0L
    val segmentJson = { segment: SpeechSegment -> segmentJson(segment, stride) }
    val results = input.objects("steps").map { step ->
        when (val op = step.str("op")) {
            "feed" -> {
                val segments = (0 until step.long("millis") / frameMillis).flatMap {
                    // フレームの音の値は、フレームの位置から決める。何番目のフレームの音かを確かめるため
                    val value = (position / frameMillis + 1).toInt().toShort()
                    val samples = ShortArray((frameMillis * sampleRateHz / MILLIS_PER_SECOND).toInt()) { value }
                    val frame = AudioFrame(position, samples)
                    position += frameMillis
                    segmenter.accept(frame, step.bool("isSpeech"))
                }
                obj("segments" to segments.map(segmentJson))
            }
            "frame" -> {
                val samples = ShortArray(step.int("samples")) { it.toShort() }
                val segments = segmenter.accept(AudioFrame(position, samples), step.bool("isSpeech"))
                position += samples.size * MILLIS_PER_SECOND / sampleRateHz
                obj("segments" to segments.map(segmentJson))
            }
            "flush" -> obj("segment" to segmenter.flush()?.let(segmentJson))
            "discard" -> obj(
                "lost" to segmenter.discard()?.let {
                    obj("startedAtMillis" to it.startedAtMillis, "endedAtMillis" to it.endedAtMillis)
                },
            )
            "seek" -> obj().also { position = step.long("positionMillis") }
            else -> error("知らない op です: $op")
        }
    }
    return obj("steps" to results)
}

private fun segmentJson(segment: SpeechSegment, stride: Int): JsonObject {
    val samples = segment.samples
    return obj(
        "startedAtMillis" to segment.startedAtMillis,
        "endedAtMillis" to segment.endedAtMillis,
        "sampleCount" to segment.sampleCount,
        "first" to samples.firstOrNull()?.toInt(),
        "last" to samples.lastOrNull()?.toInt(),
        "probes" to if (stride > 0) samples.indices.step(stride).map { samples[it].toInt() } else emptyList(),
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun segmentBacklog(input: JsonObject): JsonElement {
    val backlog = SegmentBacklog(input.long("maxSamples"))
    val offered = mutableMapOf<Long, SpeechSegment>()
    val results = input.objects("steps").map { step ->
        when (val op = step.str("op")) {
            "offer" -> {
                val start = step.long("startedAtMillis")
                val samples = step.int("samples")
                // 標本化周波数は 1 kHz にして、標本の数をそのままミリ秒にする
                val segment = SpeechSegment(start, start + samples, 1_000, ShortArray(samples) { 1 })
                offered[step.long("id")] = segment
                backlog.offer(segment)
                obj()
            }
            "poll" -> obj("id" to backlog.poll()?.let { polled -> offered.entries.single { it.value === polled }.key })
            "drainGaps" -> obj("gaps" to backlog.drainGaps().map(::gapJson))
            "addGap" -> obj().also { backlog.addGap(step.getValue("gap").let { it as JsonObject }.toGap()) }
            "abandonAll" -> obj().also { backlog.abandonAll(step.enum("reason")) }
            "close" -> obj().also { backlog.close() }
            "awaitWork" -> {
                var result: Boolean? = null
                runTest {
                    val waiting = async { backlog.awaitWork() }
                    runCurrent()
                    check(waiting.isCompleted) { "awaitWork が待ち続けました。すぐに返るときだけ使います" }
                    result = waiting.await()
                }
                obj("result" to result)
            }
            else -> error("知らない op です: $op")
        }
    }
    return obj("steps" to results, "released" to offered.filterValues { it.isReleased }.keys.sorted())
}
