package dev.ambientmemo.engine.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 発話区間の中で話者を判定し、話者の交代で本文を分ける。
 *
 * 区間の内側を窓に分けて自分との類似度を取り、ヒステリシスで自分かそれ以外に分ける。それ以外のまとまりは、
 * 既存の仮の話者に照合し、近い話者がいなければ仮の話者を作る。手順と値の根拠は docs/検証-話者区間.md にある。
 *
 * 自分の声が登録されるまでは何もしない。「自分」のラベルがないと ToDo の抽出が自分の発話を見分けられず、
 * 特徴量を作る電池も無駄になる。そのため自分の登録より前の発話の話者は null のまま残る。
 *
 * 自分の特徴量は登録のときの平均のまま変えない。誤って受け入れた他人の声で、登録が崩れないようにする。
 * 仮の話者の特徴量は、十分な長さの発話で更新する。
 */
class SpeakerIdentifier(
    private val embedder: SpeakerEmbedder,
    private val speakers: SpeakerRepository,
    private val clock: Clock,
    private val config: SpeakerConfig = SpeakerConfig(),
) {
    /**
     * 話者の続く範囲を時刻の順に返す。範囲の時刻は隙間なく続き、隣は違う話者になる。
     * 話者を判定できなければ、区間全体を不明の 1 行にして返す。[segment] の解放は呼び出し側に任せる。
     */
    suspend fun identify(segment: SpeechSegment, transcription: TimedTranscription): List<SpeakerTurn> {
        val self = speakers.listEmbeddings(embedder.modelVersion, config.maxComparedSpeakers).firstOrNull { it.isSelf }
        val windows = self?.let { scoredWindows(segment, it) }.orEmpty()
        return if (self == null || windows.isEmpty()) {
            listOf(SpeakerTurn(segment.startedAtMillis, segment.endedAtMillis, transcription.text, speaker = null))
        } else {
            turnsOf(segment, transcription, self, windows)
        }
    }

    private suspend fun scoredWindows(segment: SpeechSegment, self: StoredSpeakerEmbedding): List<ScoredWindow> =
        speakerWindows(segment.endedAtMillis - segment.startedAtMillis, config).mapNotNull { window ->
            currentCoroutineContext().ensureActive()
            vectorOf(segment, window)
                ?.takeIf { it.size == self.vector.size }
                ?.let { ScoredWindow(window, it, similarity(it, self.vector)) }
        }

    /** 窓の音声はコピーなので、例外やキャンセルでも `use` で 0 に塗りつぶす。 */
    private suspend fun vectorOf(segment: SpeechSegment, window: SpeakerWindow): FloatArray? =
        segment.slice(window).use { slice -> embedder.embed(slice) }?.let { normalizedOrNull(it.vector) }

    private fun SpeechSegment.slice(window: SpeakerWindow) = SpeechSegment(
        startedAtMillis + window.startMillis,
        startedAtMillis + window.endMillis,
        sampleRateHz,
        samples.copyOfRange(sampleIndex(window.startMillis), sampleIndex(window.endMillis)),
    )

    private fun SpeechSegment.sampleIndex(offsetMillis: Long): Int =
        (offsetMillis * sampleRateHz / MILLIS_PER_SECOND).coerceAtMost(sampleCount.toLong()).toInt()

    private suspend fun turnsOf(
        segment: SpeechSegment,
        transcription: TimedTranscription,
        self: StoredSpeakerEmbedding,
        windows: List<ScoredWindow>,
    ): List<SpeakerTurn> {
        val durationMillis = segment.endedAtMillis - segment.startedAtMillis
        val runs = voiceRuns(windows, config)
        val parts = assignText(runs, timedTokens(transcription, durationMillis), durationMillis)
        val isLongSegment = durationMillis >= config.minReliableMillis
        return mergeAdjacent(
            parts.map { part ->
                SpeakerTurn(
                    segment.startedAtMillis + part.startMillis,
                    segment.startedAtMillis + part.endMillis,
                    part.text,
                    assignmentOf(part.run, self, isLongSegment),
                )
            },
        )
    }

    private suspend fun assignmentOf(
        run: VoiceRun,
        self: StoredSpeakerEmbedding,
        isLongSegment: Boolean,
    ): SpeakerAssignment? = when (run.voice) {
        Voice.SELF -> SpeakerAssignment(self.speakerId, run.score.toDouble().coerceIn(0.0, 1.0))
        Voice.UNKNOWN -> null
        Voice.OTHER -> run.vector?.let { vector ->
            assignOther(vector, isLongSegment && run.endMillis - run.startMillis >= config.minReliableRunMillis)
        }
    }

    /** 同じ区間の前のまとまりで作った話者とも比べるので、まとまりごとに読み直す。 */
    private suspend fun assignOther(vector: FloatArray, isReliable: Boolean): SpeakerAssignment? {
        val match = speakers.listEmbeddings(embedder.modelVersion, config.maxComparedSpeakers)
            .filter { !it.isSelf && it.vector.size == vector.size }
            .map { it to similarity(vector, it.vector) }
            .filter { (_, score) -> score >= config.provisionalThreshold }
            .maxByOrNull { (_, score) -> score }
        return when {
            match != null -> {
                val (speaker, score) = match
                if (isReliable) absorb(speaker, vector)
                SpeakerAssignment(speaker.speakerId, score.toDouble().coerceIn(0.0, 1.0))
            }
            isReliable -> {
                val embedding = SpeakerEmbedding(vector, embedder.modelVersion)
                SpeakerAssignment(speakers.createProvisionalSpeaker(embedding, clock.nowMillis()).id, confidence = null)
            }
            else -> null
        }
    }

    private suspend fun absorb(speaker: StoredSpeakerEmbedding, vector: FloatArray) {
        val mean = weightedMean(speaker.vector, speaker.sampleCount, vector) ?: return
        speakers.updateEmbedding(
            speaker.speakerId,
            SpeakerEmbedding(mean, embedder.modelVersion),
            speaker.sampleCount + 1,
            clock.nowMillis(),
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
