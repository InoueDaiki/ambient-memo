package dev.ambientmemo.engine.domain

// 区間の内側を窓で判定し、話者の続く範囲ごとに本文を分ける。docs/検証-話者区間.md の推薦と同じ手順にする。
// この手順の値はすべて SpeakerConfig にある。時刻は明記がなければ区間の先頭からのミリ秒。

/** 発話区間の話者の範囲と、その本文。時刻は発話と同じ UNIX エポックからのミリ秒。[speaker] が null なら不明。 */
data class SpeakerTurn(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val text: String,
    val speaker: SpeakerAssignment?,
    /** 手で直したときに判定へ反映する声。保存しない。 */
    val voice: TurnVoice? = null,
)

/** 行の声の特徴量。[RecentVoices] がメモリに持つだけで、DB には書かない。 */
class TurnVoice(
    val vector: FloatArray,
    val voicedMillis: Long,
)

internal data class SpeakerWindow(
    val startMillis: Long,
    val endMillis: Long,
)

/** 窓の特徴量と、自分とのコサイン類似度。 */
internal class ScoredWindow(
    val window: SpeakerWindow,
    val vector: FloatArray,
    val score: Float,
)

internal enum class Voice { SELF, OTHER, UNKNOWN }

/** ヒステリシスで同じ判定が続いたまとまり。[score] は自分との類似度の平均。 */
internal class VoiceRun(
    val startMillis: Long,
    val endMillis: Long,
    val voice: Voice,
    val vector: FloatArray?,
    val score: Float,
) {
    val millis: Long get() = endMillis - startMillis
}

/** 本文の割り当てが済んだまとまり。 */
internal class VoicePart(
    val run: VoiceRun,
    val startMillis: Long,
    val endMillis: Long,
    val text: String,
)

/**
 * 判定する窓を返す。最後の窓は区間の終わりに揃える。
 *
 * 区間の後ろの区切りの無音は判定に使わない。検証と同じく、発話の部分だけを見る。
 * 無音を除くと特徴量を作れない長さになるときは、以前と同じく区間全体を使う。
 */
internal fun speakerWindows(durationMillis: Long, config: SpeakerConfig): List<SpeakerWindow> {
    val voicedMillis = durationMillis - config.trailingSilenceMillis
    val endMillis = if (voicedMillis >= config.minWindowMillis) voicedMillis else durationMillis
    if (endMillis <= config.windowMillis) return listOf(SpeakerWindow(0, endMillis))

    val windows = (0L..endMillis - config.windowMillis step config.hopMillis)
        .map { SpeakerWindow(it, it + config.windowMillis) }
    val isAligned = windows.last().endMillis == endMillis
    return if (isAligned) windows else windows + SpeakerWindow(endMillis - config.windowMillis, endMillis)
}

/**
 * ヒステリシスで窓を自分かそれ以外に分け、続いた窓を1つのまとまりにする。境界は窓の中央の中点。
 * まとまりの長さで信頼できるかを決めるので、最後のまとまりは窓の終わりまでにして、区切りの無音を含めない。
 */
internal fun voiceRuns(windows: List<ScoredWindow>, config: SpeakerConfig): List<VoiceRun> {
    var isSelf = false
    val states = windows.map { window ->
        isSelf = window.score >= if (isSelf) config.selfExitThreshold else config.selfThreshold
        isSelf
    }
    val centers = windows.map { (it.window.startMillis + it.window.endMillis) / 2 }
    val edges = listOf(0L) + centers.zipWithNext { first, second -> (first + second) / 2 } +
        windows.last().window.endMillis

    val runs = mutableListOf<VoiceRun>()
    var from = 0
    for (index in windows.indices) {
        if (index == windows.lastIndex || states[index + 1] != states[index]) {
            val members = windows.subList(from, index + 1)
            runs += voiceRun(edges[from], edges[index + 1], states[index], members, config)
            from = index + 1
        }
    }
    return runs
}

private fun voiceRun(
    startMillis: Long,
    endMillis: Long,
    isSelf: Boolean,
    windows: List<ScoredWindow>,
    config: SpeakerConfig,
): VoiceRun {
    val score = windows.map { it.score }.average().toFloat()
    val voice = when {
        score >= config.unknownFloor && score < config.selfThreshold -> Voice.UNKNOWN
        isSelf -> Voice.SELF
        else -> Voice.OTHER
    }
    return VoiceRun(startMillis, endMillis, voice, meanOf(windows.map { it.vector }), score)
}

