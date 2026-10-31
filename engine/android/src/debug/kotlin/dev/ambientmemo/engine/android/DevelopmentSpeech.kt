package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.Transcription
import dev.ambientmemo.engine.domain.VoiceActivityDetector
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val MILLIS_PER_SECOND = 1_000L
private const val DEFAULT_SAMPLE_RATE_HZ = 16_000
private const val DEFAULT_PERIOD_MILLIS = 8_000L
private val DEFAULT_SPEECH_MILLIS = 2_000L..<4_000L

/**
 * 音声の中身を見ず、読み取り開始からの時間で周期的に発話と判定する。
 *
 * マイクの入らないエミュレーターでも、発話の検知から保存までを確かめるための開発用の実装。
 */
internal class DevelopmentVoiceActivityDetector(
    private val speechMillis: LongRange = DEFAULT_SPEECH_MILLIS,
    private val periodMillis: Long = DEFAULT_PERIOD_MILLIS,
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
) : VoiceActivityDetector {
    private var samplesSinceReset = 0L

    override fun isSpeech(frame: AudioFrame): Boolean {
        val middleMillis = (samplesSinceReset + frame.samples.size / 2) * MILLIS_PER_SECOND / sampleRateHz
        samplesSinceReset += frame.samples.size
        return middleMillis % periodMillis in speechMillis
    }

    override fun reset() {
        samplesSinceReset = 0
    }
}

/** 発話の長さだけを書いた、開発用と分かる文字列を返す。音声の中身は読まない。 */
internal class DevelopmentTranscriber : Transcriber {
    private val count = AtomicInteger()

    override suspend fun transcribe(segment: SpeechSegment): Transcription {
        val seconds = (segment.endedAtMillis - segment.startedAtMillis) / MILLIS_PER_SECOND.toDouble()
        val text = String.format(Locale.ROOT, "[開発用] 発話 %d(%.1f 秒)", count.incrementAndGet(), seconds)
        return Transcription(text)
    }
}
