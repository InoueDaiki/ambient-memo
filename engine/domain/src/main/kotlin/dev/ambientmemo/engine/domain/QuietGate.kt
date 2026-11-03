package dev.ambientmemo.engine.domain

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sqrt

private const val FULL_SCALE = 32_768.0
private const val DECIBELS_PER_DECADE = 20.0
private const val LEVEL_BAND_DECIBELS = 5

/** 無音の配列の大きさ。どの帯よりも下に置く。 */
private const val SILENT_DBFS = -120.0

/**
 * これより静かなフレームは VAD に渡さない。仮の値で、実機の [AudioLevelSummary] を見て見直す。
 *
 * 1〜2 m の会話はおよそ -35〜-25 dBFS。離れた小さな声を落とさないように、それより十分に下にした。
 */
const val QUIET_GATE_DBFS = -55.0

/** フレームの大きさ(RMS)を、最大の振幅を 0 とした dB で返す。 */
fun AudioFrame.levelDbfs(): Double {
    if (samples.isEmpty()) return SILENT_DBFS
    var sum = 0.0
    samples.forEach { sum += it.toDouble() * it }
    val rms = sqrt(sum / samples.size)
    return if (rms == 0.0) SILENT_DBFS else DECIBELS_PER_DECADE * log10(rms / FULL_SCALE)
}

/**
 * 静かな間は VAD を動かさない。VAD のモデルは無音にも毎回計算するので、聞き取りの CPU の多くを占める。
 *
 * VAD が発話と判定している間は、静かなフレームも渡す。区切りの無音を VAD が数えるため。
 * 静かなフレームを飛ばしたあとは、VAD の状態を捨ててから次のフレームを渡す。飛ばした間の音を続きとして扱わないため。
 * 語頭が [quietDbfs] より静かでも、パイプラインが区間の前の音声を足すので、文字起こしからは落ちない。
 */
class QuietGatedVoiceActivityDetector(
    private val delegate: VoiceActivityDetector,
    private val quietDbfs: Double = QUIET_GATE_DBFS,
    private val levels: AudioLevelCounter? = null,
) : VoiceActivityDetector {
    private var isSpeaking = false
    private var hasSkipped = false

    override fun isSpeech(frame: AudioFrame): Boolean {
        val level = frame.levelDbfs()
        val isQuiet = level < quietDbfs
        levels?.add(level, isSkipped = isQuiet && !isSpeaking)
        if (isQuiet && !isSpeaking) {
            hasSkipped = true
            return false
        }
        if (hasSkipped) {
            delegate.reset()
            hasSkipped = false
        }
        isSpeaking = delegate.isSpeech(frame)
        return isSpeaking
    }

    override fun reset() {
        isSpeaking = false
        hasSkipped = false
        delegate.reset()
    }
}

/** 一定の間の、フレームの大きさの分布と、VAD を飛ばした数。大きさだけで、音声の中身は持たない。 */
data class AudioLevelSummary(
    val frames: Int,
    val skippedFrames: Int,
    /** 帯の下端の dBFS と、その帯のフレームの数。下端の小さい順。 */
    val histogram: List<Pair<Int, Int>>,
) : DiagnosticEvent

/** [QuietGatedVoiceActivityDetector] が数え、記録の側が [drain] で取り出す。読み取りと記録は別のスレッドで動く。 */
class AudioLevelCounter {
    private var frames = 0
    private var skippedFrames = 0
    private val bands = sortedMapOf<Int, Int>()

    @Synchronized
    fun add(levelDbfs: Double, isSkipped: Boolean) {
        frames++
        if (isSkipped) skippedFrames++
        val band = (floor(levelDbfs / LEVEL_BAND_DECIBELS) * LEVEL_BAND_DECIBELS).toInt()
        bands[band] = (bands[band] ?: 0) + 1
    }

    /** 数えた分を返して 0 に戻す。何も数えていなければ null。 */
    @Synchronized
    fun drain(): AudioLevelSummary? {
        if (frames == 0) return null
        val summary = AudioLevelSummary(frames, skippedFrames, bands.map { (band, count) -> band to count })
        frames = 0
        skippedFrames = 0
        bands.clear()
        return summary
    }
}
