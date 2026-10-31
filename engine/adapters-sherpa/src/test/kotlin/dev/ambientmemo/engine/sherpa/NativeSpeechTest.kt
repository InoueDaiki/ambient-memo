@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.sherpa

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class RecordingVad : NativeVad {
    val accepted = mutableListOf<FloatArray>()
    var dropped = 0
    var closed = false

    override fun acceptWaveform(samples: FloatArray) {
        accepted += samples.copyOf()
    }

    override fun isSpeechDetected() = accepted.size % 2 == 0

    override fun dropSegments() {
        dropped++
    }

    override fun reset() = Unit

    override fun close() {
        closed = true
    }
}

private class RecordingRecognizer(private val text: String = " こんにちは ") : NativeRecognizer {
    var received: FloatArray? = null
    var kept: FloatArray? = null
    var closed = false

    override fun decode(samples: FloatArray, sampleRateHz: Int): String {
        received = samples.copyOf()
        kept = samples
        return text
    }

    override fun close() {
        closed = true
    }
}

private class RecordingExtractor(private val vector: FloatArray? = floatArrayOf(0.6f, 0.8f)) : NativeSpeakerExtractor {
    var received: FloatArray? = null
    var kept: FloatArray? = null
    var closed = false

    override fun compute(samples: FloatArray, sampleRateHz: Int): FloatArray? {
        received = samples.copyOf()
        kept = samples
        return vector
    }

    override fun close() {
        closed = true
    }
}

class SherpaVoiceActivityDetectorTest {
    @Test
    fun `サンプルを -1 から 1 に変換して渡す`() {
        val vad = RecordingVad()

        SherpaVoiceActivityDetector(vad).isSpeech(AudioFrame(0, shortArrayOf(Short.MIN_VALUE, 0, 16_384)))

        assertEquals(listOf(-1f, 0f, 0.5f), vad.accepted.single().toList())
    }

    @Test
    fun `フレームごとに切り出された発話を捨てて、ネイティブ側に音声を溜めない`() {
        val vad = RecordingVad()
        val detector = SherpaVoiceActivityDetector(vad)

        repeat(3) { detector.isSpeech(AudioFrame(0, ShortArray(10))) }

        assertEquals(3, vad.dropped)
    }

    @Test
    fun `ネイティブの判定を返す`() {
        val detector = SherpaVoiceActivityDetector(RecordingVad())

        val results = List(2) { detector.isSpeech(AudioFrame(0, ShortArray(10))) }

        assertEquals(listOf(false, true), results)
    }
}

class SherpaTranscriberTest {
    private fun segment(samples: ShortArray) = SpeechSegment(0, 100, TEST_SAMPLE_RATE_HZ, samples)

    @Test
    fun `前後に無音を足して渡し、文字の前後の空白を除く`() = runTest {
        val recognizer = RecordingRecognizer()
        val transcriber = SherpaTranscriber(paddingMillis = 100) { recognizer }

        val result = transcriber.transcribe(segment(ShortArray(4) { 16_384 }))

        val received = checkNotNull(recognizer.received)
        val padding = TEST_SAMPLE_RATE_HZ / 10
        assertEquals("こんにちは", result.text)
        assertEquals(padding + 4 + padding, received.size)
        assertTrue(received.take(padding).all { it == 0f })
        assertEquals(listOf(0.5f, 0.5f, 0.5f, 0.5f), received.drop(padding).take(4))
        assertTrue(received.takeLast(padding).all { it == 0f })
    }

    @Test
    fun `渡した作業用の配列を 0 で塗りつぶす`() = runTest {
        val recognizer = RecordingRecognizer()

        SherpaTranscriber(paddingMillis = 0) { recognizer }.transcribe(segment(ShortArray(4) { 16_384 }))

        assertTrue(checkNotNull(recognizer.kept).all { it == 0f })
    }

    @Test
    fun `モデルは最初の発話まで読み込まず、2回目は読み込み直さない`() = runTest {
        var loads = 0
        val recognizer = RecordingRecognizer()
        val transcriber = SherpaTranscriber {
            loads++
            recognizer
        }
        assertEquals(0, loads)

        repeat(2) { transcriber.transcribe(segment(ShortArray(4))) }

        assertEquals(1, loads)
    }

