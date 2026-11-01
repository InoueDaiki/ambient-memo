package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeSpeakerEmbedder
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerDecisionLog
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerRepository
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/** 窓を 1 つにして、長い聞き取りの話者の増え方と重心の動きを既定の設定で確かめる。 */
class SpeakerLongConversationTest {
    private val model = "fake-1"
    private val speakers = InMemorySpeakerRepository()
    private val log = InMemorySpeakerDecisionLog()
    private val clock = FakeClock(1_000)
    private val config = SpeakerConfig(windowMillis = 60_000, trailingSilenceMillis = 0)
    private val selfVector = unit(64) { if (it == 0) 1f else 0f }

    private fun unit(size: Int, value: (Int) -> Float): FloatArray =
        checkNotNull(normalizedOrNull(FloatArray(size, value)))

    private fun axis(index: Int) = unit(64) { if (it == index) 1f else 0f }

    /** [center] の周りでばらつく声。窓の短い特徴量のばらつきの代わり。 */
    private fun Random.near(center: FloatArray, spread: Float): FloatArray =
        unit(center.size) { center[it] + nextGaussian().toFloat() * spread }

    private fun segment(millis: Long = 6_000) = SpeechSegment(0, millis, TEST_SAMPLE_RATE_HZ, ShortArray(16))

    private fun identifier(next: () -> FloatArray, used: SpeakerConfig = config) = SpeakerIdentifier(
        FakeSpeakerEmbedder(model) { _, _ -> next() },
        speakers,
        clock,
        used,
        log,
    )

    private suspend fun SpeakerIdentifier.identifyOne(millis: Long = 6_000): SpeakerAssignment? =
        identify(segment(millis), TimedTranscription(Transcription("本文"))).single().speaker

    private suspend fun enroll(): Long = speakers.saveSelfEmbedding(SpeakerEmbedding(selfVector, model), 3, 0).id

    private suspend fun provisional() =
        speakers.listSpeakers().filter { it.nameSource == SpeakerNameSource.PROVISIONAL }

    @Test
    fun `長い 2 人の会話で話者が増え続けない`() = runTest {
        val selfId = enroll()
        val other = axis(1)
        val random = Random(1)
        var isSelf = false
        // ばらつきは次元あたり 0.08。特徴量どうしの類似度は 0.5 前後まで下がる
        val identifier = identifier({ random.near(if (isSelf) selfVector else other, 0.08f) })

        val results = List(2_000) {
            isSelf = it % 2 == 0
            isSelf to identifier.identifyOne()
        }

        val count = provisional().size
        assertTrue(count <= config.maxProvisionalSpeakers) { "$count 人に増えました" }
        val selfRows = results.filter { (expected, _) -> expected }
        val hit = selfRows.count { (_, assignment) -> assignment?.speakerId == selfId }
        assertTrue(hit > selfRows.size * 9 / 10) { "再現率 $hit / ${selfRows.size}" }
        assertTrue(results.none { (expected, assignment) -> !expected && assignment?.speakerId == selfId })
    }

    @Test
    fun `周りの声が多くても仮の話者は上限を超えず、番号も増えない`() = runTest {
        enroll()
        var next = 0
        val identifier = identifier({ axis(1 + next++ % 40) })

        val assignments = List(120) { identifier.identifyOne() }

        assertEquals(listOf("話者1", "話者2", "話者3"), provisional().map { it.displayName })
        assertTrue(assignments.all { it != null })
    }

    @Test
    fun `上限に達したら最も近い仮の話者へ入れて、重心は動かさない`() = runTest {
        enroll()
        val ids = (1..3).map { speakers.createProvisionalSpeaker(SpeakerEmbedding(axis(it), model), 1, 2_000).id }
        val before = speakers.listEmbeddings(model, 10).first { it.speakerId == ids[1] }.vector
        val faint = unit(64) { if (it == 2) 0.2f else if (it == 9) 1f else 0f }

        val assignment = identifier({ faint }).identifyOne()

        assertEquals(ids[1], assignment?.speakerId)
        assertArrayEquals(before, speakers.listEmbeddings(model, 10).first { it.speakerId == ids[1] }.vector)
    }

    @Test
    fun `手で名付けた話者は上限に数えず、遠い声を押し込まない`() = runTest {
        enroll()
        val named = speakers.createProvisionalSpeaker(SpeakerEmbedding(axis(1), model), 1, 2_000)
        speakers.setManualName(named.id, "母")

        val assignment = identifier({ axis(2) }).identifyOne()

        assertNotEquals(named.id, assignment?.speakerId)
        assertEquals("話者1", provisional().single().displayName)
    }

    @Test
    fun `長く更新のない仮の話者は、新しい声に席を譲る`() = runTest {
        enroll()
        val ids = (1..3).map {
            speakers.createProvisionalSpeaker(SpeakerEmbedding(axis(it), model), it.toLong(), 2_000)
        }
        clock.advanceBy(config.staleProvisionalMillis + 10)

        val assignment = identifier({ axis(9) }).identifyOne()

        assertEquals(ids.first().id, assignment?.speakerId)
        assertNull(assignment?.confidence)
        assertEquals(3, provisional().size)
        val stored = speakers.listEmbeddings(model, 10).first { it.speakerId == ids.first().id }
        assertArrayEquals(axis(9), stored.vector)
        assertEquals(1, stored.sampleCount)
    }

    @Test
    fun `短いまとまりでは上限まで空いていても仮の話者を作らない`() = runTest {
        enroll()

        val assignment = identifier({ axis(1) }).identifyOne(millis = 2_000)

        assertNull(assignment)
        assertTrue(provisional().isEmpty())
    }

