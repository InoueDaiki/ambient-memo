package dev.ambientmemo.engine.domain

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 発話区間の音声を持つ、メモリ上のバッファ。音声を保持するのはここだけにする。
 *
 * [close] すると中身を 0 で塗りつぶす。参照が残っていても音声は読めなくなる。
 */
class SpeechSegment(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val sampleRateHz: Int,
    samples: ShortArray,
) : AutoCloseable {
    private val released = AtomicBoolean(false)
    private val buffer = samples

    val isReleased: Boolean
        get() = released.get()

    val sampleCount: Int = samples.size

    val samples: ShortArray
        get() {
            check(!isReleased) { "解放済みのバッファです" }
            return buffer
        }

    override fun close() {
        if (released.compareAndSet(false, true)) {
            buffer.fill(0)
        }
    }
}
