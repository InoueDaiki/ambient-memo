package dev.ambientmemo.engine.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SegmentBacklogTest {
    private fun segment(startedAtMillis: Long, samples: Int = 10) = SpeechSegment(
        startedAtMillis = startedAtMillis,
        endedAtMillis = startedAtMillis + samples,
        sampleRateHz = 1_000,
        samples = ShortArray(samples) { 1 },
    )

    @Test
    fun `上限までは順番に取り出せる`() {
        val backlog = SegmentBacklog(maxSamples = 20)
        val first = segment(0)
        val second = segment(10)

        backlog.offer(first)
        backlog.offer(second)

        assertSame(first, backlog.poll())
        assertSame(second, backlog.poll())
        assertNull(backlog.poll())
        assertTrue(backlog.drainGaps().isEmpty())
    }

    @Test
    fun `あふれると古い発話を解放して欠落にする`() {
        val backlog = SegmentBacklog(maxSamples = 20)
        val oldest = segment(0)
        val middle = segment(10)
        val newest = segment(20, samples = 15)

        listOf(oldest, middle, newest).forEach(backlog::offer)

        assertTrue(oldest.isReleased)
        assertTrue(middle.isReleased)
        assertFalse(newest.isReleased)
        assertEquals(
            listOf(
                CaptureGap(0, 10, CaptureGapReason.PROCESSING_BACKLOG),
                CaptureGap(10, 20, CaptureGapReason.PROCESSING_BACKLOG),
            ),
            backlog.drainGaps(),
        )
        assertSame(newest, backlog.poll())
    }

    @Test
    fun `取り出した分は上限の計算から外れる`() {
        val backlog = SegmentBacklog(maxSamples = 10)
        val first = segment(0)
        backlog.offer(first)
        backlog.poll()

        backlog.offer(segment(10))

        assertFalse(first.isReleased)
        assertTrue(backlog.drainGaps().isEmpty())
    }

    @Test
    fun `drainGaps は同じ欠落を二度返さない`() {
        val backlog = SegmentBacklog(maxSamples = 10)
        backlog.addGap(CaptureGap(0, 1, CaptureGapReason.SOURCE_DISCONTINUITY))

        assertEquals(1, backlog.drainGaps().size)
        assertTrue(backlog.drainGaps().isEmpty())
    }

    @Test
    fun `abandonAll は残りをすべて解放して欠落にする`() {
        val backlog = SegmentBacklog(maxSamples = 20)
        val segments = listOf(segment(0), segment(10))
        segments.forEach(backlog::offer)

        backlog.abandonAll(CaptureGapReason.PIPELINE_STOPPED)

        assertTrue(segments.all { it.isReleased })
        assertNull(backlog.poll())
        assertEquals(2, backlog.drainGaps().count { it.reason == CaptureGapReason.PIPELINE_STOPPED })
    }

    @Test
    fun `awaitWork は発話が届くまで待つ`() = runTest {
        val backlog = SegmentBacklog(maxSamples = 10)
        val waiting = async { backlog.awaitWork() }
        runCurrent()
        assertFalse(waiting.isCompleted)

        backlog.offer(segment(0))

        assertTrue(waiting.await())
    }

    @Test
    fun `awaitWork は閉じても溜まった発話があれば続け、空になると終わる`() = runTest {
        val backlog = SegmentBacklog(maxSamples = 10)
        backlog.offer(segment(0))
        backlog.close()

        assertTrue(backlog.awaitWork())
        backlog.poll()
        assertFalse(backlog.awaitWork())
    }

    @Test
    fun `待っている間に閉じると終わる`() = runTest {
        val backlog = SegmentBacklog(maxSamples = 10)
        val waiting = async { backlog.awaitWork() }
        runCurrent()

        backlog.close()

        assertFalse(waiting.await())
    }
}
