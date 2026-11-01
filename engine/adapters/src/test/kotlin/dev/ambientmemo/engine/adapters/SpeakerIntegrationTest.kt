@file:Suppress("FunctionNaming", "MagicNumber") // テスト名は日本語の文にする

package dev.ambientmemo.engine.adapters

import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.CaptureConfig
import dev.ambientmemo.engine.domain.CapturePipeline
import dev.ambientmemo.engine.domain.EnrollmentConfig
import dev.ambientmemo.engine.domain.EnrollmentOutcome
import dev.ambientmemo.engine.domain.NoiseTextFilter
import dev.ambientmemo.engine.domain.OrganizeJob
import dev.ambientmemo.engine.domain.OrganizeOutcome
import dev.ambientmemo.engine.domain.SelfEnrollment
import dev.ambientmemo.engine.domain.SpeakerConfig
import dev.ambientmemo.engine.domain.SpeakerEmbedder
import dev.ambientmemo.engine.domain.SpeakerEmbedding
import dev.ambientmemo.engine.domain.SpeakerIdentifier
import dev.ambientmemo.engine.domain.TranscriptToken
import dev.ambientmemo.engine.domain.Transcription
import dev.ambientmemo.engine.domain.Utterance
import dev.ambientmemo.engine.domain.VoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeLlm
import dev.ambientmemo.engine.domain.testing.FakeSpeakerEmbedder
import dev.ambientmemo.engine.domain.testing.FakeTranscriber
import dev.ambientmemo.engine.domain.testing.ScriptedVoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import dev.ambientmemo.engine.domain.testing.TEST_FRAME_SAMPLES
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import dev.ambientmemo.engine.domain.testing.WavAudioSource
import dev.ambientmemo.engine.sherpa.ModelCatalog
import dev.ambientmemo.engine.sherpa.SherpaSettings
import dev.ambientmemo.engine.sherpa.jvm.jvmSpeakerEmbedder
import dev.ambientmemo.engine.sherpa.jvm.jvmTranscriber
import dev.ambientmemo.engine.sherpa.jvm.jvmVoiceActivityDetector
import dev.ambientmemo.engine.sherpa.testing.TestModels
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.TimeZone
import kotlin.math.sqrt

private const val BASE_MILLIS = 1_700_000_000_000L
private const val HORIZON_MILLIS = BASE_MILLIS + 600_000

private fun wavSource(samples: ShortArray): WavAudioSource {
    val wav = SyntheticAudio.wav(samples)
    return WavAudioSource({ ByteArrayInputStream(wav) }, FakeClock(BASE_MILLIS), frameSamples = TEST_FRAME_SAMPLES * 10)
}

/** 登録から一覧までを、本物の SQLite に固定のベクトルをつないで通す。 */
class SpeakerToSqliteIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val config = CaptureConfig(
        maxUtteranceMillis = 1_000,
        minSilenceMillis = 200,
        preRollMillis = 0,
        minSpeechMillis = 0,
    )
    private val self = floatArrayOf(1f, 0f, 0f)
    private val other = floatArrayOf(0f, 1f, 0f)

    /** 0.5 秒の発話と 0.5 秒の無音を [count] 回くり返す。 */
    private fun bursts(count: Int) = (0 until count).fold(ShortArray(0)) { audio, _ ->
        audio + SyntheticAudio.tone(500) + SyntheticAudio.silence(500)
    } to List(count) { BASE_MILLIS + it * 1_000L..<BASE_MILLIS + it * 1_000L + 500 }

    @Test
    fun `登録した自分と仮の話者を発話に記録し、開き直しても読める`() = runTest {
        val path = dir.resolve("test.db")
        openTestDriver(path).use { driver ->
            val database = AmbientMemoDatabase(driver)
            val speakers = SqliteSpeakerRepository(database)
            val clock = FakeClock(HORIZON_MILLIS)
            val (enrollAudio, enrollSpeech) = bursts(3)
            val outcome = SelfEnrollment(
                audioSource = wavSource(enrollAudio),
                voiceActivityDetector = ScriptedVoiceActivityDetector(enrollSpeech),
                embedder = FakeSpeakerEmbedder { _, _ -> self },
                speakers = speakers,
                clock = clock,
                captureConfig = config,
                config = EnrollmentConfig(requiredSamples = 3, minSampleMillis = 400),
            ).run()
            assertEquals(EnrollmentOutcome.ENROLLED, outcome)

            val vectors = listOf(self, other, other, self)
            val embedder = FakeSpeakerEmbedder { index, _ -> vectors[index] }
            val (audio, speech) = bursts(4)
            CapturePipeline(
                audioSource = wavSource(audio),
                voiceActivityDetector = ScriptedVoiceActivityDetector(speech),
                transcriber = FakeTranscriber { index, _ -> Transcription("発話${index + 1}") },
                utterances = SqliteUtteranceRepository(database),
                captureGaps = SqliteCaptureGapRepository(database),
                clock = clock,
                config = config,
                speakerIdentifier = SpeakerIdentifier(
                    embedder,
                    speakers,
                    clock,
                    SpeakerConfig(minReliableMillis = 400, minReliableRunMillis = 400, minNewSpeakerRunMillis = 400),
                ),
            ).run()
            assertTrue(embedder.received.all { it.isReleased })
        }

        openTestDriver(path).use { driver ->
            val database = AmbientMemoDatabase(driver)
            val named = SqliteSpeakerRepository(database).listSpeakers().associate { it.id to it.displayName }
            val saved = SqliteUtteranceRepository(database).listOverlapping(BASE_MILLIS, HORIZON_MILLIS)

            assertEquals(listOf("自分", "話者1", "話者1", "自分"), saved.map { named[it.speakerId] })
            assertEquals(listOf(1.0, null, 1.0, 1.0), saved.map { it.speakerConfidence })
        }
    }
}

