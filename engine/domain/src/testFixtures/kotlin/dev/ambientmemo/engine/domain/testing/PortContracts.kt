@file:Suppress("FunctionNaming", "MagicNumber") // テスト名は日本語の文にする

package dev.ambientmemo.engine.domain.testing

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.AudioSource
import dev.ambientmemo.engine.domain.Clock
import dev.ambientmemo.engine.domain.Llm
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.SpeakerEmbedder
import dev.ambientmemo.engine.domain.SpeechSegment
import dev.ambientmemo.engine.domain.Transcriber
import dev.ambientmemo.engine.domain.VoiceActivityDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

// ここの契約テストは今の Fake に流す。sherpa-onnx などの本物を足すときは、同じ抽象クラスを継承して流す

private class CopiedFrame(val startedAtMillis: Long, val samples: ShortArray)

private suspend fun Flow<AudioFrame>.copyAll(): List<CopiedFrame> {
    val frames = mutableListOf<CopiedFrame>()
    onEach { frames += CopiedFrame(it.startedAtMillis, it.samples.copyOf()) }.collect()
    return frames
}

private fun segment(samples: ShortArray = SyntheticAudio.tone(500)) = SpeechSegment(
    startedAtMillis = 0,
    endedAtMillis = samples.size * 1_000L / TEST_SAMPLE_RATE_HZ,
    sampleRateHz = TEST_SAMPLE_RATE_HZ,
    samples = samples,
)

/** 有限の音声を流せる [AudioSource] の契約テスト。 */
abstract class AudioSourceContract {
    /** [samples] を流し終えると完了するソースを作る。 */
    protected abstract fun source(samples: ShortArray): AudioSource

    private val samples = SyntheticAudio.tone(500)

    @Test
    fun `サンプルを欠かさず順番に流す`() = runTest {
        val frames = source(samples).frames().copyAll()

        val delivered = frames.flatMap { it.samples.asIterable() }.toShortArray()
        assertArrayEquals(samples, delivered)
    }

    @Test
    fun `空のフレームを流さない`() = runTest {
        assertTrue(source(samples).frames().copyAll().all { it.samples.isNotEmpty() })
    }

    @Test
    fun `フレームの時刻は前のフレームの終わりと連続する`() = runTest {
        val source = source(samples)

        val frames = source.frames().copyAll()

        frames.zipWithNext { current, next ->
            val expected = current.startedAtMillis + current.samples.size * 1_000L / source.sampleRateHz
            assertTrue(abs(next.startedAtMillis - expected) <= 1, "${next.startedAtMillis} と $expected")
        }
    }

    @Test
    fun `collect するたびに最初から流す`() = runTest {
        val source = source(samples)

        val first = source.frames().copyAll()
        val second = source.frames().copyAll()

        assertEquals(first.sumOf { it.samples.size }, second.sumOf { it.samples.size })
    }
}

abstract class ClockContract {
    protected abstract fun clock(): Clock

    @Test
    fun `時刻は続けて読んでも戻らない`() {
        val clock = clock()

        val first = clock.nowMillis()
        val second = clock.nowMillis()

        assertTrue(second >= first)
    }
}

/** 与えた音声のうち、[speechMillis] の範囲を発話と判定する検出器の契約テスト。 */
abstract class VoiceActivityDetectorContract {
    protected abstract fun detector(speechMillis: LongRange): VoiceActivityDetector

    /** 発話の音声。音声の中身を見る本物の実装は、本物の発話に差し替える。 */
    protected open fun speech(): ShortArray = SyntheticAudio.tone(600)

    private fun AudioFrame.copy() = AudioFrame(startedAtMillis, samples.copyOf())

    private suspend fun results(detector: VoiceActivityDetector, audio: ShortArray): List<Boolean> {
        val results = mutableListOf<Boolean>()
        FakeAudioSource(audio).frames().onEach { results += detector.isSpeech(it.copy()) }.collect()
        return results
    }

    private fun speechRange(speech: ShortArray): LongRange {
        val endMillis = LEADING_SILENCE_MILLIS + speech.size * 1_000L / TEST_SAMPLE_RATE_HZ
        return LEADING_SILENCE_MILLIS.toLong()..<endMillis
    }

    /** 発話の前後に無音を置いた音声。 */
    private fun audio(speech: ShortArray) =
        SyntheticAudio.silence(LEADING_SILENCE_MILLIS) + speech + SyntheticAudio.silence(TRAILING_SILENCE_MILLIS)

