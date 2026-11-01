@file:Suppress("MagicNumber") // 区間の時刻と類似度の並びがテストの本体

package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.FakeAudioSource
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeSpeakerEmbedder
import dev.ambientmemo.engine.domain.testing.FakeTranscriber
import dev.ambientmemo.engine.domain.testing.InMemoryCaptureGapRepository
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerRepository
import dev.ambientmemo.engine.domain.testing.InMemoryUtteranceRepository
import dev.ambientmemo.engine.domain.testing.ScriptedVoiceActivityDetector
import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.math.sqrt

/** 音声から保存までを通して、区間の中の話者の判定と行の分割を確かめる。設定は既定値のまま。 */
class SpeakerTurnPipelineTest {
    private val clock = FakeClock(50_000)
    private val utterances = InMemoryUtteranceRepository()
    private val gaps = InMemoryCaptureGapRepository()
    private val speakers = InMemorySpeakerRepository()
    private val captureConfig = CaptureConfig(
        maxUtteranceMillis = 30_000,
        minSilenceMillis = 100,
        preRollMillis = 0,
        minSpeechMillis = 0,
    )

    // 窓の動きを見るテストなので、しきい値は既定値の調整に左右されない値に固定する
    private val windowConfig = SpeakerConfig(
        selfThreshold = 0.6f,
        selfExitThreshold = 0.55f,
        unknownFloor = 0.45f,
        provisionalThreshold = 0.6f,
        minNewSpeakerRunMillis = 1_000,
        maxProvisionalSpeakers = 64,
    )

    // 1 つの区間 + 区切りの無音で 6.8 秒。窓の発話の部分は 6 秒で、開始は 0, 750, ..., 4_500 の 7 つ
    private val windowCount = 7

    private fun self(similarity: Float) = floatArrayOf(similarity, sqrt(1 - similarity * similarity), 0f)

    /** 自分とは直交する声。[z] を変えると別の人になる。 */
    private fun other(z: Float = 0f) = floatArrayOf(0f, sqrt(1 - z * z), z)

    /** 区間の音声を流す。文字は 0.5 秒ごとに 1 つ。 */
    private fun text(millis: Long) = "あいうえおかきくけこさしすせそたちつてとなにぬねの".take((millis / 500).toInt())

    private fun tokens(text: String, durationMillis: Long): List<TranscriptToken> =
        text.mapIndexed { index, character ->
            TranscriptToken(character.toString(), index * 500L + 100)
        }.also { check(it.isEmpty() || it.last().offsetMillis < durationMillis) }

    private suspend fun run(
        vectors: List<FloatArray?>,
        millis: Long = 6_800,
        tokens: (String, Long) -> List<TranscriptToken> = ::tokens,
        embedder: FakeSpeakerEmbedder = FakeSpeakerEmbedder("fake-1") { index, _ -> vectors[index] },
    ): Pipeline {
        speakers.saveSelfEmbedding(SpeakerEmbedding(floatArrayOf(1f, 0f, 0f), "fake-1"), 3, 0)
        val transcriber = FakeTranscriber(tokens) { _, _ -> Transcription(text(millis), 0.5) }
        val pipeline = CapturePipeline(
            audioSource = FakeAudioSource(SyntheticAudio.tone(millis.toInt()) + SyntheticAudio.silence(200)),
            voiceActivityDetector = ScriptedVoiceActivityDetector(listOf(0L..<millis)),
            transcriber = transcriber,
            utterances = utterances,
            captureGaps = gaps,
            clock = clock,
            config = captureConfig,
            speakerIdentifier = SpeakerIdentifier(embedder, speakers, clock, windowConfig),
        )
        pipeline.run()
        return Pipeline(saved(), embedder, transcriber)
    }

    private class Pipeline(
        val rows: List<Utterance>,
        val embedder: FakeSpeakerEmbedder,
        val transcriber: FakeTranscriber,
    )

    private suspend fun saved() = utterances.listOverlapping(0, Long.MAX_VALUE)

    private suspend fun selfId() = speakers.listSpeakers().single { it.isSelf }.id

    private suspend fun provisionalIds() = speakers.listSpeakers().filterNot { it.isSelf }.map { it.id }