/**
 * 知っている他人の声と比べて、自分の判定を確かめる。[rivalScore] は最も近い他人との類似度。
 *
 * しきい値を緩めると、別の人の声も帯の近くに入る。一方で、自分の声が端末の条件で下がった発話は、
 * 他人の重心にも近くならない。そこで、確信の持てない範囲だけ、自分と他人のどちらに近いかで決める。
 */
internal fun settledVoice(run: VoiceRun, rivalScore: Float?, config: SpeakerConfig): Voice {
    if (rivalScore == null || run.score >= config.strongSelfThreshold) return run.voice
    val lead = run.score - rivalScore
    return when {
        run.voice == Voice.UNKNOWN && lead >= config.rivalMargin -> Voice.SELF
        run.voice == Voice.UNKNOWN && lead <= -config.rivalMargin -> Voice.OTHER
        run.voice == Voice.SELF && lead <= -config.selfOverrideMargin -> Voice.OTHER
        else -> run.voice
    }
}

/**
 * 分割に使うトークンを返す。時刻のないときと、つなげても本文にならないときは、本文を区間に均等に並べる。
 * これは文字数の比例配分で、時刻で分けるより粗い。検証では測っていない。
 */
internal fun timedTokens(transcription: TimedTranscription, durationMillis: Long): List<TranscriptToken> {
    val tokens = transcription.tokens
    val isUsable = tokens.isNotEmpty() &&
        tokens.joinToString("") { it.text }.withoutSpaces() == transcription.text.withoutSpaces()
    if (isUsable) return tokens

    val characters = transcription.text.characters()
    return characters.mapIndexed { index, text -> TranscriptToken(text, index * durationMillis / characters.size) }
}

private fun String.withoutSpaces(): String = filterNot(Char::isWhitespace)

/** サロゲートペアを割らずに 1 文字ずつ分ける。 */
private fun String.characters(): List<String> {
    val characters = mutableListOf<String>()
    var index = 0
    while (index < length) {
        val next = index + Character.charCount(codePointAt(index))
        characters += substring(index, next)
        index = next
    }
    return characters
}

/**
 * トークンを開始時刻でまとまりへ分ける。多数決にしない。
 *
 * 句読点のような文字だけのトークンは、前のトークンと同じまとまりに入れて、行の先頭に残さない。
 * 文字のないまとまりは行にせず、時間は前の行に含める。無音や雑音で話者が増えないように、
 * まとまりの話者を決める前に落とす。行の時刻は隙間なく続き、区間の先頭から終わりまでを覆う。
 */
internal fun assignText(
    runs: List<VoiceRun>,
    tokens: List<TranscriptToken>,
    durationMillis: Long,
): List<VoicePart> {
    val texts = List(runs.size) { StringBuilder() }
    var current = 0
    tokens.forEachIndexed { index, token ->
        if (index == 0 || token.text.any(Char::isLetterOrDigit)) {
            val found = runs.indexOfFirst { token.offsetMillis < it.endMillis }
            current = if (found >= 0) found else runs.lastIndex
        }
        texts[current].append(token.text)
    }

    val kept = runs.indices.filter { texts[it].isNotBlank() }
    return kept.mapIndexed { position, index ->
        val startMillis = if (position == 0) 0L else runs[index].startMillis
        val endMillis = kept.getOrNull(position + 1)?.let { runs[it].startMillis } ?: durationMillis
        VoicePart(runs[index], startMillis, endMillis, texts[index].toString().trim())
    }
}

/** 隣り合う同じ話者の行を 1 行にする。必要な分だけ行を分け、1 行 1 話者を保つ。 */
internal fun mergeAdjacent(turns: List<SpeakerTurn>): List<SpeakerTurn> =
    turns.fold(emptyList()) { merged, turn ->
        val last = merged.lastOrNull()
        if (last != null && last.speaker?.speakerId == turn.speaker?.speakerId) {
            merged.dropLast(1) + last.mergedWith(turn)
        } else {
            merged + turn
        }
    }

private fun SpeakerTurn.mergedWith(next: SpeakerTurn): SpeakerTurn {
    val confidence = listOfNotNull(speaker?.confidence, next.speaker?.confidence).minOrNull()
    return copy(
        endedAtMillis = next.endedAtMillis,
        text = text + next.text,
        speaker = speaker?.copy(confidence = confidence),
        voice = listOfNotNull(voice, next.voice).maxByOrNull { it.voicedMillis },
    )
}
