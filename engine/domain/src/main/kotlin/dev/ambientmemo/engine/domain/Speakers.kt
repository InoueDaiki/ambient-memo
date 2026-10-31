package dev.ambientmemo.engine.domain

import kotlin.math.sqrt

/** 話者の名前の由来。推定は、自分と手動の名前を上書きしない。 */
enum class SpeakerNameSource {
    /** 自分の声の登録で付けた。 */
    SELF,

    /** 特徴量のまとまりに付けた仮の名前。 */
    PROVISIONAL,

    /** 会話から推定した。 */
    INFERRED,

    /** 人が手で付けた。 */
    MANUAL,
}

data class Speaker(
    val id: Long,
    val displayName: String,
    val nameSource: SpeakerNameSource,
    val isSelf: Boolean,
    val createdAtMillis: Long,
)

/** 保存済みの話者の特徴量。[vector] は長さ 1 に正規化してある。 */
class StoredSpeakerEmbedding(
    val speakerId: Long,
    val isSelf: Boolean,
    val vector: FloatArray,
    val sampleCount: Int,
)

/** 発話の話者の判定結果。[confidence] は最も近い話者とのコサイン類似度。新しい仮の話者は比べる相手がなく null。 */
data class SpeakerAssignment(
    val speakerId: Long,
    val confidence: Double?,
) {
    init {
        requireConfidence(confidence)
    }
}

/** 話者の照合の既定値。すべて仮で、端末で自分の声に合わせて調整する前提。 */
data class SpeakerConfig(
    /** 自分と判定するコサイン類似度。検証の開始値。別の話者の集団で決めた値は持ち越しにくいと分かっている。 */
    val selfThreshold: Float = DEFAULT_THRESHOLD,
    /** 既存の仮の話者と判定するコサイン類似度。自分と同じ値から始める。 */
    val provisionalThreshold: Float = DEFAULT_THRESHOLD,
    /** これより短い区間は既存の話者との照合だけに使い、仮の話者を作らず、平均にも入れない。 */
    val minReliableMillis: Long = DEFAULT_MIN_RELIABLE_MILLIS,
    /** 照合する話者の上限。周りの声で仮の話者が増えても、発話ごとの処理を増やし続けない。 */
    val maxComparedSpeakers: Int = DEFAULT_MAX_COMPARED_SPEAKERS,
) {
    init {
        require(selfThreshold in 0f..1f) { "selfThreshold は 0 から 1 の範囲です" }
        require(provisionalThreshold in 0f..1f) { "provisionalThreshold は 0 から 1 の範囲です" }
        require(minReliableMillis >= 0) { "minReliableMillis は 0 以上です" }
        require(maxComparedSpeakers > 0) { "maxComparedSpeakers は正の値です" }
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 0.6f

        // アプリの区間は前後に約 1.4 秒の無音を含むので、実音声は約 1.1 秒。検証の 1.5 秒の EER は 1.4%
        const val DEFAULT_MIN_RELIABLE_MILLIS = 2_500L
        const val DEFAULT_MAX_COMPARED_SPEAKERS = 64
    }
}

/**
 * 整理ジョブの文字起こしに付ける話者のラベル。
 *
 * プロンプトは「自分」を端末の持ち主として説明しているので、名前を変えても「自分」のままにする。
 * 仮の話者も含め、話者が分かる発話だけにラベルを付ける。
 */
internal class SpeakerLabels(speakers: List<Speaker>) {
    private val labels = speakers.associate { speaker ->
        speaker.id to if (speaker.isSelf) SELF_LABEL else speaker.displayName.toLabel()
    }

    fun of(utterance: Utterance): String? = utterance.speakerId?.let(labels::get)?.takeIf { it.isNotEmpty() }

    /** 手で付けた名前で、文字起こしの行の形を崩さない。 */
    private fun String.toLabel(): String = replace(LINE_BREAKING, " ").trim()

    companion object {
        val NONE = SpeakerLabels(emptyList())
        private const val SELF_LABEL = "自分"
        private val LINE_BREAKING = Regex("[\\s:：]+")
    }
}

/** 長さ 1 に正規化する。無音などで長さ 0 か、数でない値が入っていると比べられないので null を返す。 */
internal fun normalizedOrNull(vector: FloatArray): FloatArray? {
    val length = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
    val isUsable = length > 0f && length.isFinite()
    return if (isUsable) FloatArray(vector.size) { vector[it] / length } else null
}

/** 長さ 1 のベクトルのコサイン類似度。 */
internal fun similarity(first: FloatArray, second: FloatArray): Float {
    require(first.size == second.size) { "特徴量の次元が違います" }
    return first.indices.fold(0f) { sum, index -> sum + first[index] * second[index] }
}

/** 長さ 1 のベクトルの重み付き平均を、長さ 1 に戻す。 */
internal fun weightedMean(mean: FloatArray, sampleCount: Int, added: FloatArray): FloatArray? {
    require(mean.size == added.size) { "特徴量の次元が違います" }
    return normalizedOrNull(FloatArray(mean.size) { mean[it] * sampleCount + added[it] })
}

/** 長さ 1 のベクトルの平均を、長さ 1 に戻す。 */
internal fun meanOf(vectors: List<FloatArray>): FloatArray? {
    val first = vectors.firstOrNull() ?: return null
    require(vectors.all { it.size == first.size }) { "特徴量の次元が違います" }
    return normalizedOrNull(FloatArray(first.size) { index -> vectors.sumOf { it[index].toDouble() }.toFloat() })
}