    @Test
    fun `話者が変わると行を分け、時刻を分割点に合わせて、本文をトークンの時刻で分ける`() = runTest {
        val scores = List(3) { self(0.8f) } + List(4) { other() }

        val rows = run(scores).rows

        assertEquals(2, rows.size)
        assertEquals(listOf(selfId(), provisionalIds().single()), rows.map { it.speakerId })
        // 窓 3 つ目と 4 つ目の中央の中点が境界
        assertEquals(listOf(0L, 2_625L), rows.map { it.startedAtMillis })
        assertEquals(listOf(2_625L, 6_800L), rows.map { it.endedAtMillis })
        assertEquals("あいうえお", rows[0].text.take(5))
        assertEquals(text(6_800), rows.joinToString("") { it.text })
        assertEquals(0.8, checkNotNull(rows[0].speakerConfidence), 0.001)
        assertNull(rows[1].speakerConfidence)
        assertEquals(setOf(0.5), rows.mapTo(HashSet()) { it.confidence })
        assertEquals(1, rows.distinctBy { it.createdAtMillis }.size)
    }

    @Test
    fun `話者が変わらなければ 1 行のまま、区間全体の時刻になる`() = runTest {
        val rows = run(List(windowCount) { self(0.9f) }).rows

        assertEquals(selfId(), rows.single().speakerId)
        assertEquals(0L, rows.single().startedAtMillis)
        assertEquals(6_800L, rows.single().endedAtMillis)
        assertEquals(text(6_800), rows.single().text)
    }

    @Test
    fun `後ろの区切りの無音は窓に入れず、窓の音声を発話から切り出す`() = runTest {
        val received = mutableListOf<LongRange>()
        val embedder = FakeSpeakerEmbedder("fake-1") { _, window ->
            received += window.startedAtMillis..window.endedAtMillis
            assertEquals(24_000, window.sampleCount)
            self(0.9f)
        }

        run(emptyList(), embedder = embedder)

        assertEquals(List(windowCount) { it * 750L..it * 750L + 1_500 }, received)
    }

    @Test
    fun `最後の窓は発話の終わりに揃える`() = runTest {
        val received = mutableListOf<Long>()
        val embedder = FakeSpeakerEmbedder("fake-1") { _, window ->
            received += window.endedAtMillis
            self(0.9f)
        }

        run(emptyList(), millis = 3_500, embedder = embedder)

        assertEquals(listOf(1_500L, 2_250L, 2_700L), received)
    }

    @Test
    fun `発話が窓より短ければ全体で 1 つの窓にする`() = runTest {
        val rows = run(listOf(self(0.9f)), millis = 2_000)

        assertEquals(1, rows.embedder.received.size)
        assertEquals(1_200, rows.embedder.received.single().endedAtMillis)
        assertEquals(selfId(), rows.rows.single().speakerId)
    }

    @Test
    fun `入るしきい値に届かない間は自分にならない`() = runTest {
        val rows = run(List(windowCount) { self(0.59f) }).rows

        // 平均が不明の帯に入るので、他人にもならない
        assertNull(rows.single().speakerId)
        assertTrue(provisionalIds().isEmpty())
    }

    @Test
    fun `自分に入ると出るしきい値以上の間は自分のまま続く`() = runTest {
        val scores = listOf(0.8f, 0.8f, 0.55f, 0.55f, 0.55f, 0.8f, 0.8f).map(::self)

        val row = run(scores).rows.single()

        assertEquals(selfId(), row.speakerId)
        assertEquals(0.69, checkNotNull(row.speakerConfidence), 0.01)
    }

    @Test
    fun `出るしきい値を下回ると自分から出て、次に入るまで戻らない`() = runTest {
        val scores = listOf(0.9f, 0.9f, 0.1f, 0.3f, 0.3f, 0.3f, 0.3f).map(::self)

        val rows = run(scores).rows

        assertEquals(2, rows.size)
        assertEquals(selfId(), rows[0].speakerId)
        assertTrue(rows[1].speakerId in provisionalIds())
    }

