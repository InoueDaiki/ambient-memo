package dev.ambientmemo.engine.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 発話区間の中で話者を判定し、話者の交代で本文を分ける。
 *
 * 区間の内側を窓に分けて自分との類似度を取り、ヒステリシスで自分かそれ以外に分ける。それ以外のまとまりは、
 * 既存の話者に照合し、近い話者がいなければ上限まで仮の話者を作る。手順と値の根拠は docs/検証-話者区間.md にある。
 *
 * 自分の声が登録されるまでは何もしない。「自分」のラベルがないと ToDo の抽出が自分の発話を見分けられず、
 * 特徴量を作る電池も無駄になる。そのため自分の登録より前の発話の話者は null のまま残る。
 *
 * 登録の特徴量は変えない。確信の高い発話だけを別の [SelfAdaptation] にゆっくり足し、類似度を少しだけ上げるのに使う。
 * 他人の声を誤って学んでも効く幅は小さく、登録し直せば消える。仮の話者の特徴量は、十分な長さの発話で更新する。
 */
@Suppress("TooManyFunctions") // 判定の手順を順に分けている。状態は持たない
class SpeakerIdentifier(
    private val embedder: SpeakerEmbedder,
    private val speakers: SpeakerRepository,
    private val clock: Clock,
    private val config: SpeakerConfig = SpeakerConfig(),
    private val decisionLog: SpeakerDecisionLog? = null,
) {
    /**
     * 話者の続く範囲を時刻の順に返す。範囲の時刻は隙間なく続き、隣は違う話者になる。
     * 話者を判定できなければ、区間全体を不明の 1 行にして返す。[segment] の解放は呼び出し側に任せる。
     */
    suspend fun identify(segment: SpeechSegment, transcription: TimedTranscription): List<SpeakerTurn> {
        val self = speakers.listEmbeddings(embedder.modelVersion, config.maxComparedSpeakers).firstOrNull { it.isSelf }
        val adaptation = self?.let { speakers.findSelfAdaptation(embedder.modelVersion) }
            ?.takeIf { it.vector.size == self.vector.size }
        val windows = self?.let { scoredWindows(segment, it, adaptation) }.orEmpty()
        return if (self == null || windows.isEmpty()) {
            listOf(SpeakerTurn(segment.startedAtMillis, segment.endedAtMillis, transcription.text, speaker = null))
        } else {
            turnsOf(segment, transcription, self, windows)
        }
    }

    private suspend fun scoredWindows(
        segment: SpeechSegment,
        self: StoredSpeakerEmbedding,
        adaptation: SelfAdaptation?,
    ): List<ScoredWindow> {
        val windows = speakerWindows(segment.endedAtMillis - segment.startedAtMillis, config)
        return windows.mapNotNull { window ->
            currentCoroutineContext().ensureActive()
            vectorOf(segment, window)
                ?.takeIf { it.size == self.vector.size }
                ?.let { ScoredWindow(window, it, selfScore(it, self, adaptation)) }
        }
    }

    /** 登録と比べる。育てた声のほうが近ければ、[SpeakerConfig.adaptMaxBoost] の分まで上げる。 */
    private fun selfScore(vector: FloatArray, self: StoredSpeakerEmbedding, adaptation: SelfAdaptation?): Float {
        val enrolled = similarity(vector, self.vector)
        val adapted = adaptation?.let { similarity(vector, it.vector) } ?: return enrolled
        return maxOf(enrolled, minOf(adapted, enrolled + config.adaptMaxBoost))
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
        val decisions = mutableListOf<SpeakerDecisionSample>()
        val turns = parts.map { part ->
            val speaker = assignmentOf(part.run, self, isLongSegment, decisions)
            SpeakerTurn(
                segment.startedAtMillis + part.startMillis,
                segment.startedAtMillis + part.endMillis,
                part.text,
                speaker,
                part.run.vector?.let { TurnVoice(it, part.run.millis) },
            )
        }
        decisionLog?.record(decisions, clock.nowMillis())
        return mergeAdjacent(turns)
    }

    private suspend fun assignmentOf(
        run: VoiceRun,
        self: StoredSpeakerEmbedding,
        isLongSegment: Boolean,
        decisions: MutableList<SpeakerDecisionSample>,
    ): SpeakerAssignment? {
        val others = run.vector?.let { othersFor(it) }.orEmpty()
        val rival = run.vector?.let { vector -> others.maxOfOrNull { similarity(vector, it.vector) } }
        val assignment = when (settledVoice(run, rival, config)) {
            Voice.SELF -> {
                learnSelf(run, self, rival)
                SpeakerAssignment(self.speakerId, run.score.toDouble().coerceIn(0.0, 1.0))
            }
            Voice.UNKNOWN -> null
            Voice.OTHER -> run.vector?.let { assignOther(it, others, isLongSegment, run.millis) }
        }
        val decision = when (assignment?.speakerId) {
            null -> SpeakerDecision.UNKNOWN
            self.speakerId -> SpeakerDecision.SELF
            else -> SpeakerDecision.OTHER
        }
        decisions += SpeakerDecisionSample(decision, run.score, run.millis)
        return assignment
    }

    /** 同じ区間の前のまとまりで作った話者とも比べるので、まとまりごとに読み直す。 */
    private suspend fun othersFor(vector: FloatArray): List<StoredSpeakerEmbedding> =
        speakers.listEmbeddings(embedder.modelVersion, config.maxComparedSpeakers)
            .filter { !it.isSelf && it.vector.size == vector.size }

    /**
     * 高い確信の発話だけを自分の声に足す。他人の声で崩さないように、登録に近いことと、
     * 知っている他人より十分に自分に近いことを確かめる。
     */
    private suspend fun learnSelf(run: VoiceRun, self: StoredSpeakerEmbedding, rival: Float?) {
        val vector = run.vector ?: return
        val isConfident = run.score >= config.adaptMinScore &&
            run.millis >= config.adaptMinRunMillis &&
            similarity(vector, self.vector) >= config.adaptMinEnrolledScore &&
            (rival == null || run.score - rival >= config.adaptRivalMargin)
        if (!isConfident) return
        val stored = speakers.findSelfAdaptation(embedder.modelVersion)?.takeIf { it.vector.size == vector.size }
        learnedSelfVoice(self.vector, stored, TurnVoice(vector, run.millis), embedder.modelVersion, config)
            ?.let { speakers.saveSelfAdaptation(it, clock.nowMillis()) }
    }

    private suspend fun assignOther(
        vector: FloatArray,
        others: List<StoredSpeakerEmbedding>,
        isLongSegment: Boolean,
        runMillis: Long,
    ): SpeakerAssignment? {
        val isReliable = isLongSegment && runMillis >= config.minReliableRunMillis
        val match = others.map { it to similarity(vector, it.vector) }.maxByOrNull { (_, score) -> score }
        val provisional = others.filter { it.nameSource == SpeakerNameSource.PROVISIONAL }
        val canCreate = isLongSegment && runMillis >= config.minNewSpeakerRunMillis
        val isFull = provisional.size >= config.maxProvisionalSpeakers
        return when {
            match != null && match.second >= config.provisionalThreshold -> {
                val (speaker, score) = match
                if (isReliable) absorb(speaker, vector, runMillis)
                SpeakerAssignment(speaker.speakerId, score.toDouble().coerceIn(0.0, 1.0))
            }
            !isFull && canCreate -> SpeakerAssignment(create(vector, runMillis).id, confidence = null)
            isFull -> reuseOrNearest(vector, provisional, canCreate, runMillis)
            else -> null
        }
    }

    /**
     * 上限に達したら話者を増やさない。長く使っていない仮の話者がいれば、その席を新しい声に譲る。
     * いなければ最も近い仮の話者に入れる。遠い声なので、重心は動かさない。
     */
    private suspend fun reuseOrNearest(
        vector: FloatArray,
        provisional: List<StoredSpeakerEmbedding>,
        canCreate: Boolean,
        runMillis: Long,
    ): SpeakerAssignment? {
        val nowMillis = clock.nowMillis()
        val stale = provisional.filter { nowMillis - it.updatedAtMillis >= config.staleProvisionalMillis }
            .minByOrNull { it.updatedAtMillis }
        return if (stale != null && canCreate) {
            val embedding = SpeakerEmbedding(vector, embedder.modelVersion)
            speakers.updateEmbedding(stale.speakerId, embedding, 1, nowMillis, runMillis)
            SpeakerAssignment(stale.speakerId, confidence = null)
        } else {
            provisional.map { it to similarity(vector, it.vector) }
                .maxByOrNull { (_, score) -> score }
                ?.let { (speaker, score) -> SpeakerAssignment(speaker.speakerId, score.toDouble().coerceIn(0.0, 1.0)) }
        }
    }

    private suspend fun create(vector: FloatArray, runMillis: Long): Speaker = speakers.createProvisionalSpeaker(
        SpeakerEmbedding(vector, embedder.modelVersion),
        clock.nowMillis(),
        runMillis,
    )

    private suspend fun absorb(speaker: StoredSpeakerEmbedding, vector: FloatArray, runMillis: Long) {
        val mean = weightedMean(speaker.vector, speaker.voicedMillis.coerceAtLeast(1), vector, runMillis) ?: return
        speakers.updateEmbedding(
            speaker.speakerId,
            SpeakerEmbedding(mean, embedder.modelVersion),
            speaker.sampleCount + 1,
            clock.nowMillis(),
            speaker.voicedMillis + runMillis,
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
