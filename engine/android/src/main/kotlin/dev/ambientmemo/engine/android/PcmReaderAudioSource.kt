package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.AudioFrame
import dev.ambientmemo.engine.domain.AudioSource
import dev.ambientmemo.engine.domain.Clock
import dev.ambientmemo.engine.domain.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val FRAME_MILLIS = 100L
private const val MILLIS_PER_SECOND = 1_000L

/** 音声を読み取れなくなった。マイクの占有や権限の取り消しなど。 */
class AudioCaptureException(message: String) : Exception(message)

/** 開いている音声の読み取り口。[read] は読めるまで待ち、読んだサンプル数を返す。負の値は失敗、0 は終わり。 */
internal interface PcmReader : AutoCloseable {
    fun read(buffer: ShortArray): Int
}

/**
 * [PcmReader] のサンプルを [AudioFrame] にして流す。読み終えた配列は 0 で塗りつぶす。
 *
 * OS からは [readMillis] ずつまとめて読み、0.1 秒のフレームに分けて流す。読む回数が減るほど、
 * このプロセスが CPU を起こす回数が減る。フレームの長さは変えないので、区切りの判定は読む長さに依らない。
 *
 * フレームの時刻はサンプル数から数える。読み取りが少し遅れても、OS のバッファに残った音声は失われないため。
 * 壁時計との差が [maxLagMillis] を超えたらバッファから溢れて音声が失われたとみなし、時刻を壁時計に合わせる。
 * パイプラインはこの飛びを欠落として記録する。
 */
internal class PcmReaderAudioSource(
    override val sampleRateHz: Int,
    private val maxLagMillis: Long,
    private val readMillis: Long = FRAME_MILLIS,
    private val clock: Clock = SystemClock,
    private val openReader: () -> PcmReader,
) : AudioSource {
    override fun frames(): Flow<AudioFrame> = flow {
        val buffer = ShortArray((sampleRateHz * readMillis / MILLIS_PER_SECOND).toInt())
        val frame = ShortArray((sampleRateHz * FRAME_MILLIS / MILLIS_PER_SECOND).toInt())
        var position: Position? = null
        try {
            openReader().use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) throw AudioCaptureException("音声を読み取れません: $count")
                    if (count == 0) break
                    val next = advance(position, count)
                    var offset = 0
                    while (offset < count) {
                        val size = minOf(frame.size, count - offset)
                        val samples = if (size == frame.size) frame else ShortArray(size)
                        buffer.copyInto(samples, 0, offset, offset + size)
                        emit(AudioFrame(next.frameStartMillis + offset * MILLIS_PER_SECOND / sampleRateHz, samples))
                        offset += size
                    }
                    position = next
                }
            }
        } finally {
            buffer.fill(0)
            frame.fill(0)
        }
    }

    /** サンプル数から数えた時刻。端数のミリ秒を積み上げないように、基準の時刻からのサンプル数で持つ。 */
    private data class Position(val baseMillis: Long, val samplesRead: Long, val frameStartMillis: Long)

    private fun advance(previous: Position?, count: Int): Position {
        val wallStartMillis = clock.nowMillis() - count * MILLIS_PER_SECOND / sampleRateHz
        if (previous != null) {
            val startMillis = previous.baseMillis + previous.samplesRead * MILLIS_PER_SECOND / sampleRateHz
            if (wallStartMillis - startMillis <= maxLagMillis) {
                return previous.copy(samplesRead = previous.samplesRead + count, frameStartMillis = startMillis)
            }
        }
        return Position(wallStartMillis, count.toLong(), wallStartMillis)
    }
}
