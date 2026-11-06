package dev.ambientmemo.engine.domain

import java.util.Locale
import java.util.TimeZone

/**
 * LLM に一度に渡す発話のまとまり。発話は開始時刻の順に並ぶ。話題ごとに分けて、メモにする。
 *
 * [continues] は、無音ではなく文字数の上限で切ったこと。最後の話題が次のまとまりへ続いているかもしれない。
 */
internal class UtteranceChunk(
    val utterances: List<Utterance>,
    val continues: Boolean = false,
) {
    val startedAtMillis: Long = utterances.first().startedAtMillis
    val endedAtMillis: Long = utterances.maxOf { it.endedAtMillis }
    val isBlank: Boolean = utterances.all { it.text.isBlank() }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

/**
 * 発話を、検証の文字起こしと同じ `[HH:mm] 自分: 本文` の形にする。
 * 話者が分からない発話は、話者識別の前と同じ `[HH:mm] 本文` の形にする。
 */
internal fun Utterance.toTranscriptLine(timeZone: TimeZone, labels: SpeakerLabels = SpeakerLabels.NONE): String =
    "[${clockLabel(startedAtMillis, timeZone)}] ${labels.of(this)?.let { "$it: " }.orEmpty()}${text.trim()}"

/** [timeZone] での時刻の `HH:mm`。 */
internal fun clockLabel(millis: Long, timeZone: TimeZone): String {
    val localMillis = millis + timeZone.getOffset(millis)
    val minutes = Math.floorMod(Math.floorDiv(localMillis, MILLIS_PER_MINUTE), MINUTES_PER_DAY)
    return String.format(Locale.ROOT, "%02d:%02d", minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
}

internal fun UtteranceChunk.toTranscript(timeZone: TimeZone, labels: SpeakerLabels = SpeakerLabels.NONE): String =
    transcriptLines(timeZone, labels).joinToString("\n")

/** 本文のある発話の行。行の番号は話題の切れ目に使う。 */
internal fun UtteranceChunk.transcriptLines(
    timeZone: TimeZone,
    labels: SpeakerLabels = SpeakerLabels.NONE,
): List<String> = utterances.filter { it.text.isNotBlank() }.map { it.toTranscriptLine(timeZone, labels) }

/**
 * [starts] の行(1 から数える [transcriptLines] の番号)で話題に分ける。
 * 本文が空の発話は、前の行と同じ話題にする。最初の行より前なら最初の話題にする。
 */
internal fun UtteranceChunk.splitAtLines(starts: List<Int>): List<UtteranceChunk> {
    val topics = mutableListOf(mutableListOf<Utterance>())
    var line = 0
    for (utterance in utterances) {
        if (utterance.text.isNotBlank()) {
            line++
            if (line > 1 && line in starts) topics += mutableListOf<Utterance>()
        }
        topics.last() += utterance
    }
    return topics.map { UtteranceChunk(it) }
}

/**
 * [sorted] を、無音の長さと文字数の上限でまとまりに分ける。[sorted] は開始時刻、id の順に並んでいること。
 *
 * 上限より長い発話が1つだけあっても、その発話だけのまとまりにする。切ると文が壊れる。
 */
internal fun splitIntoChunks(
    sorted: List<Utterance>,
    config: OrganizeConfig,
    timeZone: TimeZone,
    labels: SpeakerLabels = SpeakerLabels.NONE,
): List<UtteranceChunk> {
    val chunks = mutableListOf<UtteranceChunk>()
    var current = mutableListOf<Utterance>()
    var currentChars = 0
    var currentEndedAt = Long.MIN_VALUE
    for (utterance in sorted) {
        val chars = utterance.toTranscriptLine(timeZone, labels).length + 1
        val isSilent = utterance.startedAtMillis - currentEndedAt >= config.minSilenceGapMillis
        if (current.isNotEmpty() && (isSilent || currentChars + chars > config.maxChunkChars)) {
            chunks += UtteranceChunk(current, continues = !isSilent)
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
