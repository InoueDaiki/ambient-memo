@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.CapturePipeline
import dev.ambientmemo.engine.domain.NoiseTextFilter
import dev.ambientmemo.engine.domain.QuietGatedVoiceActivityDetector
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import dev.ambientmemo.engine.domain.testing.TEST_FRAME_SAMPLES
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import dev.ambientmemo.engine.domain.testing.WavAudioSource
import dev.ambientmemo.engine.sherpa.ModelCatalog
import dev.ambientmemo.engine.sherpa.SherpaSettings
import dev.ambientmemo.engine.sherpa.jvm.jvmTranscriber
import dev.ambientmemo.engine.sherpa.jvm.jvmVoiceActivityDetector
import dev.ambientmemo.engine.sherpa.testing.TestModels
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.math.abs

/** 静かな間を飛ばしたときに、区間の端がずれてよい幅。 */
private const val MAX_EDGE_SHIFT_MILLIS = 500L

/** WAV から本物の VAD、ASR、SQLite まで通す。モデルがなければ飛ばす。 */
class RealSpeechToSqliteIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val base = 1_700_000_000_000L
    private val horizon = base + 600_000

    private var runs = 0

    private fun transcribe(samples: ShortArray, isGated: Boolean = false): List<Utterance> = runBlocking {
        val models = ModelCatalog.ACTIVE
        val store = TestModels.storeFor(models)
        val wav = SyntheticAudio.wav(samples)
        openTestDriver(dir.resolve("test-${runs++}.db")).use { driver ->
            val database = AmbientMemoDatabase(driver)
            val utterances = SqliteUtteranceRepository(database)
            jvmVoiceActivityDetector(store, models).use { vad ->
                jvmTranscriber(store, models).use { transcriber ->
                    CapturePipeline(
                        audioSource = WavAudioSource(
                            { ByteArrayInputStream(wav) },
                            FakeClock(base),
                            frameSamples = TEST_FRAME_SAMPLES * 10,
                        ),
                        voiceActivityDetector = if (isGated) QuietGatedVoiceActivityDetector(vad) else vad,
                        transcriber = transcriber,
                        utterances = utterances,
                        captureGaps = SqliteCaptureGapRepository(database),
                        clock = FakeClock(horizon),
                        config = SherpaSettings.captureConfig(),
                        transcriptionFilter = NoiseTextFilter(),
                    ).run()
                }
            }
            utterances.listOverlapping(base, horizon)
        }
    }

    private fun silence(seconds: Int) = SyntheticAudio.silence(seconds * 1_000)

    @Test
    fun `無音で区切られた 2 つの発話を文字にして保存する`() {
        val speech = TestModels.speech()

        val saved = transcribe(silence(1) + speech + silence(2) + speech + silence(2))

        assertEquals(2, saved.size)
        saved.forEach { assertTrue("ヤンバルクイナ" in it.text, it.text) }
        assertTrue(saved[0].endedAtMillis <= saved[1].startedAtMillis)
    }

    @Test
    fun `前後の音声が語頭と語尾を残し、WAV の時間帯に収まる`() {
        val speech = TestModels.speech()
        val speechMillis = speech.size * 1_000L / TEST_SAMPLE_RATE_HZ

        val utterance = transcribe(silence(1) + speech + silence(2)).single()

        assertTrue("ヤンバルクイナ" in utterance.text, utterance.text)
        assertTrue(utterance.text.endsWith("だった"), utterance.text)
        assertTrue(utterance.startedAtMillis >= base, "${utterance.startedAtMillis - base}")
        assertTrue(utterance.endedAtMillis - base <= 1_000 + speechMillis + 2_000)
    }

    @Test
    fun `20 秒より長い発話を分割し、音声を落とさない`() {
        val speech = TestModels.speech()
        var long = silence(1)
        repeat(6) { long += speech }

        val saved = transcribe(long + silence(2))

        assertTrue(saved.size >= 2, "${saved.size}")
        saved.forEach { assertTrue(it.endedAtMillis - it.startedAtMillis <= 20_000) }
        saved.zipWithNext { current, next -> assertTrue(current.endedAtMillis <= next.startedAtMillis) }
    }

    /** 部屋の雑音の代わり。-70 dBFS ほどの小さな揺れで、静かな間として飛ばされる。 */
    private fun hiss(seconds: Int) = ShortArray(seconds * TEST_SAMPLE_RATE_HZ) { if (it % 2 == 0) 10 else -10 }

    private fun scaled(samples: ShortArray, factor: Double) =
        ShortArray(samples.size) { (samples[it] * factor).toInt().toShort() }

    /**
     * 飛ばしたあとに VAD の状態を捨てるので、発話の判定が少し変わる。
     * 区間の前に音声を足すので語頭は落ちず、文字は同じになる。区間の端は 0.1〜0.3 秒ずれる。
     */
    @Test
    fun `静かな間を VAD に渡さなくても、同じ発話を同じ文字で保存する`() {
        val speech = TestModels.speech()
        val audio = hiss(3) + speech + hiss(4) + speech + hiss(3)

        val plain = transcribe(audio)
        val gated = transcribe(audio, isGated = true)

        assertEquals(2, gated.size)
        assertEquals(plain.map { it.text }, gated.map { it.text })
        plain.zip(gated).forEach { (expected, actual) ->
            assertTrue(abs(expected.startedAtMillis - actual.startedAtMillis) <= MAX_EDGE_SHIFT_MILLIS)
            assertTrue(abs(expected.endedAtMillis - actual.endedAtMillis) <= MAX_EDGE_SHIFT_MILLIS)
        }
    }

    @Test
    fun `離れた小さな声も、静かな間として飛ばさずに保存する`() {
        // 元の音声より 20 dB 小さい。語頭の聞き取りは飛ばさなくても変わるので、飛ばさないときと比べる
        val audio = hiss(2) + scaled(TestModels.speech(), 0.1) + hiss(3)

        val plain = transcribe(audio)
        val gated = transcribe(audio, isGated = true)

        assertEquals(1, gated.size)
        assertEquals(plain.map { it.text }, gated.map { it.text })
    }

    @Test
    fun `無音だけなら何も保存しない`() {
        assertTrue(transcribe(silence(3)).isEmpty())
    }
}