    @Test
    fun `発話の時間帯を発話と判定する`() = runTest {
        val speech = speech()

        val results = results(detector(speechRange(speech)), audio(speech))

        assertTrue(results.any { it })
        assertFalse(results.first())
        assertFalse(results.last())
    }

    @Test
    fun `無音を発話と判定しない`() = runTest {
        val results = results(detector(LongRange.EMPTY), SyntheticAudio.silence(1_000))

        assertFalse(results.any { it })
    }

    @Test
    fun `reset すると同じ音声で同じ結果になる`() = runTest {
        val speech = speech()
        val detector = detector(speechRange(speech))

        val first = results(detector, audio(speech))
        detector.reset()
        val second = results(detector, audio(speech))

        assertEquals(first, second)
    }

    private companion object {
        const val LEADING_SILENCE_MILLIS = 200
        const val TRAILING_SILENCE_MILLIS = 1_000
    }
}

abstract class TranscriberContract {
    protected abstract fun transcriber(): Transcriber

    /** 発話の音声。音声の中身を見る本物の実装は、本物の発話に差し替える。 */
    protected open fun speech(): ShortArray = SyntheticAudio.tone(500)

    @Test
    fun `発話の音声を文字にする`() = runTest {
        val transcription = transcriber().transcribe(segment(speech()))

        assertTrue(transcription.text.isNotBlank())
    }

    @Test
    fun `トークンは区間の中で時刻の順に並び、つなげると本文になる`() = runTest {
        val segment = segment(speech())

        val transcription = transcriber().transcribe(segment)

        val tokens = transcription.tokens
        val offsets = tokens.map { it.offsetMillis }
        val joined = tokens.joinToString("") { it.text }
        assertTrue(tokens.isNotEmpty())
        assertEquals(transcription.text.filterNot(Char::isWhitespace), joined.filterNot(Char::isWhitespace))
        assertEquals(offsets.sorted(), offsets)
        assertTrue(offsets.all { it in 0..segment.endedAtMillis }, offsets.toString())
    }

    @Test
    fun `バッファの解放は呼び出し側に任せる`() = runTest {
        val segment = segment(speech())

        transcriber().transcribe(segment)

        assertFalse(segment.isReleased)
    }

    @Test
    fun `渡された音声を書き換えない`() = runTest {
        val samples = speech()
        val segment = segment(samples.copyOf())

        transcriber().transcribe(segment)

        assertArrayEquals(samples, segment.samples)
    }
}

abstract class SpeakerEmbedderContract {
    protected abstract fun embedder(): SpeakerEmbedder

    /** 発話の音声。音声の中身を見る本物の実装は、本物の発話に差し替える。 */
    protected open fun speech(): ShortArray = SyntheticAudio.tone(500)

    @Test
    fun `同じ音声から同じ特徴量と版を返す`() = runTest {
        val embedder = embedder()

        val first = checkNotNull(embedder.embed(segment(speech())))
        val second = checkNotNull(embedder.embed(segment(speech())))

        assertTrue(first.vector.isNotEmpty())
        assertTrue(first.vector.all { it.isFinite() })
        assertEquals(embedder.modelVersion, first.modelVersion)
        assertTrue(first.modelVersion.isNotBlank())
        assertArrayEquals(first.vector, second.vector)
        assertEquals(first.modelVersion, second.modelVersion)
    }

    @Test
    fun `バッファの解放は呼び出し側に任せる`() = runTest {
        val segment = segment(speech())

        embedder().embed(segment)

        assertFalse(segment.isReleased)
    }

    @Test
    fun `渡された音声を書き換えない`() = runTest {
        val samples = speech()
        val segment = segment(samples.copyOf())

        embedder().embed(segment)

        assertArrayEquals(samples, segment.samples)
    }
}

/** [Llm] の契約テスト。 */
abstract class LlmContract {
    protected abstract fun llm(): Llm

    private val request = LlmRequest("あなたは整理係です。", "「はい」とだけ答えてください。", maxOutputTokens = 16)

    @Test
    fun `モデルの版を持つ`() {
        assertTrue(llm().modelVersion.isNotBlank())
    }

    @Test
    fun `同じインスタンスで続けて生成できる`() = runTest {
        val llm = llm()

        llm.generate(request)
        llm.generate(request)
    }
}
