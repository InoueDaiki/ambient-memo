package dev.ambientmemo.engine.domain

import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.SyntheticAudio
import dev.ambientmemo.engine.domain.testing.WavAudioSource
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class WavAudioSourceTest {
    private val samples = SyntheticAudio.tone(100)

    private fun source(bytes: ByteArray, clock: FakeClock = FakeClock()) =
        WavAudioSource({ ByteArrayInputStream(bytes) }, clock)

    private suspend fun WavAudioSource.readAll(): List<Short> =
        frames().map { it.samples.toList() }.toList().flatten()

    @Test
    fun `ファイルから読める`(@TempDir dir: Path) = runTest {
        val path = dir.resolve("tone.wav")
        Files.write(path, SyntheticAudio.wav(samples))

        val read = WavAudioSource(path, FakeClock()).frames().map { it.samples.size }.toList().sum()

        assertEquals(samples.size, read)
    }

    @Test
    fun `時刻は読み始めの時計を起点にする`() = runTest {
        val frames = source(SyntheticAudio.wav(samples), FakeClock(5_000))
            .frames()
            .map { it.startedAtMillis }
            .toList()

        assertEquals(listOf(5_000L, 5_010L, 5_020L), frames.take(3))
    }

    @Test
    fun `知らないチャンクは奇数バイトでも読み飛ばす`() = runTest {
        val bytes = SyntheticAudio.wav(samples, extraChunk = byteArrayOf(1, 2, 3))

        assertEquals(samples.toList(), source(bytes).readAll())
    }

    @Test
    fun `末尾が切れたファイルは読める分だけ流す`() = runTest {
        val bytes = SyntheticAudio.wav(samples)
        // 奇数バイトで切る。半端な1バイトはサンプルにしない
        val truncated = bytes.copyOf(bytes.size - 101)

        assertEquals(samples.size - 51, source(truncated).readAll().size)
    }

    @Test
    fun `読み終わるとストリームを閉じる`() = runTest {
        val stream = ClosableStream(SyntheticAudio.wav(samples))

        WavAudioSource({ stream }, FakeClock()).frames().collect()

        assertTrue(stream.isClosed)
    }

    @Test
    fun `途中で止めてもストリームを閉じる`() = runTest {
        val stream = ClosableStream(SyntheticAudio.wav(samples))

        WavAudioSource({ stream }, FakeClock()).frames().first()

        assertTrue(stream.isClosed)
    }

    @Test
    fun `WAV ではないものは読まない`() {
        assertRejected<IllegalArgumentException>(ByteArray(64))
    }

    @Test
    fun `ヘッダーが途中で切れたものは読まない`() {
        assertRejected<EOFException>(SyntheticAudio.wav(samples).copyOf(20))
    }

    @Test
    fun `モノラル以外は読まない`() {
        assertRejected<IllegalArgumentException>(SyntheticAudio.wav(samples, channels = 2))
    }

    @Test
    fun `サンプリング周波数が違うものは読まない`() {
        assertRejected<IllegalArgumentException>(SyntheticAudio.wav(samples, sampleRateHz = 48_000))
    }

    private inline fun <reified T : Throwable> assertRejected(bytes: ByteArray) {
        assertThrows(T::class.java) { runTest { source(bytes).frames().collect() } }
    }

    private class ClosableStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var isClosed = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)

        override fun close() {
            isClosed = true
        }
    }
}
