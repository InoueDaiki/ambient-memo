package dev.ambientmemo.engine.domain

/**
 * 発話の話者を判定する。最も近い話者がしきい値を超えればその話者にし、超えなければ仮の話者を作る。
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
    /** 話者を判定できなければ null を返す。[segment] の解放は呼び出し側に任せる。 */
    suspend fun identify(segment: SpeechSegment): SpeakerAssignment? {
        val known = speakers.listEmbeddings(embedder.modelVersion, config.maxComparedSpeakers)
        val vector = if (known.any { it.isSelf }) vectorOf(segment) else null
        return vector?.let { assign(it, known, isReliable(segment)) }
    }

    private suspend fun vectorOf(segment: SpeechSegment): FloatArray? =
        embedder.embed(segment)?.let { normalizedOrNull(it.vector) }

    private fun isReliable(segment: SpeechSegment): Boolean =
        segment.endedAtMillis - segment.startedAtMillis >= config.minReliableMillis

    private suspend fun assign(
        vector: FloatArray,
        known: List<StoredSpeakerEmbedding>,
        isReliable: Boolean,
    ): SpeakerAssignment? {
        val match = known.filter { it.vector.size == vector.size }
            .map { it to similarity(vector, it.vector) }
            .filter { (speaker, score) -> score >= thresholdOf(speaker) }
            .maxByOrNull { (_, score) -> score }
        return when {
            match != null -> {
                val (speaker, score) = match
                if (isReliable && !speaker.isSelf) absorb(speaker, vector)
                SpeakerAssignment(speaker.speakerId, score.toDouble().coerceIn(0.0, 1.0))
            }
            isReliable -> {
                val embedding = SpeakerEmbedding(vector, embedder.modelVersion)
                SpeakerAssignment(speakers.createProvisionalSpeaker(embedding, clock.nowMillis()).id, confidence = null)
            }
            else -> null
        }
    }

    private fun thresholdOf(speaker: StoredSpeakerEmbedding): Float =
        if (speaker.isSelf) config.selfThreshold else config.provisionalThreshold

    private suspend fun absorb(speaker: StoredSpeakerEmbedding, vector: FloatArray) {
        val mean = weightedMean(speaker.vector, speaker.sampleCount, vector) ?: return
        speakers.updateEmbedding(
            speaker.speakerId,
            SpeakerEmbedding(mean, embedder.modelVersion),
            speaker.sampleCount + 1,
            clock.nowMillis(),
        )
    }
}