    @Test
    fun `まとまりの平均が不明の帯にあれば、自分の窓が続いても不明にする`() = runTest {
        val scores = listOf(0.62f, 0.56f, 0.56f, 0.56f, 0.56f, 0.56f, 0.56f).map(::self)

        val row = run(scores).rows.single()

        assertNull(row.speakerId)
        assertNull(row.speakerConfidence)
        assertTrue(provisionalIds().isEmpty())
    }

    @Test
    fun `他人のまとまりの平均が不明の帯にあれば、仮の話者を作らず不明にする`() = runTest {
        val rows = run(List(3) { self(0.9f) } + List(4) { self(0.5f) }).rows

        assertEquals(listOf(selfId(), null), rows.map { it.speakerId })
        assertTrue(provisionalIds().isEmpty())
    }

    @Test
    fun `不明の帯の下端より低ければ他人にする`() = runTest {
        val row = run(List(windowCount) { self(0.44f) }).rows.single()

        assertTrue(row.speakerId in provisionalIds())
    }

    @Test
    fun `短い区間は他人でも仮の話者を作らない`() = runTest {
        val row = run(List(2) { other() }, millis = 2_300).rows.single()

        assertNull(row.speakerId)
        assertTrue(provisionalIds().isEmpty())
    }

    @Test
    fun `長い区間でも短いまとまりでは仮の話者を作らない`() = runTest {
        val rows = run(List(3) { self(0.9f) } + listOf(other()) + List(3) { self(0.9f) }).rows

        assertEquals(listOf(selfId(), null, selfId()), rows.map { it.speakerId })
        assertTrue(provisionalIds().isEmpty())
    }

    @Test
    fun `同じ区間の前のまとまりで作った話者に合流する`() = runTest {
        val scores = List(2) { other() } + List(3) { self(0.9f) } + List(2) { other() }

        val rows = run(scores).rows

        val other = provisionalIds().single()
        assertEquals(listOf(other, selfId(), other), rows.map { it.speakerId })
        assertEquals(2, speakers.listEmbeddings("fake-1", 10).single { it.speakerId == other }.sampleCount)
    }

    @Test
    fun `他人どうしの交代は分けない`() = runTest {
        // 窓が分けるのは自分かそれ以外かだけで、検証の推薦も同じ
        val row = run(List(3) { other() } + List(4) { other(1f) }).rows.single()

        assertEquals(provisionalIds().single(), row.speakerId)
    }

    @Test
    fun `文字のないまとまりは行にせず、仮の話者も作らず、前後の同じ話者を 1 行にする`() = runTest {
        val scores = List(2) { self(0.9f) } + List(3) { other() } + List(2) { self(0.9f) }
        val silentMiddle = { text: String, duration: Long ->
            tokens(text, duration).mapIndexed { index, token ->
                token.copy(offsetMillis = if (index < 7) index * 100L else 4_200 + index * 100L)
            }
        }

        val row = run(scores, tokens = silentMiddle).rows.single()

        assertEquals(selfId(), row.speakerId)
        assertEquals(0L, row.startedAtMillis)
        assertEquals(6_800L, row.endedAtMillis)
        assertTrue(provisionalIds().isEmpty())
    }

    @Test
    fun `時刻がなければ本文を文字数で比例配分する`() = runTest {
        val rows = run(List(3) { self(0.9f) } + List(4) { other() }, tokens = { _, _ -> emptyList() }).rows

        // 本文は 13 文字、境界は 2.625 秒で、0.523 秒に 1 文字
        assertEquals(listOf(6, 7), rows.map { it.text.length })
        assertEquals(text(6_800), rows.joinToString("") { it.text })
    }

    @Test
    fun `句読点は前の語と同じ行に残し、行の先頭にしない`() = runTest {
        val punctuated = { _: String, _: Long ->
            listOf(
                TranscriptToken("あ", 2_000),
                TranscriptToken("。", 2_700),
                TranscriptToken("い", 3_000),
            )
        }
        val scores = List(3) { self(0.9f) } + List(4) { other() }
        speakers.saveSelfEmbedding(SpeakerEmbedding(floatArrayOf(1f, 0f, 0f), "fake-1"), 3, 0)
        val turns = SpeakerIdentifier(FakeSpeakerEmbedder("fake-1") { index, _ -> scores[index] }, speakers, clock)
            .identify(
                SpeechSegment(0, 6_800, 16_000, ShortArray(108_800)),
                TimedTranscription(Transcription("あ。い"), punctuated("", 0)),
            )

        assertEquals(listOf("あ。", "い"), turns.map { it.text })
    }

