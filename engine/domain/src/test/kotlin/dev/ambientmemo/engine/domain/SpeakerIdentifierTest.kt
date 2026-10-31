package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeSpeakerEmbedder
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerRepository
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SpeakerIdentifierTest {
    private val model = "fake-1"
    private val speakers = InMemorySpeakerRepository()
    private val clock = FakeClock(1_000)
    private val config = SpeakerConfig(selfThreshold = 0.6f, provisionalThreshold = 0.7f, minReliableMillis = 2_000)
    private val self = floatArrayOf(1f, 0f, 0f)

    /** self とのコサイン類似度が [similarity] の、長さ 1 のベクトル。 */
    private fun nearSelf(similarity: Float) = floatArrayOf(similarity, sqrt(1 - similarity * similarity), 0f)

    private fun segment(millis: Long = 3_000) = SpeechSegment(0, millis, TEST_SAMPLE_RATE_HZ, ShortArray(16))

    private fun identifier(
        vararg vectors: FloatArray?,
        modelVersion: String = model,
    ): Pair<SpeakerIdentifier, FakeSpeakerEmbedder> {
        val embedder = FakeSpeakerEmbedder(modelVersion) { index, _ -> vectors[index] }
        return SpeakerIdentifier(embedder, speakers, clock, config) to embedder
    }

    private suspend fun enroll(vector: FloatArray = self, modelVersion: String = model): Long =
        speakers.saveSelfEmbedding(SpeakerEmbedding(vector, modelVersion), 3, 0).id

    @Test
    fun `自分の登録がなければ特徴量を作らず、話者を判定しない`() = runTest {
        val (identifier, embedder) = identifier(self)

        assertNull(identifier.identify(segment()))
        assertTrue(embedder.received.isEmpty())
        assertTrue(speakers.listSpeakers().isEmpty())
    }

    @Test
    fun `しきい値以上なら自分にして、類似度を信頼度にする`() = runTest {
        val selfId = enroll()
        val (identifier, _) = identifier(nearSelf(0.6f))

        val assignment = identifier.identify(segment())

        assertEquals(selfId, assignment?.speakerId)
        assertEquals(0.6, checkNotNull(assignment?.confidence), 0.001)
    }

    @Test
    fun `しきい値より低ければ仮の話者を作る`() = runTest {
        val selfId = enroll()
        val (identifier, _) = identifier(nearSelf(0.59f))

        val assignment = checkNotNull(identifier.identify(segment()))

        assertNotEquals(selfId, assignment.speakerId)
        assertNull(assignment.confidence)
        assertEquals(listOf("自分", "話者1"), speakers.listSpeakers().map { it.displayName })
    }

    @Test
    fun `同じ声は同じ仮の話者にまとまり、別の声は別の話者になる`() = runTest {
        enroll()
        val a = floatArrayOf(0f, 1f, 0f)
        val b = floatArrayOf(0f, 0f, 1f)
        val (identifier, _) = identifier(a, b, a, b)

        val ids = List(4) { identifier.identify(segment())?.speakerId }

        assertEquals(ids[0], ids[2])
        assertEquals(ids[1], ids[3])
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `最も近い話者を選ぶ`() = runTest {
        enroll()
        val other = speakers.createProvisionalSpeaker(SpeakerEmbedding(nearSelf(0.8f), model), 0)
        val (identifier, _) = identifier(nearSelf(0.78f))

        assertEquals(other.id, identifier.identify(segment())?.speakerId)
    }

    @Test
    fun `仮の話者の特徴量は平均で更新する`() = runTest {
        enroll()
        val angle = 1.2f
        val first = floatArrayOf(0f, 1f, 0f)
        val next = floatArrayOf(0f, cos(angle / 3), sin(angle / 3))
        val other = speakers.createProvisionalSpeaker(SpeakerEmbedding(first, model), 0)
        val (identifier, _) = identifier(next)

        identifier.identify(segment())

        val stored = speakers.listEmbeddings(model, 10).single { it.speakerId == other.id }
        assertEquals(2, stored.sampleCount)
        assertTrue(stored.vector[2] > 0f && stored.vector[2] < next[2])
    }

    @Test
    fun `自分の特徴量は照合で変えない`() = runTest {
        enroll()
        val (identifier, _) = identifier(nearSelf(0.7f))

        identifier.identify(segment())

        val stored = speakers.listEmbeddings(model, 10).single()
        assertEquals(3, stored.sampleCount)
        assertEquals(self.toList(), stored.vector.toList())
    }

    @Test
    fun `短い区間は既存の話者には照合するが、平均に入れない`() = runTest {
        val selfId = enroll()
        val other = speakers.createProvisionalSpeaker(SpeakerEmbedding(floatArrayOf(0f, 1f, 0f), model), 0)
        val (identifier, _) = identifier(self, floatArrayOf(0f, 1f, 0f))

        assertEquals(selfId, identifier.identify(segment(1_999))?.speakerId)
        assertEquals(other.id, identifier.identify(segment(1_999))?.speakerId)

        assertEquals(1, speakers.listEmbeddings(model, 10).single { it.speakerId == other.id }.sampleCount)
    }

    @Test
    fun `短い区間で誰にも近くなければ、仮の話者を作らず不明にする`() = runTest {
        enroll()
        val (identifier, _) = identifier(floatArrayOf(0f, 0f, 1f))

        assertNull(identifier.identify(segment(1_999)))
        assertEquals(1, speakers.listSpeakers().size)
    }

    @Test
    fun `特徴量を作れなければ不明にする`() = runTest {
        enroll()
        val (identifier, _) = identifier(null)

        assertNull(identifier.identify(segment()))
    }

    @Test
    fun `長さが 0 や数でない特徴量は不明にして、仮の話者を作らない`() = runTest {
        enroll()
        val (identifier, _) = identifier(floatArrayOf(0f, 0f, 0f), floatArrayOf(Float.NaN, 1f, 0f))

        assertNull(identifier.identify(segment()))
        assertNull(identifier.identify(segment()))
        assertEquals(1, speakers.listSpeakers().size)
    }

    @Test
    fun `モデルの版が変わると登録し直すまで判定しない`() = runTest {
        enroll(modelVersion = "old")
        val (identifier, embedder) = identifier(self, modelVersion = "new")

        assertNull(identifier.identify(segment()))
        assertTrue(embedder.received.isEmpty())
    }

    @Test
    fun `正規化していない特徴量でも比べられる`() = runTest {
        val selfId = enroll()
        val (identifier, _) = identifier(floatArrayOf(30f, 0f, 0f))

        val assignment = identifier.identify(segment())

        assertEquals(selfId, assignment?.speakerId)
        assertEquals(1.0, checkNotNull(assignment?.confidence), 0.001)
    }

    @Test
    fun `照合する話者の上限を守る`() = runTest {
        val limited = SpeakerConfig(minReliableMillis = 0, maxComparedSpeakers = 2)
        enroll()
        val first = speakers.createProvisionalSpeaker(SpeakerEmbedding(floatArrayOf(0f, 1f, 0f), model), 1)
        speakers.createProvisionalSpeaker(SpeakerEmbedding(floatArrayOf(0f, 0f, 1f), model), 2)
        val embedder = FakeSpeakerEmbedder(model) { _, _ -> floatArrayOf(0f, 1f, 0f) }

        val assignment = SpeakerIdentifier(embedder, speakers, clock, limited).identify(segment())

        assertNotEquals(first.id, assignment?.speakerId)
    }
}
