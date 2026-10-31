package dev.ambientmemo.engine.domain.testing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

private const val MILLIS_PER_SECOND = 1_000
private const val TONE_HZ = 440.0
private const val TONE_AMPLITUDE = 8_000.0
private const val BYTES_PER_SAMPLE = 2
private const val BITS_PER_SAMPLE = 16
private const val FMT_CHUNK_BYTES = 16
private const val WAVE_ID_BYTES = 4
private const val CHUNK_HEADER_BYTES = 8
private const val PCM_FORMAT: Short = 1
private const val MONO: Short = 1

/** テスト用の合成音。個人の音声も、コミットする音声ファイルも使わない。 */
object SyntheticAudio {
    fun tone(millis: Int, sampleRateHz: Int = TEST_SAMPLE_RATE_HZ): ShortArray =
        ShortArray(millis * sampleRateHz / MILLIS_PER_SECOND) { index ->
            (sin(2 * PI * TONE_HZ * index / sampleRateHz) * TONE_AMPLITUDE).toInt().toShort()
        }

    fun silence(millis: Int, sampleRateHz: Int = TEST_SAMPLE_RATE_HZ): ShortArray =
        ShortArray(millis * sampleRateHz / MILLIS_PER_SECOND)

    /** 16 ビットのモノラル PCM の WAV を作る。[extraChunk] は fmt と data の間に入れる。 */
    fun wav(
        samples: ShortArray,
        sampleRateHz: Int = TEST_SAMPLE_RATE_HZ,
        channels: Short = MONO,
        extraChunk: ByteArray? = null,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray(Charsets.US_ASCII))
        body.write(chunk("fmt ", formatChunk(sampleRateHz, channels)))
        extraChunk?.let { body.write(chunk("LIST", it)) }
        body.write(chunk("data", pcmBytes(samples)))
        return chunk("RIFF", body.toByteArray())
    }

    private fun formatChunk(sampleRateHz: Int, channels: Short): ByteArray =
        ByteBuffer.allocate(FMT_CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(PCM_FORMAT)
            .putShort(channels)
            .putInt(sampleRateHz)
            .putInt(sampleRateHz * channels * BYTES_PER_SAMPLE)
            .putShort((channels * BYTES_PER_SAMPLE).toShort())
            .putShort(BITS_PER_SAMPLE.toShort())
            .array()

    private fun pcmBytes(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(samples)
        return buffer.array()
    }

    private fun chunk(id: String, payload: ByteArray): ByteArray {
        val padding = payload.size % 2
        return ByteBuffer.allocate(CHUNK_HEADER_BYTES + payload.size + padding).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII).copyOf(WAVE_ID_BYTES))
            .putInt(payload.size)
            .put(payload)
            .array()
    }
}