/** 1 つの区間から分けた行が、本物の SQLite を通っても順序を保ち、整理ジョブに話者ごとに渡ることを確かめる。 */
class SpeakerSplitToOrganizeIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private fun near(similarity: Float) = floatArrayOf(similarity, sqrt(1 - similarity * similarity), 0f)

    @Test
    fun `分けた行は開始時刻の順に整理ジョブへ渡り、同じメモの根拠になる`() = runTest {
        openTestDriver(dir.resolve("test.db")).use { driver ->
            val database = AmbientMemoDatabase(driver)
            val speakers = SqliteSpeakerRepository(database)
            val memos = SqliteMemoRepository(database)
            val clock = FakeClock(HORIZON_MILLIS)
            speakers.saveSelfEmbedding(SpeakerEmbedding(near(1f), "fake-1"), 3, 0)
            // 他人の窓の範囲は 2.625 秒から 5.625 秒
            val tokens = listOf("買", "い", "物", "何", "時", "に", "行", "く", "五", "時")
                .zip(listOf(0L, 800, 1_600, 2_700, 3_200, 3_700, 4_200, 4_700, 6_000, 7_000), ::TranscriptToken)
            val audio = SyntheticAudio.tone(9_500) + SyntheticAudio.silence(500)
            val embedder = FakeSpeakerEmbedder { _, window ->
                val isOther = window.startedAtMillis - BASE_MILLIS in 2_000L..5_000L
                near(if (isOther) 0f else 0.9f)
            }

            CapturePipeline(
                audioSource = wavSource(audio),
                voiceActivityDetector = ScriptedVoiceActivityDetector(listOf(BASE_MILLIS..<BASE_MILLIS + 9_500)),
                transcriber = FakeTranscriber({ _, _ -> tokens }) { _, _ -> Transcription("買い物何時に行く五時") },
                utterances = SqliteUtteranceRepository(database),
                captureGaps = SqliteCaptureGapRepository(database),
                clock = clock,
                config = CaptureConfig(maxUtteranceMillis = 30_000, minSilenceMillis = 200, preRollMillis = 0),
                speakerIdentifier = SpeakerIdentifier(embedder, speakers, clock),
            ).run()

            val rows = memos.listUnorganizedUtterances()
            val self = speakers.listSpeakers().single { it.isSelf }.id
            assertEquals(listOf("買い物", "何時に行く", "五時"), rows.map { it.text })
            assertEquals(self, rows[0].speakerId)
            assertNotEquals(self, rows[1].speakerId)
            assertEquals(self, rows[2].speakerId)
            assertEquals(rows.sortedBy { it.id }, rows)
            assertEquals(rows.drop(1).map { it.startedAtMillis }, rows.dropLast(1).map { it.endedAtMillis })

            val llm = FakeLlm.organizing()
            val job = OrganizeJob(memos, speakers, llm, clock, TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(OrganizeOutcome.COMPLETED, job.run().outcome)

            val memo = memos.listMemos().single()
            assertEquals(rows.map { it.id }, memos.listUtteranceIds(memo.id))
            val prompt = llm.received.first().user
            val positions = listOf("自分: 買い物", "話者1: 何時に行く", "自分: 五時").map(prompt::indexOf)
            assertTrue(positions.all { it >= 0 }, prompt)
            assertEquals(positions.sorted(), positions)
        }
    }
}

