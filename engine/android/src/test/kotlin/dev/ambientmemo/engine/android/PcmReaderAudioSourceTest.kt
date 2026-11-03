@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.AudioSource
import dev.ambientmemo.engine.domain.testing.AudioSourceContract
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.TEST_SAMPLE_RATE_HZ
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val FRAME_SAMPLES = 1_600

private class ScriptedReader(
    private val samples: ShortArray,
    private val failAt: Int? = null,
    private val onRead: (Int) -> Unit = {},
) : PcmReader {
    var closed = false
    private var position = 0
    private var reads = 0

    override fun read(buffer: ShortArray): Int {
        onRead(reads)
        if (reads++ == failAt) return -1
        val count = minOf(buffer.size, samples.size - position)
        samples.copyInto(buffer, 0, position, position + count)
        position += count
        return count
    }

    override fun close() {
        closed = true
    }
}

class PcmReaderAudioSourceContractTest : AudioSourceContract() {
    override fun source(samples: ShortArray): AudioSource {
        val clock = FakeClock(1_000)
        return PcmReaderAudioSource(TEST_SAMPLE_RATE_HZ, maxLagMillis = 1_000, clock = clock) {
            ScriptedReader(samples, onRead = { clock.advanceBy(100) })
        }
    }
}

class PcmReaderAudioSourceTest {
    private val clock = FakeClock(10_000)

    private fun source(reader: PcmReader) =
        PcmReaderAudioSource(TEST_SAMPLE_RATE_HZ, maxLagMillis = 1_000, clock = clock) { reader }

    @Test
    fun `最初のフレームの時刻は読み終えた時刻から長さを引いたもの`() = runTest {
        val frame = source(ScriptedReader(ShortArray(FRAME_SAMPLES))).frames().first()

        assertEquals(9_900, frame.startedAtMillis)
    }

    @Test
    fun `バッファに収まる遅れは時刻を飛ばさない`() = runTest {
        val reader = ScriptedReader(ShortArray(FRAME_SAMPLES * 3), onRead = { if (it == 1) clock.advanceBy(900) })

        val starts = source(reader).frames().take(3).toList().map(AudioFrame::startedAtMillis)

        assertEquals(listOf(9_900L, 10_000L, 10_100L), starts)
    }

    @Test
    fun `バッファを超える遅れは音声の欠落として時刻を飛ばす`() = runTest {
        val reader = ScriptedReader(ShortArray(FRAME_SAMPLES * 2), onRead = { if (it == 1) clock.advanceBy(5_000) })

        val starts = source(reader).frames().take(2).toList().map(AudioFrame::startedAtMillis)

        assertEquals(listOf(9_900L, 14_900L), starts)
    }

    @Test
    fun `まとめて読んだ音声を 100 ミリ秒のフレームに分け、時刻を続ける`() = runTest {
        val samples = ShortArray(FRAME_SAMPLES * 15) { (it / FRAME_SAMPLES).toShort() }
        val reader = ScriptedReader(samples, onRead = { clock.advanceBy(1_000) })
        val source = PcmReaderAudioSource(
            TEST_SAMPLE_RATE_HZ,
            maxLagMillis = 3_000,
            readMillis = 1_000,
            clock = clock,
        ) { reader }

        val frames = source.frames().map { AudioFrame(it.startedAtMillis, it.samples.copyOf()) }.toList()

        assertEquals(15, frames.size)
        assertTrue(frames.all { it.samples.size == FRAME_SAMPLES })
        assertEquals((0 until 15).map { 10_000L + it * 100 }, frames.map(AudioFrame::startedAtMillis))
        assertEquals((0 until 15).toList(), frames.map { it.samples.first().toInt() })
    }

    @Test
    fun `読み取りに失敗したら例外を投げて閉じる`() {
        val reader = ScriptedReader(ShortArray(FRAME_SAMPLES * 2), failAt = 1)

        assertThrows(AudioCaptureException::class.java) {
            runBlocking { source(reader).frames().toList() }
        }

        assertTrue(reader.closed)
    }

    @Test
    fun `途中でやめても閉じて配列を消す`() = runTest {
        val reader = ScriptedReader(ShortArray(FRAME_SAMPLES) { 1 })
        lateinit var delivered: ShortArray

        source(reader).frames().first().also { delivered = it.samples }

        assertTrue(reader.closed)
        assertTrue(delivered.all { it.toInt() == 0 })
    }
}
