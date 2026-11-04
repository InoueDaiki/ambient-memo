package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeSpeakerEmbedder
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerDecisionLog
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerRepository
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * 乱数でばらつかせた 2,000 回の判定を流す統計の確かめだけを残す。java.util.Random の正規乱数に依り、
 * データにすると 64 次元の特徴量が 2,000 件になる。ほかの長い聞き取りの確かめは engine/spec/speaker-long-conversation.json にある。
 */
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

    private suspend fun SpeakerIdentifier.identifyOne(): SpeakerAssignment? =
        identify(segment(), TimedTranscription(Transcription("本文"))).single().speaker

    @Test
    fun `長い 2 人の会話で話者が増え続けない`() = runTest {
        val selfId = speakers.saveSelfEmbedding(SpeakerEmbedding(selfVector, model), 3, 0).id
        val other = axis(1)
        val random = Random(1)
        var isSelf = false
        // ばらつきは次元あたり 0.08。特徴量どうしの類似度は 0.5 前後まで下がる
        val embedder = FakeSpeakerEmbedder(model) { _, _ -> random.near(if (isSelf) selfVector else other, 0.08f) }
        val identifier = SpeakerIdentifier(embedder, speakers, clock, config, log)

        val results = List(2_000) {
            isSelf = it % 2 == 0
            isSelf to identifier.identifyOne()
        }

        val count = speakers.listSpeakers().count { it.nameSource == SpeakerNameSource.PROVISIONAL }
        assertTrue(count <= config.maxProvisionalSpeakers) { "$count 人に増えました" }
        val selfRows = results.filter { (expected, _) -> expected }
        val hit = selfRows.count { (_, assignment) -> assignment?.speakerId == selfId }
        assertTrue(hit > selfRows.size * 9 / 10) { "再現率 $hit / ${selfRows.size}" }
        assertTrue(results.none { (expected, assignment) -> !expected && assignment?.speakerId == selfId })
    }
}
