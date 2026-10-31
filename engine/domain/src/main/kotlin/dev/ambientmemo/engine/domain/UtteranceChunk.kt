package dev.ambientmemo.engine.domain

import java.util.Locale
import java.util.TimeZone

/** メモ1件になる発話のまとまり。発話は開始時刻の順に並ぶ。 */
internal class UtteranceChunk(
    val utterances: List<Utterance>,
) {
    val startedAtMillis: Long = utterances.first().startedAtMillis
    val endedAtMillis: Long = utterances.maxOf { it.endedAtMillis }
    val isBlank: Boolean = utterances.all { it.text.isBlank() }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

/**
 * 話者ラベルのない今の発話を、検証の文字起こしと同じ `[HH:mm] 本文` の形にする。
 * 話者識別を足したら、ここで `[HH:mm] 自分: 本文` の形にする。
 */
internal fun Utterance.toTranscriptLine(timeZone: TimeZone): String {
    val localMillis = startedAtMillis + timeZone.getOffset(startedAtMillis)
    val minutes = Math.floorMod(Math.floorDiv(localMillis, MILLIS_PER_MINUTE), MINUTES_PER_DAY)
    return String.format(
        Locale.ROOT,
        "[%02d:%02d] %s",
        minutes / MINUTES_PER_HOUR,
        minutes % MINUTES_PER_HOUR,
        text.trim(),
    )
}

internal fun UtteranceChunk.toTranscript(timeZone: TimeZone): String =
    utterances.filter { it.text.isNotBlank() }.joinToString("\n") { it.toTranscriptLine(timeZone) }

/**
 * [sorted] を、無音の長さと文字数の上限でまとまりに分ける。[sorted] は開始時刻、id の順に並んでいること。
 *
 * 上限より長い発話が1つだけあっても、その発話だけのまとまりにする。切ると文が壊れる。
 */
internal fun splitIntoChunks(
    sorted: List<Utterance>,
    config: OrganizeConfig,
    timeZone: TimeZone,
): List<UtteranceChunk> {
    val chunks = mutableListOf<UtteranceChunk>()
    var current = mutableListOf<Utterance>()
    var currentChars = 0
    var currentEndedAt = Long.MIN_VALUE
    for (utterance in sorted) {
        val chars = utterance.toTranscriptLine(timeZone).length + 1
        val isSilent = utterance.startedAtMillis - currentEndedAt >= config.minSilenceGapMillis
        if (current.isNotEmpty() && (isSilent || currentChars + chars > config.maxChunkChars)) {
            chunks += UtteranceChunk(current)
            current = mutableListOf()
            currentChars = 0
            currentEndedAt = Long.MIN_VALUE
        }
        current += utterance
        currentChars += chars
        currentEndedAt = maxOf(currentEndedAt, utterance.endedAtMillis)
    }
    if (current.isNotEmpty()) chunks += UtteranceChunk(current)
    return chunks
}

/** 会話が続いているかもしれない最後のまとまりを除く。途中で切ると、続きが別のメモに分かれる。 */
internal fun List<UtteranceChunk>.withoutOngoing(nowMillis: Long, config: OrganizeConfig): List<UtteranceChunk> {
    val isLastOngoing = lastOrNull()?.let { nowMillis - it.endedAtMillis < config.minSilenceGapMillis } ?: false
    return if (isLastOngoing) dropLast(1) else this
}
