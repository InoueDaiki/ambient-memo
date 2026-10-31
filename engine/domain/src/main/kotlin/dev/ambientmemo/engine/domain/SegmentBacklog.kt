package dev.ambientmemo.engine.domain

import kotlinx.coroutines.channels.Channel

/**
 * 文字起こし待ちの発話の置き場所。音声の長さの合計に上限を持つ。
 *
 * 音声の読み取りを止めないように [offer] は待たない。あふれたら古い発話から解放して、
 * 欠落を溜める。DB への書き込みは文字起こし側が [drainGaps] で引き取って行う。
 */
internal class SegmentBacklog(private val maxSamples: Long) {
    private val lock = Any()
    private val segments = ArrayDeque<SpeechSegment>()
    private val gaps = mutableListOf<CaptureGap>()
    private var bufferedSamples = 0L
    private var isClosed = false

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    fun offer(segment: SpeechSegment) {
        synchronized(lock) {
            segments.addLast(segment)
            bufferedSamples += segment.sampleCount
            while (bufferedSamples > maxSamples) {
                dropOldest(CaptureGapReason.PROCESSING_BACKLOG)
            }
        }
        wakeUp.trySend(Unit)
    }

    fun addGap(gap: CaptureGap) {
        synchronized(lock) { gaps += gap }
        wakeUp.trySend(Unit)
    }

    /** 溜まった発話か欠落があれば `true`、閉じて空になったら `false` を返す。 */
    suspend fun awaitWork(): Boolean {
        while (true) {
            val state = synchronized(lock) {
                when {
                    segments.isNotEmpty() || gaps.isNotEmpty() -> true
                    isClosed -> false
                    else -> null
                }
            }
            if (state != null) return state
            wakeUp.receive()
        }
    }

    fun poll(): SpeechSegment? = synchronized(lock) {
        segments.removeFirstOrNull()?.also { bufferedSamples -= it.sampleCount }
    }

    fun drainGaps(): List<CaptureGap> = synchronized(lock) {
        gaps.toList().also { gaps.clear() }
    }

    /** これ以上追加しない。溜まった分は処理を続ける。 */
    fun close() {
        synchronized(lock) { isClosed = true }
        wakeUp.trySend(Unit)
    }

    /** 溜まった発話をすべて解放して、欠落にする。 */
    fun abandonAll(reason: CaptureGapReason) {
        synchronized(lock) {
            while (segments.isNotEmpty()) {
                dropOldest(reason)
            }
        }
    }

    private fun dropOldest(reason: CaptureGapReason) {
        val dropped = segments.removeFirst()
        bufferedSamples -= dropped.sampleCount
        gaps += CaptureGap(dropped.startedAtMillis, dropped.endedAtMillis, reason)
        dropped.close()
    }
}
