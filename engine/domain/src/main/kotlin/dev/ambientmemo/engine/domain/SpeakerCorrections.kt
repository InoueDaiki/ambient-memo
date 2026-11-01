package dev.ambientmemo.engine.domain

/**
 * 最近の発話の声をメモリに持つ。手で直したときに、その声を以後の判定へ反映するために使う。
 *
 * DB には書かない。発話ごとの特徴量を残し続けないため。プロセスが終わると消え、古い発話を直しても話者の付け替えだけになる。
 */
class RecentVoices(private val capacity: Int = DEFAULT_CAPACITY) {
    private val voices = object : LinkedHashMap<Long, TurnVoice>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TurnVoice>): Boolean = size > capacity
    }

    @Synchronized
    fun remember(utteranceId: Long, voice: TurnVoice) {
        voices[utteranceId] = voice
    }

    /** 声は 1 度だけ使う。同じ発話を何度か直しても、重心に二重に入れない。 */
    @Synchronized
    fun take(utteranceId: Long): TurnVoice? = voices.remove(utteranceId)

    @Synchronized
    fun clear() = voices.clear()

    private companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

/** 発話を手で直す先。 */
sealed interface SpeakerTarget {
    data object Self : SpeakerTarget

    data class Existing(val speakerId: Long) : SpeakerTarget

    /** 新しい仮の話者。上限に達していれば直せない。 */
    data object NewOther : SpeakerTarget

    data object Unknown : SpeakerTarget
}

/** 話者を手で直す。直した結果は以後の判定に反映する。 */
@Suppress("TooManyFunctions") // 3 つの操作を手順ごとに分けている
class SpeakerCorrections(
    private val speakers: SpeakerRepository,
    private val utterances: UtteranceRepository,
    private val voices: RecentVoices,
    private val modelVersion: String,
    private val clock: Clock,
    private val config: SpeakerConfig = SpeakerConfig(),
) {
    /** 発話の話者を直す。声がメモリにあれば、直した先の重心へ足す。発話や話者がなければ false を返す。 */
    suspend fun reassign(utteranceId: Long, target: SpeakerTarget): Boolean {
        val found = utterances.find(utteranceId) != null
        return found && when (target) {
            SpeakerTarget.Unknown -> utterances.setSpeaker(utteranceId, null)
            SpeakerTarget.NewOther -> createOther(utteranceId)?.let { utterances.setSpeaker(utteranceId, it) } ?: false
            SpeakerTarget.Self -> moveTo(utteranceId, embeddings().firstOrNull { it.isSelf }?.speakerId)
            is SpeakerTarget.Existing -> moveTo(utteranceId, target.speakerId.takeIf { exists(it) })
        }
    }

    private suspend fun moveTo(utteranceId: Long, speakerId: Long?): Boolean {
        if (speakerId == null) return false
        utterances.setSpeaker(utteranceId, speakerId)
        voices.take(utteranceId)?.let { learn(speakerId, it) }
        return true
    }

    private suspend fun exists(speakerId: Long) = speakers.listSpeakers().any { it.id == speakerId }

    private suspend fun embeddings() = speakers.listEmbeddings(modelVersion, Int.MAX_VALUE)

    private suspend fun createOther(utteranceId: Long): Long? {
        val provisional = speakers.listSpeakers().count { it.nameSource == SpeakerNameSource.PROVISIONAL }
        if (provisional >= config.maxProvisionalSpeakers) return null
        val voice = voices.take(utteranceId)?.takeIf { it.vector.isNotEmpty() }
        val nowMillis = clock.nowMillis()
        val speaker = if (voice == null) {
            speakers.createProvisionalSpeakerWithoutVoice(nowMillis)
        } else {
            val embedding = SpeakerEmbedding(voice.vector, modelVersion)
            speakers.createProvisionalSpeaker(embedding, nowMillis, voice.voicedMillis)
        }
        return speaker.id
    }

    private suspend fun learn(speakerId: Long, voice: TurnVoice) {
        val stored = embeddings().firstOrNull { it.speakerId == speakerId }
        when {
            stored == null -> speakers.updateEmbedding(
                speakerId,
                SpeakerEmbedding(voice.vector, modelVersion),
                1,
                clock.nowMillis(),
                voice.voicedMillis,
            )
            stored.vector.size != voice.vector.size -> Unit
            stored.isSelf -> learnSelf(stored, voice)
            else -> weightedMean(stored.vector, stored.voicedMillis, voice.vector, voice.voicedMillis)?.let { mean ->
                speakers.updateEmbedding(
                    speakerId,
                    SpeakerEmbedding(mean, modelVersion),
                    stored.sampleCount + 1,
                    clock.nowMillis(),
                    stored.voicedMillis + voice.voicedMillis,
                )
            }
        }
    }

    private suspend fun learnSelf(self: StoredSpeakerEmbedding, voice: TurnVoice) {
        val adaptation = speakers.findSelfAdaptation(modelVersion)?.takeIf { it.vector.size == voice.vector.size }
        learnedSelfVoice(self.vector, adaptation, voice, modelVersion, config)
            ?.let { speakers.saveSelfAdaptation(it, clock.nowMillis()) }
    }

    /**
     * [fromId] を [intoId] へ統合する。手の操作なので、手で名付けた話者も統合する。自分は統合元にできない。
     * 自分へ統合すると発話は「自分」になり、統合元の重心は育てた自分の声へ足す。登録の特徴量は変えない。
     */
    suspend fun merge(fromId: Long, intoId: Long): Boolean {
        val all = speakers.listSpeakers()
        val from = all.firstOrNull { it.id == fromId }
        val into = all.firstOrNull { it.id == intoId }
        val canMerge = from != null && into != null && !from.isSelf && fromId != intoId
        if (canMerge) apply(fromId, intoId, checkNotNull(into).isSelf)
        return canMerge
    }

    private suspend fun apply(fromId: Long, intoId: Long, intoSelf: Boolean) {
        val stored = embeddings().associateBy { it.speakerId }
        val from = stored[fromId]
        val into = stored[intoId]
        val merged = if (intoSelf) null else mergedEmbedding(from, into)
        speakers.mergeSpeakers(listOf(SpeakerMerge(fromId, intoId, merged)), clock.nowMillis())
        val isComparable = from != null && into != null && from.vector.size == into.vector.size
        if (intoSelf && isComparable) {
            learnSelf(checkNotNull(into), TurnVoice(checkNotNull(from).vector, from.voicedMillis.coerceAtLeast(1)))
        }
    }

    private fun mergedEmbedding(from: StoredSpeakerEmbedding?, into: StoredSpeakerEmbedding?): MergedEmbedding? = when {
        from == null -> null
        into == null || into.vector.size != from.vector.size -> MergedEmbedding(
            SpeakerEmbedding(from.vector, modelVersion),
            from.sampleCount,
            from.voicedMillis,
        )
        else -> weightedMean(
            into.vector,
            into.voicedMillis.coerceAtLeast(1),
            from.vector,
            from.voicedMillis.coerceAtLeast(1),
        )?.let {
            MergedEmbedding(
                SpeakerEmbedding(it, modelVersion),
                into.sampleCount + from.sampleCount,
                into.voicedMillis + from.voicedMillis,
            )
        }
    }

    /** 名前を手で付ける。空の名前は受け付けない。 */
    suspend fun rename(speakerId: Long, name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() && speakers.setManualName(speakerId, trimmed)
    }
}
