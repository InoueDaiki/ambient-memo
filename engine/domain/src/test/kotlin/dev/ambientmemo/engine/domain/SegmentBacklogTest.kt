package dev.ambientmemo.engine.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 待つ処理はコルーチンに依るので、ここに残す。それ以外は engine/spec/segment-backlog.json にある。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SegmentBacklogTest {
    private fun segment(startedAtMillis: Long, samples: Int = 10) = SpeechSegment(
        startedAtMillis = startedAtMillis,
        endedAtMillis = startedAtMillis + samples,
        sampleRateHz = 1_000,
        samples = ShortArray(samples) { 1 },
    )

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
    fun `待っている間に閉じると終わる`() = runTest {
        val backlog = SegmentBacklog(maxSamples = 10)
        val waiting = async { backlog.awaitWork() }
        runCurrent()

        backlog.close()

        assertFalse(waiting.await())
    }
}