/** WAV から本物の VAD、ASR、話者のモデル、SQLite まで通す。モデルがなければ飛ばす。 */
class RealSpeakerToSqliteIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private fun silence(seconds: Int) = SyntheticAudio.silence(seconds * 1_000)

    private fun ShortArray.seconds(from: Int, to: Int) =
        copyOfRange(from * TEST_SAMPLE_RATE_HZ, minOf(size, to * TEST_SAMPLE_RATE_HZ))

    private inner class Harness(
        val detector: VoiceActivityDetector,
        val embedder: SpeakerEmbedder,
        val speakers: SqliteSpeakerRepository,
        val database: AmbientMemoDatabase,
    ) {
        suspend fun enroll(audio: ShortArray) = SelfEnrollment(
            audioSource = wavSource(audio),
            voiceActivityDetector = detector,
            embedder = embedder,
            speakers = speakers,
            clock = FakeClock(HORIZON_MILLIS),
            captureConfig = SherpaSettings.enrollmentCaptureConfig(),
        ).run()

        suspend fun capture(audio: ShortArray): List<Utterance> {
            val models = ModelCatalog.ACTIVE
            val utterances = SqliteUtteranceRepository(database)
            jvmTranscriber(TestModels.storeFor(models), models).use { transcriber ->
                CapturePipeline(
                    audioSource = wavSource(audio),
                    voiceActivityDetector = detector,
                    transcriber = transcriber,
                    utterances = utterances,
                    captureGaps = SqliteCaptureGapRepository(database),
                    clock = FakeClock(HORIZON_MILLIS),
                    config = SherpaSettings.captureConfig(),
                    transcriptionFilter = NoiseTextFilter(),
                    speakerIdentifier = SpeakerIdentifier(embedder, speakers, FakeClock(HORIZON_MILLIS)),
                ).run()
            }
            return utterances.listOverlapping(BASE_MILLIS, HORIZON_MILLIS)
        }
    }

    private fun harness(block: suspend Harness.() -> Unit) = runBlocking {
        val models = ModelCatalog.ACTIVE
        val speakerModel = ModelCatalog.ACTIVE_SPEAKER
        val store = TestModels.storeFor(models)
        val speakerStore = TestModels.storeFor(speakerModel)
        openTestDriver(dir.resolve("test.db")).use { driver ->
            val database = AmbientMemoDatabase(driver)
            jvmVoiceActivityDetector(store, models).use { detector ->
                jvmSpeakerEmbedder(speakerStore, speakerModel).use { embedder ->
                    Harness(detector, embedder, SqliteSpeakerRepository(database), database).block()
                }
            }
        }
    }

    @Test
    fun `登録した声を自分と判定し、別の声は声ごとの仮の話者にする`() = harness {
        val mine = TestModels.speech(number = 1)
        val first = TestModels.speech(number = 4)
        val second = TestModels.speech(number = 3)
        val enrollment = silence(1) + mine.seconds(0, 3) + silence(2) + mine.seconds(3, 6) + silence(2) +
            mine.seconds(6, 9) + silence(2)

        assertEquals(EnrollmentOutcome.ENROLLED, enroll(enrollment))

        val saved = capture(
            silence(1) + mine.seconds(9, 14) + silence(2) + first + silence(2) + second + silence(2) +
                first + silence(2),
        )

        assertEquals(4, saved.size, saved.map { it.text }.toString())
        val selfId = speakers.listSpeakers().single { it.isSelf }.id
        assertEquals(selfId, saved[0].speakerId)
        assertTrue(checkNotNull(saved[0].speakerConfidence) >= 0.6, "${saved[0].speakerConfidence}")
        assertNotEquals(selfId, saved[1].speakerId)
        assertNotEquals(selfId, saved[2].speakerId)
        assertNotEquals(saved[1].speakerId, saved[2].speakerId)
        assertEquals(saved[1].speakerId, saved[3].speakerId)
    }

    @Test
    fun `短い無音で続く別の声は、1 つの区間でも話者ごとの行に分ける`() = harness {
        val mine = TestModels.speech(number = 1)
        val enrollment = silence(1) + mine.seconds(0, 3) + silence(2) + mine.seconds(3, 6) + silence(2) +
            mine.seconds(6, 9) + silence(2)
        assertEquals(EnrollmentOutcome.ENROLLED, enroll(enrollment))

        val whole = TestModels.speech(number = 4)
        val saved = capture(silence(1) + mine.seconds(9, 13) + whole + silence(2))

        val selfId = speakers.listSpeakers().single { it.isSelf }.id
        assertTrue(saved.size >= 2, saved.map { it.text }.toString())
        assertEquals(selfId, saved.first().speakerId)
        assertNotEquals(selfId, saved.last().speakerId)
        assertEquals(saved.drop(1).map { it.startedAtMillis }, saved.dropLast(1).map { it.endedAtMillis })
    }

    @Test
    fun `登録しなければ話者を判定しない`() = harness {
        val saved = capture(silence(1) + TestModels.speech() + silence(2))

        assertEquals(null, saved.single().speakerId)
        assertTrue(speakers.listSpeakers().isEmpty())
    }

    @Test
    fun `発話の少ない音声では登録しない`() = harness {
        val outcome = enroll(silence(1) + TestModels.speech(number = 1).seconds(0, 3) + silence(2))

        assertEquals(EnrollmentOutcome.NOT_ENOUGH_SPEECH, outcome)
        assertTrue(speakers.listSpeakers().isEmpty())
    }
}