    @Test
    fun `仮の話者の重心は発話の長さで重み付けする`() = runTest {
        enroll()
        val close = unit(64) { if (it == 1) 1f else if (it == 2) 0.6f else 0f }
        val speaker = speakers.createProvisionalSpeaker(SpeakerEmbedding(axis(1), model), 1, 54_000)

        identifier({ close }).identifyOne()

        val stored = speakers.listEmbeddings(model, 10).single { it.speakerId == speaker.id }
        assertEquals(60_000L, stored.voicedMillis)
        assertTrue(stored.vector[2] < 0.1f) { "長い重心は短い発話で大きく動かない: ${stored.vector[2]}" }
    }

    @Test
    fun `弱い自分の判定でも、知っている他人のほうが近ければ他人にする`() = runTest {
        val selfId = enroll()
        val rival = unit(64) { if (it == 0) 0.55f else if (it == 1) 1f else 0f }
        val other = speakers.createProvisionalSpeaker(SpeakerEmbedding(rival, model), 1, 20_000)

        val assignment = identifier({ rival }).identifyOne()

        assertNotEquals(selfId, assignment?.speakerId)
        assertEquals(other.id, assignment?.speakerId)
    }

    @Test
    fun `不明の帯でも、他人より十分に自分に近ければ自分にする`() = runTest {
        val selfId = enroll()
        speakers.createProvisionalSpeaker(SpeakerEmbedding(axis(1), model), 1, 20_000)
        val voice = unit(64) { if (it == 0) 0.47f else if (it == 2) 0.883f else 0f }

        assertEquals(selfId, identifier({ voice }).identifyOne()?.speakerId)
    }

    @Test
    fun `他人を知らなければ不明の帯は不明のまま`() = runTest {
        enroll()
        val voice = unit(64) { if (it == 0) 0.47f else if (it == 2) 0.883f else 0f }

        assertNull(identifier({ voice }).identifyOne())
    }

    @Test
    fun `確信の高い発話は自分の声に足すが、登録は変えない`() = runTest {
        enroll()
        val voice = unit(64) { if (it == 0) 0.8f else if (it == 3) 0.6f else 0f }

        identifier({ voice }).identifyOne()

        val adaptation = checkNotNull(speakers.findSelfAdaptation(model))
        assertTrue(adaptation.vector[3] > 0f)
        assertEquals(6_000L, adaptation.voicedMillis)
        assertArrayEquals(selfVector, speakers.listEmbeddings(model, 10).single { it.isSelf }.vector)
    }

    @Test
    fun `確信の低い発話と短いまとまりでは自分の声を更新しない`() = runTest {
        enroll()
        val weak = unit(64) { if (it == 0) 0.52f else if (it == 3) 0.854f else 0f }
        val strong = unit(64) { if (it == 0) 0.9f else if (it == 3) 0.436f else 0f }

        identifier({ weak }).identifyOne()
        identifier({ strong }).identifyOne(millis = 1_500)

        assertNull(speakers.findSelfAdaptation(model))
    }

    @Test
    fun `知っている他人に近い発話では自分の声を更新しない`() = runTest {
        enroll()
        val voice = unit(64) { if (it == 0) 0.57f else if (it == 3) 0.82f else 0f }
        speakers.createProvisionalSpeaker(SpeakerEmbedding(voice, model), 1, 20_000)

        identifier({ voice }).identifyOne()

        assertNull(speakers.findSelfAdaptation(model))
    }

    @Test
    fun `育てた声で類似度を上げられる幅には上限がある`() = runTest {
        val selfId = enroll()
        val drifted = unit(64) { if (it == 0) 0.4f else if (it == 3) 0.917f else 0f }
        speakers.saveSelfAdaptation(SelfAdaptation(drifted, 600_000, model), 1)

        val assignment = identifier({ drifted }).identifyOne()

        // 登録との類似度は 0.4。上げられるのは 0.45 までなので、自分にはならない
        assertNotEquals(selfId, assignment?.speakerId)
    }

    @Test
    fun `育てた声で端末の条件の下がりを補う`() = runTest {
        val selfId = enroll()
        val drifted = unit(64) { if (it == 0) 0.47f else if (it == 3) 0.883f else 0f }
        val tuned = config.copy(selfThreshold = 0.5f, adaptMaxBoost = 0.05f)
        assertNull(identifier({ drifted }, tuned).identifyOne())

        speakers.saveSelfAdaptation(SelfAdaptation(drifted, 600_000, model), 1)

        assertEquals(selfId, identifier({ drifted }, tuned).identifyOne()?.speakerId)
    }

    @Test
    fun `登録し直すと育てた声は消える`() = runTest {
        enroll()
        speakers.saveSelfAdaptation(SelfAdaptation(axis(3), 600_000, model), 1)

        speakers.saveSelfEmbedding(SpeakerEmbedding(axis(4), model), 3, 2)

        assertNull(speakers.findSelfAdaptation(model))
    }

    @Test
    fun `判定の類似度を端末の中で集計する`() = runTest {
        val selfId = enroll()
        val reader = SpeakerDiagnosticsReader(log, speakers, model, clock)
        val voices = listOf(unit(64) { if (it == 0) 0.45f else if (it == 2) 0.893f else 0f }, selfVector, axis(1))
        var next = 0
        val identifier = identifier({ voices[next++] })

        val ids = List(3) { identifier.identifyOne()?.speakerId }
        val diagnostics = reader.read()

        assertEquals(selfId, ids[1])
        assertEquals(1, diagnostics.selfRuns)
        assertEquals(1, diagnostics.otherRuns)
        assertEquals(1, diagnostics.unknownRuns)
        assertEquals(1, diagnostics.provisionalSpeakers)
        assertEquals(6_000L, diagnostics.selfMillis)
        assertEquals(listOf(0f, 0.45f, 0.95f), diagnostics.histogram.map { it.floor })
    }
}
