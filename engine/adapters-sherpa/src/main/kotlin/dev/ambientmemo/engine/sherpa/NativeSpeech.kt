package dev.ambientmemo.engine.sherpa

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.SpeakerEmbedder
import dev.ambientmemo.engine.domain.SpeakerEmbedding
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.TimedTranscription
import dev.ambientmemo.engine.domain.TranscriptToken
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

/** 認識の結果。[timestampSeconds] は渡した音声の先頭からの、[tokens] の開始時刻。 */
class NativeRecognition(
    val text: String,
    val tokens: List<String>,
    val timestampSeconds: List<Float>,
)

/** sherpa-onnx の `OfflineRecognizer` のうち、使う部分。 */
interface NativeRecognizer : AutoCloseable {
    fun decode(samples: FloatArray, sampleRateHz: Int): NativeRecognition
}

/** sherpa-onnx の `SpeakerEmbeddingExtractor` のうち、使う部分。 */
interface NativeSpeakerExtractor : AutoCloseable {
    /** 特徴量を返す。音声が短すぎて計算できないときは null を返す。ストリームは内側で解放する。 */
    fun compute(samples: FloatArray, sampleRateHz: Int): FloatArray?
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

    override suspend fun transcribe(segment: SpeechSegment): TimedTranscription {
        val padding = (segment.sampleRateHz * paddingMillis / MILLIS_PER_SECOND).toInt()
        val source = segment.samples
        val samples = FloatArray(padding + source.size + padding)
        source.forEachIndexed { index, sample -> samples[padding + index] = sample / FULL_SCALE }
        try {
            val recognition = synchronized(lock) { loaded().decode(samples, segment.sampleRateHz) }
            return TimedTranscription(Transcription(recognition.text.trim()), tokensOf(recognition))
        } finally {
            samples.fill(0f)
        }
    }

    /** 時刻は足した無音のぶんを引いて、区間の先頭からにする。数が揃わなければ時刻がないものとして扱う。 */
    private fun tokensOf(recognition: NativeRecognition): List<TranscriptToken> {
        if (recognition.tokens.size != recognition.timestampSeconds.size) return emptyList()
        return recognition.tokens.zip(recognition.timestampSeconds) { text, seconds ->
            TranscriptToken(text, ((seconds * MILLIS_PER_SECOND).toLong() - paddingMillis).coerceAtLeast(0))
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

/**
 * 発話区間全体から1つの特徴量を作る。作業用の配列は 0 で塗りつぶす。
 *
 * [minMillis] より短い音声はネイティブへ渡さず null を返す。ごく短い音声では、CI の Linux の x64 で
 * `isReady` が true のまま数でない値が返った。Mac の arm64 では起きなかったので、値も確かめる。
 *
 * 読み込みとキャンセルの扱いは [SherpaTranscriber] と同じ。自分の声が登録されるまで呼ばれないので、モデルも読み込まない。
 */
class SherpaSpeakerEmbedder(
    override val modelVersion: String,
    private val minMillis: Long = SherpaSettings.MIN_SPEAKER_MILLIS,
    private val createExtractor: () -> NativeSpeakerExtractor,
) : SpeakerEmbedder, AutoCloseable {
    private val lock = Any()
    private var extractor: NativeSpeakerExtractor? = null
    private var closed = false

    override suspend fun embed(segment: SpeechSegment): SpeakerEmbedding? {
        val source = segment.samples
        if (source.size * MILLIS_PER_SECOND < minMillis * segment.sampleRateHz) return null
        val samples = FloatArray(source.size) { source[it] / FULL_SCALE }
        try {
            val vector = synchronized(lock) { loaded().compute(samples, segment.sampleRateHz) }
            return vector?.takeIf { it.all(Float::isFinite) }?.let { SpeakerEmbedding(it, modelVersion) }
        } finally {
            samples.fill(0f)
        }
    }

    private fun loaded(): NativeSpeakerExtractor {
        check(!closed) { "解放済みです" }
        return extractor ?: createExtractor().also { extractor = it }
    }

    /** 処理中の発話があれば、終わるまで待ってから解放する。 */
    override fun close() {
        synchronized(lock) {
            closed = true
            extractor?.close()
            extractor = null
        }
    }
}