    @Test
    fun `特徴量が途中で失敗すると、窓の音声を消して、本文は不明の 1 行で残す`() = runTest {
        val embedder = FakeSpeakerEmbedder("fake-1") { index, _ ->
            if (index == 3) throw IOException("失敗")
            self(0.9f)
        }

        val result = run(emptyList(), embedder = embedder)

        val row = result.rows.single()
        assertNull(row.speakerId)
        assertEquals(text(6_800), row.text)
        assertEquals(4, embedder.received.size)
        assertTrue(embedder.received.all { it.isReleased })
        assertTrue(result.transcriber.received.all { it.isReleased })
        assertTrue(gaps.listOverlapping(0, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `一部の窓の特徴量を作れなくても、残りの窓で判定する`() = runTest {
        val scores = listOf(self(0.9f), null, self(0.9f), floatArrayOf(0f, 0f, 0f)) + List(3) { self(0.9f) }

        val row = run(scores).rows.single()

        assertEquals(selfId(), row.speakerId)
    }

    @Test
    fun `どの窓も特徴量を作れなければ不明の 1 行にする`() = runTest {
        val row = run(List(windowCount) { null }).rows.single()

        assertNull(row.speakerId)
        assertEquals(text(6_800), row.text)
    }

    @Test
    fun `窓の途中でキャンセルすると、窓と区間の音声を消して欠落を記録する`() = runTest {
        speakers.saveSelfEmbedding(SpeakerEmbedding(floatArrayOf(1f, 0f, 0f), "fake-1"), 3, 0)
        val started = CompletableDeferred<Unit>()
        val embedder = FakeSpeakerEmbedder("fake-1") { index, _ ->
            if (index == 2) {
                started.complete(Unit)
                awaitCancellation()
            }
            self(0.9f)
        }
        val transcriber = FakeTranscriber { _, _ -> Transcription("本文") }
        val pipeline = CapturePipeline(
            audioSource = FakeAudioSource(SyntheticAudio.tone(6_800) + SyntheticAudio.silence(200)),
            voiceActivityDetector = ScriptedVoiceActivityDetector(listOf(0L..<6_800L)),
            transcriber = transcriber,
            utterances = utterances,
            captureGaps = gaps,
            clock = clock,
            config = captureConfig,
            speakerIdentifier = SpeakerIdentifier(embedder, speakers, clock, windowConfig),
        )

        val job = launch { pipeline.run() }
        started.await()
        job.cancelAndJoin()

        assertTrue(saved().isEmpty())
        assertEquals(
            listOf(CaptureGapReason.PIPELINE_STOPPED),
            gaps.listOverlapping(0, Long.MAX_VALUE).map { it.reason },
        )
        assertEquals(3, embedder.received.size)
        assertTrue(embedder.received.all { it.isReleased })
        assertTrue(transcriber.received.all { it.isReleased })
    }

    @Test
    fun `窓の音声は特徴量の後に 0 で塗りつぶされる`() = runTest {
        val copies = mutableListOf<ShortArray>()
        val embedder = FakeSpeakerEmbedder("fake-1") { _, window ->
            copies += window.samples
            assertNotNull(window.samples.firstOrNull { it != 0.toShort() })
            self(0.9f)
        }

        run(emptyList(), embedder = embedder)

        assertEquals(windowCount, copies.size)
        assertTrue(copies.all { samples -> samples.all { it == 0.toShort() } })
    }

    @Test
    fun `自分の登録の前は窓を作らず、1 行のまま保存する`() = runTest {
        val embedder = FakeSpeakerEmbedder("fake-1")
        val identifier = SpeakerIdentifier(embedder, speakers, clock)

        val turns = identifier.identify(
            SpeechSegment(0, 6_800, 16_000, ShortArray(108_800)),
            TimedTranscription(Transcription("本文")),
        )

        assertEquals(listOf(SpeakerTurn(0, 6_800, "本文", null)), turns)
        assertTrue(embedder.received.isEmpty())
    }
}
