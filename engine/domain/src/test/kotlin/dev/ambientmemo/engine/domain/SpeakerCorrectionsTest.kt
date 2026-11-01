package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeSpeakerEmbedder
import dev.ambientmemo.engine.domain.testing.InMemorySpeakerRepository
import dev.ambientmemo.engine.domain.testing.InMemoryUtteranceRepository
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeakerCorrectionsTest {
    private val model = "fake-1"
    private val utterances = InMemoryUtteranceRepository()
    private val speakers = InMemorySpeakerRepository(utterances)
    private val voices = RecentVoices()
    private val clock = FakeClock(1_000)
    private val corrections = SpeakerCorrections(speakers, utterances, voices, model, clock)
    private val selfVector = floatArrayOf(1f, 0f, 0f)
    private val otherVector = floatArrayOf(0f, 1f, 0f)
    private val voice = floatArrayOf(0f, 0f, 1f)

    private suspend fun enroll() = speakers.saveSelfEmbedding(SpeakerEmbedding(selfVector, model), 3, 0)

    private suspend fun other(vector: FloatArray = otherVector, millis: Long = 4_000) =
        speakers.createProvisionalSpeaker(SpeakerEmbedding(vector, model), 1, millis)

    private suspend fun utter(speakerId: Long?) = utterances.insert(NewUtterance(0, 1, "本文", null, 1, speakerId, 0.5))

    private suspend fun stored(id: Long) = speakers.listEmbeddings(model, 10).first { it.speakerId == id }

    @Test
    fun `他人の行を自分に直すと、声を育てた声へ足し、登録は変えない`() = runTest {
        val self = enroll()
        val speaker = other()
        val row = utter(speaker.id)
        voices.remember(row.id, TurnVoice(voice, 4_000))

        assertTrue(corrections.reassign(row.id, SpeakerTarget.Self))

        val saved = checkNotNull(utterances.find(row.id))
        assertEquals(self.id, saved.speakerId)
        assertNull(saved.speakerConfidence)
        assertNotNull(speakers.findSelfAdaptation(model))
        assertArrayEquals(selfVector, stored(self.id).vector)
    }

    @Test
    fun `自分の行を他の話者に直すと、声をその話者の重心へ足す`() = runTest {
        val self = enroll()
        val speaker = other()
        val row = utter(self.id)
        voices.remember(row.id, TurnVoice(voice, 4_000))

        assertTrue(corrections.reassign(row.id, SpeakerTarget.Existing(speaker.id)))

        assertEquals(speaker.id, utterances.find(row.id)?.speakerId)
        val updated = stored(speaker.id)
        assertEquals(2, updated.sampleCount)
        assertEquals(8_000L, updated.voicedMillis)
        assertTrue(updated.vector[2] > 0f)
    }

    @Test
    fun `同じ発話を何度直しても、声は 1 度だけ足す`() = runTest {
        enroll()
        val speaker = other()
        val row = utter(null)
        voices.remember(row.id, TurnVoice(voice, 4_000))

        corrections.reassign(row.id, SpeakerTarget.Existing(speaker.id))
        corrections.reassign(row.id, SpeakerTarget.Unknown)
        corrections.reassign(row.id, SpeakerTarget.Existing(speaker.id))

        assertEquals(2, stored(speaker.id).sampleCount)
    }

    @Test
    fun `声が残っていない古い発話は話者だけを直す`() = runTest {
        enroll()
        val speaker = other()
        val row = utter(null)

        assertTrue(corrections.reassign(row.id, SpeakerTarget.Existing(speaker.id)))

        assertEquals(speaker.id, utterances.find(row.id)?.speakerId)
        assertEquals(1, stored(speaker.id).sampleCount)
    }

    @Test
    fun `新しい他の話者に直すと、声があればその声で作る`() = runTest {
        enroll()
        val row = utter(null)
        voices.remember(row.id, TurnVoice(voice, 3_000))

        assertTrue(corrections.reassign(row.id, SpeakerTarget.NewOther))

        val created = speakers.listSpeakers().single { !it.isSelf }
        assertEquals(created.id, utterances.find(row.id)?.speakerId)
        assertArrayEquals(voice, stored(created.id).vector)
    }

    @Test
    fun `仮の話者が上限なら新しい他の話者には直せない`() = runTest {
        enroll()
        repeat(SpeakerConfig().maxProvisionalSpeakers) { other() }
        val row = utter(null)

        assertFalse(corrections.reassign(row.id, SpeakerTarget.NewOther))

        assertNull(utterances.find(row.id)?.speakerId)
    }

    @Test
    fun `ない発話やない話者には直せない`() = runTest {
        enroll()
        val row = utter(null)

        assertFalse(corrections.reassign(row.id + 1, SpeakerTarget.Self))
        assertFalse(corrections.reassign(row.id, SpeakerTarget.Existing(99)))
    }

    @Test
    fun `登録の前は自分に直せない`() = runTest {
        val row = utter(null)

        assertFalse(corrections.reassign(row.id, SpeakerTarget.Self))
    }

    @Test
    fun `手の統合は発話と重心をまとめ、統合元を消す`() = runTest {
        enroll()
        val into = other(otherVector, 3_000)
        val from = other(voice, 1_000)
        val row = utter(from.id)

        assertTrue(corrections.merge(from.id, into.id))

        assertEquals(into.id, utterances.find(row.id)?.speakerId)
        assertTrue(speakers.listSpeakers().none { it.id == from.id })
        val merged = stored(into.id)
        assertEquals(4_000L, merged.voicedMillis)
        assertTrue(merged.vector[1] > merged.vector[2])
    }

    @Test
    fun `自分へ統合すると発話は自分になり、登録は変えない`() = runTest {
        val self = enroll()
        val from = other(voice)
        val row = utter(from.id)

        assertTrue(corrections.merge(from.id, self.id))

        assertEquals(self.id, utterances.find(row.id)?.speakerId)
        assertArrayEquals(selfVector, stored(self.id).vector)
        assertNotNull(speakers.findSelfAdaptation(model))
    }

    @Test
    fun `自分は統合元にできない`() = runTest {
        val self = enroll()
        val into = other()

        assertFalse(corrections.merge(self.id, into.id))
        assertFalse(corrections.merge(into.id, into.id))

        assertEquals(2, speakers.listSpeakers().size)
    }

    @Test
    fun `名前を手で付けると、まとめ直しの対象から外れる`() = runTest {
        val speaker = other()

        assertTrue(corrections.rename(speaker.id, "  母 "))
        assertFalse(corrections.rename(speaker.id, "  "))

        val renamed = speakers.listSpeakers().single()
        assertEquals("母", renamed.displayName)
        assertEquals(SpeakerNameSource.MANUAL, renamed.nameSource)
    }

    @Test
    fun `保存した発話の声を覚え、手で直すと次の判定に効く`() = runTest {
        val self = enroll()
        val embedder = FakeSpeakerEmbedder(model) { _, _ -> voice }
        val identifier = SpeakerIdentifier(embedder, speakers, clock, SpeakerConfig(windowMillis = 60_000))
        val segment = SpeechSegment(0, 6_000, TEST_SAMPLE_RATE_HZ, ShortArray(16))
        val turn = identifier.identify(segment, TimedTranscription(Transcription("本文"))).single()
        val speakerId = checkNotNull(turn.speaker?.speakerId)
        val row = utter(speakerId)
        voices.remember(row.id, checkNotNull(turn.voice))

        corrections.reassign(row.id, SpeakerTarget.Self)

        assertEquals(self.id, utterances.find(row.id)?.speakerId)
        assertNotNull(speakers.findSelfAdaptation(model))
    }

    @Test
    fun `覚える声の数には上限がある`() {
        val limited = RecentVoices(capacity = 2)

        (1L..3L).forEach { limited.remember(it, TurnVoice(voice, 1)) }

        assertNull(limited.take(1))
        assertNotNull(limited.take(3))
    }
}
