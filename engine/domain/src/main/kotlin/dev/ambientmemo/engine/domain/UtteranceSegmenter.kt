package dev.ambientmemo.engine.domain

import kotlin.math.min

/**
 * 発話か無音かの判定が付いたフレームから、発話区間を切り出す。
 *
 * 持つ音声は語頭のリングバッファと、発話の上限ぶんの作業用バッファだけで、長い発話でも増えない。
 * スレッドセーフではない。
 */
internal class UtteranceSegmenter(
    config: CaptureConfig,
    private val sampleRateHz: Int,
) {
    private val maxSamples = millisToSamples(config.maxUtteranceMillis, sampleRateHz)
    private val minSilenceSamples = millisToSamples(config.minSilenceMillis, sampleRateHz)
    private val minSpeechSamples = millisToSamples(config.minSpeechMillis, sampleRateHz)

    private val preRoll = PreRollBuffer(millisToSamples(config.preRollMillis, sampleRateHz))
    private val open = ShortArray(maxSamples)

    private var isOpen = false
    private var openSize = 0
    private var openStartedAtMillis = 0L
    private var isContinuation = false
    private var speechSamples = 0
    private var silenceRun = 0

    fun accept(frame: AudioFrame, isSpeech: Boolean): List<SpeechSegment> {
        if (!isOpen) {
            if (!isSpeech) {
                preRoll.write(frame.samples, 0, frame.samples.size)
                return emptyList()
            }
            startUtterance(frame.startedAtMillis)
        }

        val segments = mutableListOf<SpeechSegment>()
        var offset = 0
        while (offset < frame.samples.size) {
            val count = min(frame.samples.size - offset, maxSamples - openSize)
            frame.samples.copyInto(open, openSize, offset, offset + count)
            openSize += count
            offset += count
            if (isSpeech) {
                speechSamples += count
                silenceRun = 0
            } else {
                silenceRun += count
            }
            if (openSize == maxSamples) {
                splitAtLimit()?.let(segments::add)
            }
        }
        if (!isSpeech && silenceRun >= minSilenceSamples) {
            finishUtterance()?.let(segments::add)
        }
        return segments
    }

    /** 開いている発話を閉じて返す。音声の終わりや途切れで呼ぶ。 */
    fun flush(): SpeechSegment? = if (isOpen) finishUtterance() else null

    /** 持っている音声をすべて消す。発話の途中だった場合は、失われた時間を返す。 */
    fun discard(): CaptureSpan? {
        val lost = if (isOpen && voicedSamples() > 0) {
            CaptureSpan(openStartedAtMillis, openStartedAtMillis + samplesToMillis(voicedSamples(), sampleRateHz))
        } else {
            null
        }
        resetOpen()
        preRoll.clear()
        return lost
    }

    private fun startUtterance(frameStartedAtMillis: Long) {
        openSize = preRoll.moveTo(open)
        openStartedAtMillis = frameStartedAtMillis - samplesToMillis(openSize, sampleRateHz)
        isOpen = true
        isContinuation = false
        speechSamples = 0
        silenceRun = 0
    }

    private fun splitAtLimit(): SpeechSegment? {
        val segment = toSegment(voicedSamples())
        openStartedAtMillis += samplesToMillis(maxSamples, sampleRateHz)
        isContinuation = true
        speechSamples = 0
        open.fill(0)
        openSize = 0
        return segment
    }

    private fun finishUtterance(): SpeechSegment? {
        val voiced = voicedSamples()
        // 分割の続きは短くても発話の一部なので捨てない
        val isLongEnough = isContinuation || speechSamples >= minSpeechSamples
        val segment = if (isLongEnough) toSegment(voiced) else null
        preRoll.write(open, voiced, openSize - voiced)
        resetOpen()
        return segment
    }

    /** 末尾の無音を除いた長さ。分割すると無音の一部が前の区間に入るので、バッファの長さで抑える。 */
    private fun voicedSamples(): Int = openSize - min(silenceRun, openSize)

    private fun toSegment(sampleCount: Int): SpeechSegment? {
        if (sampleCount == 0) return null
        return SpeechSegment(
            startedAtMillis = openStartedAtMillis,
            endedAtMillis = openStartedAtMillis + samplesToMillis(sampleCount, sampleRateHz),
            sampleRateHz = sampleRateHz,
            samples = open.copyOf(sampleCount),
        )
    }

    private fun resetOpen() {
        open.fill(0)
        openSize = 0
        isOpen = false
        isContinuation = false
        speechSamples = 0
        silenceRun = 0
    }
}

internal data class CaptureSpan(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
)

/** 直近の音声を決まった長さだけ残すリングバッファ。 */
private class PreRollBuffer(capacity: Int) {
    private val buffer = ShortArray(capacity)
    private var writeIndex = 0
    private var size = 0

    fun write(source: ShortArray, offset: Int, length: Int) {
        if (buffer.isEmpty()) return
        val kept = min(length, buffer.size)
        for (index in offset + length - kept until offset + length) {
            buffer[writeIndex] = source[index]
            writeIndex = (writeIndex + 1) % buffer.size
        }
        size = min(size + kept, buffer.size)
    }

    /** 古い順に [destination] へ移し、移した長さを返す。 */
    fun moveTo(destination: ShortArray): Int {
        val moved = size
        val start = writeIndex - size
        for (index in 0 until moved) {
            destination[index] = buffer[Math.floorMod(start + index, buffer.size)]
        }
        clear()
        return moved
    }

    fun clear() {
        buffer.fill(0)
        writeIndex = 0
        size = 0
    }
}