    @Test
    fun `読み込みの失敗は例外にして、次の発話で読み込み直す`() = runTest {
        var loads = 0
        val failure = IllegalStateException("失敗")
        val transcriber = SherpaTranscriber {
            if (++loads == 1) throw failure
            RecordingRecognizer()
        }

        val thrown = runCatching { transcriber.transcribe(segment(ShortArray(4))) }.exceptionOrNull()
        val second = transcriber.transcribe(segment(ShortArray(4)))

        assertSame(failure, thrown)
        assertEquals("こんにちは", second.text)
    }

    @Test
    fun `閉じるとネイティブを解放し、それ以降は文字起こしできない`() = runTest {
        val recognizer = RecordingRecognizer()
        val transcriber = SherpaTranscriber { recognizer }
        transcriber.transcribe(segment(ShortArray(4)))

        transcriber.close()

        assertTrue(recognizer.closed)
        assertTrue(runCatching { transcriber.transcribe(segment(ShortArray(4))) }.isFailure)
    }

    @Test
    fun `読み込む前に閉じてもネイティブを作らない`() {
        var loads = 0
        SherpaTranscriber {
            loads++
            RecordingRecognizer()
        }.close()

        assertEquals(0, loads)
    }
}

class SherpaSpeakerEmbedderTest {
    private fun segment(samples: ShortArray = ShortArray(4) { 16_384 }) =
        SpeechSegment(0, 100, TEST_SAMPLE_RATE_HZ, samples)

    @Test
    fun `区間全体を -1 から 1 にして渡し、版を付けて返す`() = runTest {
        val extractor = RecordingExtractor()

        val embedding = SherpaSpeakerEmbedder("model-1", minMillis = 0) { extractor }.embed(segment())

        assertEquals(listOf(0.5f, 0.5f, 0.5f, 0.5f), checkNotNull(extractor.received).toList())
        assertEquals("model-1", embedding?.modelVersion)
        assertEquals(listOf(0.6f, 0.8f), embedding?.vector?.toList())
    }

    @Test
    fun `計算できない長さなら null を返す`() = runTest {
        val embedder = SherpaSpeakerEmbedder("model-1", minMillis = 0) { RecordingExtractor(vector = null) }

        assertNull(embedder.embed(segment()))
    }

    @Test
    fun `数でない特徴量は null にする`() = runTest {
        val nan = RecordingExtractor(vector = floatArrayOf(0.6f, Float.NaN))

        assertNull(SherpaSpeakerEmbedder("model-1", minMillis = 0) { nan }.embed(segment()))
    }

    @Test
    fun `短い音声はネイティブへ渡さず、モデルも読み込まない`() = runTest {
        var loads = 0
        val embedder = SherpaSpeakerEmbedder("model-1", minMillis = 400) {
            loads++
            RecordingExtractor()
        }

        assertNull(embedder.embed(segment(ShortArray(TEST_SAMPLE_RATE_HZ * 399 / 1_000))))
        assertEquals(0, loads)

        embedder.embed(segment(ShortArray(TEST_SAMPLE_RATE_HZ * 400 / 1_000)))
        assertEquals(1, loads)
    }

    @Test
    fun `渡した作業用の配列を 0 で塗りつぶす`() = runTest {
        val extractor = RecordingExtractor()

        SherpaSpeakerEmbedder("model-1", minMillis = 0) { extractor }.embed(segment())

        assertTrue(checkNotNull(extractor.kept).all { it == 0f })
    }

    @Test
    fun `例外のときも作業用の配列を 0 で塗りつぶす`() = runTest {
        var kept: FloatArray? = null
        val extractor = object : NativeSpeakerExtractor {
            override fun compute(samples: FloatArray, sampleRateHz: Int): FloatArray? {
                kept = samples
                error("失敗")
            }

            override fun close() = Unit
        }

        val result = runCatching { SherpaSpeakerEmbedder("model-1", minMillis = 0) { extractor }.embed(segment()) }

        assertTrue(result.isFailure)
        assertTrue(checkNotNull(kept).all { it == 0f })
    }

    @Test
    fun `モデルは最初の発話まで読み込まず、閉じると解放する`() = runTest {
        var loads = 0
        val extractor = RecordingExtractor()
        val embedder = SherpaSpeakerEmbedder("model-1", minMillis = 0) {
            loads++
            extractor
        }
        assertEquals(0, loads)

        repeat(2) { embedder.embed(segment()) }
        embedder.close()

        assertEquals(1, loads)
        assertTrue(extractor.closed)
        assertTrue(runCatching { embedder.embed(segment()) }.isFailure)
    }
}
