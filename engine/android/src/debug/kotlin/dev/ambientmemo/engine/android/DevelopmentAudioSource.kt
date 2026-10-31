package dev.ambientmemo.engine.android

import android.content.Context
import dev.ambientmemo.engine.domain.AudioSource
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val INPUT_FILE = "debug-input.wav"
private const val SAMPLE_RATE_HZ = 16_000
private const val MAX_LAG_MILLIS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val BYTES_PER_SAMPLE = 2
private const val CHUNK_HEADER_BYTES = 8
private const val RIFF_HEADER_BYTES = 12
private const val ID_BYTES = 4

/**
 * 内部ストレージに `debug-input.wav`(16 kHz、16 ビット、モノラル)があれば、マイクの代わりに実時間で流す。
 *
 * マイクの音が入らないエミュレーターで、本物のモデルを通して確かめるためのもの。音声を書き込むコードはなく、
 * 開発者が `adb` で置いたファイルを読むだけ。Release にはこのコードが入らない。
 */
internal fun developmentAudioSource(context: Context): AudioSource? {
    val file = File(context.filesDir, INPUT_FILE)
    if (!file.isFile) return null
    return PcmReaderAudioSource(
        sampleRateHz = SAMPLE_RATE_HZ,
        maxLagMillis = MAX_LAG_MILLIS,
        openReader = { WavFileReader(file) },
    )
}

/** ファイルの終わりの後は無音を流し続ける。マイクと同じく、止めるまで終わらない。 */
private class WavFileReader(file: File) : PcmReader {
    private val input = DataInputStream(BufferedInputStream(FileInputStream(file)))
    private var remainingBytes = seekToData()

    private fun seekToData(): Long {
        input.skipBytes(RIFF_HEADER_BYTES)
        while (true) {
            val header = ByteArray(CHUNK_HEADER_BYTES).also(input::readFully)
            val id = String(header, 0, ID_BYTES, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(ID_BYTES).toLong()
            if (id == "data") return size
            input.skipBytes((size + size % 2).toInt())
        }
    }

    override fun read(buffer: ShortArray): Int {
        Thread.sleep(buffer.size * MILLIS_PER_SECOND / SAMPLE_RATE_HZ)
        buffer.fill(0)
        val bytes = ByteArray(minOf(remainingBytes, (buffer.size * BYTES_PER_SAMPLE).toLong()).toInt())
        input.readFully(bytes)
        remainingBytes -= bytes.size
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buffer, 0, bytes.size / BYTES_PER_SAMPLE)
        return buffer.size
    }

    override fun close() = input.close()
}
