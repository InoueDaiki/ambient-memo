package dev.ambientmemo.engine.domain.testing

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.AudioSource
import dev.ambientmemo.engine.domain.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

private const val MILLIS_PER_SECOND = 1_000L
private const val BYTES_PER_SAMPLE = 2
private const val BITS_PER_SAMPLE = 16
private const val FORMAT_PCM = 1
private const val RIFF_HEADER_BYTES = 12
private const val CHUNK_HEADER_BYTES = 8
private const val FMT_CHUNK_MIN_BYTES = 16
private const val ID_BYTES = 4
private const val SIZE_OFFSET = 4
private const val CHANNELS_OFFSET = 2
private const val SAMPLE_RATE_OFFSET = 4
private const val BITS_PER_SAMPLE_OFFSET = 14
private const val UNSIGNED_INT_MASK = 0xFFFFFFFFL

/**
 * 16 ビットのモノラル PCM の WAV ファイルを、[AudioSource] として流す。
 *
 * マイクの代わりに結合テストで使う。実時間には合わせず、読めるだけ速く流す。
 * 時刻は collect の開始時の [clock] を起点にして、サンプル数から計算する。
 */
class WavAudioSource(
    private val open: () -> InputStream,
    private val clock: Clock,
    override val sampleRateHz: Int = TEST_SAMPLE_RATE_HZ,
    private val frameSamples: Int = TEST_FRAME_SAMPLES,
) : AudioSource {
    constructor(path: Path, clock: Clock, sampleRateHz: Int = TEST_SAMPLE_RATE_HZ) :
        this({ Files.newInputStream(path) }, clock, sampleRateHz)

    override fun frames(): Flow<AudioFrame> = flow {
        open().buffered().use { input ->
            val remaining = readDataChunkSize(input, sampleRateHz)
            val startMillis = clock.nowMillis()
            val samples = ShortArray(frameSamples)
            val bytes = ByteArray(frameSamples * BYTES_PER_SAMPLE)
            var readBytes = 0L
            var position = 0L
            while (readBytes < remaining) {
                val wanted = minOf(bytes.size.toLong(), remaining - readBytes).toInt()
                val count = input.readNBytes(bytes, 0, wanted)
                val sampleCount = count / BYTES_PER_SAMPLE
                if (sampleCount == 0) break
                ByteBuffer.wrap(bytes, 0, sampleCount * BYTES_PER_SAMPLE)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .get(samples, 0, sampleCount)
                val frame = if (sampleCount == samples.size) samples else samples.copyOf(sampleCount)
                emit(AudioFrame(startMillis + position * MILLIS_PER_SECOND / sampleRateHz, frame))
                readBytes += count
                position += sampleCount
            }
        }
    }
}

/** ヘッダーを確かめ、data チャンクの先頭まで読み進めて、そのバイト数を返す。 */
private fun readDataChunkSize(input: InputStream, expectedSampleRateHz: Int): Long {
    val riff = readExactly(input, RIFF_HEADER_BYTES)
    require(id(riff, 0) == "RIFF" && id(riff, RIFF_HEADER_BYTES - ID_BYTES) == "WAVE") { "WAV ファイルではありません" }

    var hasFormat = false
    while (true) {
        val header = readExactly(input, CHUNK_HEADER_BYTES)
        val size = littleEndian(header).getInt(SIZE_OFFSET).toLong() and UNSIGNED_INT_MASK
        when (id(header, 0)) {
            "fmt " -> {
                require(size >= FMT_CHUNK_MIN_BYTES) { "fmt チャンクが短すぎます" }
                checkFormat(readExactly(input, size.toInt()), expectedSampleRateHz)
                hasFormat = true
            }
            "data" -> {
                require(hasFormat) { "data チャンクの前に fmt チャンクがありません" }
                return size
            }
            else -> input.skipNBytes(size)
        }
        // チャンクは偶数バイトに揃えられている
        if (size % 2 != 0L) input.skipNBytes(1)
    }
}

private fun checkFormat(chunk: ByteArray, expectedSampleRateHz: Int) {
    val buffer = littleEndian(chunk)
    require(buffer.getShort(0).toInt() == FORMAT_PCM) { "PCM ではありません" }
    require(buffer.getShort(CHANNELS_OFFSET).toInt() == 1) { "モノラルではありません" }
    require(buffer.getShort(BITS_PER_SAMPLE_OFFSET).toInt() == BITS_PER_SAMPLE) { "16 ビットではありません" }
    val sampleRateHz = buffer.getInt(SAMPLE_RATE_OFFSET)
    require(sampleRateHz == expectedSampleRateHz) { "サンプリング周波数が $sampleRateHz Hz です" }
}

private fun readExactly(input: InputStream, length: Int): ByteArray {
    val bytes = input.readNBytes(length)
    if (bytes.size < length) throw EOFException("WAV ファイルの途中で終わっています")
    return bytes
}

private fun id(bytes: ByteArray, offset: Int) = String(bytes, offset, ID_BYTES, Charsets.US_ASCII)

private fun littleEndian(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
