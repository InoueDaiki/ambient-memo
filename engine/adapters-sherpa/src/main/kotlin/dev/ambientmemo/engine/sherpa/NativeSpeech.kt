package dev.ambientmemo.engine.sherpa

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.Transcription
import dev.ambientmemo.engine.domain.VoiceActivityDetector

private const val MILLIS_PER_SECOND = 1_000L
private const val FULL_SCALE = 32_768f

/** sherpa-onnx の `Vad` のうち、使う部分。jar と AAR の API の違いを隠す。 */
interface NativeVad : AutoCloseable {
    fun acceptWaveform(samples: FloatArray)

    fun isSpeechDetected(): Boolean

    /** 切り出された発話を捨てる。区切りは domain で決めるので使わない。捨てないとネイティブ側に音声が溜まる。 */
    fun dropSegments()

    fun reset()
}

/** sherpa-onnx の `OfflineRecognizer` のうち、使う部分。 */
interface NativeRecognizer : AutoCloseable {
    fun decode(samples: FloatArray, sampleRateHz: Int): String
}

/** [NativeVad] の判定をそのまま発話か無音かにする。スレッドセーフではない。 */
class SherpaVoiceActivityDetector(private val vad: NativeVad) : VoiceActivityDetector, AutoCloseable {
    override fun isSpeech(frame: AudioFrame): Boolean {
        val samples = FloatArray(frame.samples.size) { frame.samples[it] / FULL_SCALE }
        try {
            vad.acceptWaveform(samples)
            vad.dropSegments()
            return vad.isSpeechDetected()
        } finally {
            samples.fill(0f)
        }
    }

    override fun reset() {
        vad.reset()
    }

    override fun close() {
        vad.close()
    }
}

/**
 * 発話の前後に無音を足して文字にする。音声の中身を持ち続けず、作業用の配列は 0 で塗りつぶす。
 *
 * モデルは大きいので、聞き取りの開始ではなく最初の発話で読み込む。読み込みに失敗した発話は、
 * パイプラインが欠落として記録する。ネイティブの処理は止められないので、キャンセルしても途中では戻らない。
 */
class SherpaTranscriber(
    private val paddingMillis: Long = SherpaSettings.PADDING_MILLIS,
    private val createRecognizer: () -> NativeRecognizer,
) : Transcriber, AutoCloseable {
    private val lock = Any()
    private var recognizer: NativeRecognizer? = null
    private var closed = false

    override suspend fun transcribe(segment: SpeechSegment): Transcription {
        val padding = (segment.sampleRateHz * paddingMillis / MILLIS_PER_SECOND).toInt()
        val source = segment.samples
        val samples = FloatArray(padding + source.size + padding)
        source.forEachIndexed { index, sample -> samples[padding + index] = sample / FULL_SCALE }
        try {
            val text = synchronized(lock) { loaded().decode(samples, segment.sampleRateHz) }
            return Transcription(text.trim())
        } finally {
            samples.fill(0f)
        }
    }

    private fun loaded(): NativeRecognizer {
        check(!closed) { "解放済みです" }
        return recognizer ?: createRecognizer().also { recognizer = it }
    }

    /** 処理中の発話があれば、終わるまで待ってから解放する。 */
    override fun close() {
        synchronized(lock) {
            closed = true
            recognizer?.close()
            recognizer = null
        }
    }
}
