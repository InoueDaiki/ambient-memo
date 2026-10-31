package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.CaptureConfig
import dev.ambientmemo.engine.domain.CaptureGap
import dev.ambientmemo.engine.domain.CaptureGapReason
import dev.ambientmemo.engine.domain.CapturePipeline
import dev.ambientmemo.engine.domain.Transcription
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeTranscriber
import dev.ambientmemo.engine.domain.testing.ScriptedVoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import dev.ambientmemo.engine.domain.testing.WavAudioSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** WAV の入力から本物の SQLite への保存までを、ASR と VAD だけ Fake にして通す。 */
class WavToSqliteIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val base = 1_700_000_000_000L
    private val config = CaptureConfig(
        maxUtteranceMillis = 1_000,
        minSilenceMillis = 200,
        preRollMillis = 100,
        minSpeechMillis = 100,
    )

    /** 0.4 秒の無音、0.5 秒の発話、0.4 秒の無音、1.5 秒の発話、0.4 秒の無音。 */
    private fun writeWav(): Path {
        val audio = SyntheticAudio.silence(400) + SyntheticAudio.tone(500) + SyntheticAudio.silence(400) +
            SyntheticAudio.tone(1_500) + SyntheticAudio.silence(400)
        return dir.resolve("speech.wav").also { Files.write(it, SyntheticAudio.wav(audio)) }
    }

    private fun detector() = ScriptedVoiceActivityDetector(
        listOf(base + 400..<base + 900, base + 1_300..<base + 2_800),
    )

    @Test
    fun `発話を検知して、分割した文字起こしを SQLite に保存する`() = runTest {
        val transcriber = FakeTranscriber { index, segment ->
            check(segment.samples.any { it != 0.toShort() }) { "音声が届いていません" }
            Transcription("発話${index + 1}", 0.9)
        }

        openTestDriver(dir.resolve("test.db")).use { driver ->
            val database = AmbientMemoDatabase(driver)
            val utterances = SqliteUtteranceRepository(database)
            val gaps = SqliteCaptureGapRepository(database)

            CapturePipeline(
                audioSource = WavAudioSource(writeWav(), FakeClock(base)),
                voiceActivityDetector = detector(),
                transcriber = transcriber,
                utterances = utterances,
                captureGaps = gaps,
                clock = FakeClock(base + 10_000),
                config = config,
            ).run()

            val saved = utterances.listOverlapping(base, base + 10_000)
            assertEquals(listOf("発話1", "発話2", "発話3"), saved.map { it.text })
            assertEquals(listOf(300L, 1_200L, 2_200L), saved.map { it.startedAtMillis - base })
            assertEquals(listOf(900L, 2_200L, 2_800L), saved.map { it.endedAtMillis - base })
            assertTrue(gaps.listOverlapping(base, base + 10_000).isEmpty())
            assertTrue(transcriber.received.all { it.isReleased })
        }
    }

    @Test
    fun `失敗した発話は欠落として SQLite に残る`() = runTest {
        val transcriber = FakeTranscriber { index, _ ->
            if (index == 0) throw IOException("失敗")
            Transcription("発話${index + 1}")
        }

        openTestDriver(dir.resolve("test.db")).use { driver ->
            val database = AmbientMemoDatabase(driver)
            val gaps = SqliteCaptureGapRepository(database)

            CapturePipeline(
                audioSource = WavAudioSource(writeWav(), FakeClock(base)),
                voiceActivityDetector = detector(),
                transcriber = transcriber,
                utterances = SqliteUtteranceRepository(database),
                captureGaps = gaps,
                clock = FakeClock(base + 10_000),
                config = config,
            ).run()

            assertEquals(
                listOf(CaptureGap(base + 300, base + 900, CaptureGapReason.PROCESSING_FAILED)),
                gaps.listOverlapping(base, base + 10_000),
            )
        }
    }
}
