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
    /**
     * 自分に入るコサイン類似度。不明の帯の上端も兼ねる。docs/検証-話者区間.md の推薦の値。
     * 0.05 の違いで誤受入も再現率も大きく動き、別の話者の集団で決めた値は持ち越しにくいと分かっている。
     */
    val selfThreshold: Float = DEFAULT_THRESHOLD,
    /** 自分から出るコサイン類似度。これ以上なら自分のまま続く。 */
    val selfExitThreshold: Float = DEFAULT_SELF_EXIT_THRESHOLD,
    /** 不明の帯の下端。同じ判定が続くまとまりの平均類似度が、これ以上 [selfThreshold] 未満なら不明にする。 */
    val unknownFloor: Float = DEFAULT_UNKNOWN_FLOOR,
    /** 既存の仮の話者と判定するコサイン類似度。自分と同じ値から始める。 */
    val provisionalThreshold: Float = DEFAULT_THRESHOLD,
    /** 判定する窓の長さ。区間の発話の部分がこれ以下なら、全体で 1 つの窓にする。 */
    val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    /** 窓をずらす幅。 */
    val hopMillis: Long = DEFAULT_HOP_MILLIS,
    /** 区間の後ろにある区切りの無音の長さ。窓に入れない。アプリの VAD の区切りの無音と揃える。 */
    val trailingSilenceMillis: Long = DEFAULT_TRAILING_SILENCE_MILLIS,
    /** 無音を除いた長さがこれより短いときは、無音を除かない。特徴量を作れる下限。 */
    val minWindowMillis: Long = DEFAULT_MIN_WINDOW_MILLIS,
    /** これより短い区間は既存の話者との照合だけに使い、仮の話者を作らず、平均にも入れない。 */
    val minReliableMillis: Long = DEFAULT_MIN_RELIABLE_MILLIS,
    /** 長い区間の中でも、これより短いまとまりは [minReliableMillis] と同じ扱いにする。無音を含まないので短い。 */
    val minReliableRunMillis: Long = DEFAULT_MIN_RELIABLE_RUN_MILLIS,
    /** 照合する話者の上限。周りの声で仮の話者が増えても、発話ごとの処理を増やし続けない。 */
    val maxComparedSpeakers: Int = DEFAULT_MAX_COMPARED_SPEAKERS,
) {
    init {
        require(selfThreshold in 0f..1f) { "selfThreshold は 0 から 1 の範囲です" }
        require(selfExitThreshold in 0f..selfThreshold) { "selfExitThreshold は 0 から selfThreshold の範囲です" }
        require(unknownFloor in 0f..selfExitThreshold) { "unknownFloor は 0 から selfExitThreshold の範囲です" }
        require(provisionalThreshold in 0f..1f) { "provisionalThreshold は 0 から 1 の範囲です" }
        require(windowMillis > 0) { "windowMillis は正の値です" }
        require(hopMillis > 0) { "hopMillis は正の値です" }
        require(trailingSilenceMillis >= 0) { "trailingSilenceMillis は 0 以上です" }
        require(minWindowMillis >= 0) { "minWindowMillis は 0 以上です" }
        require(minReliableMillis >= 0) { "minReliableMillis は 0 以上です" }
        require(minReliableRunMillis >= 0) { "minReliableRunMillis は 0 以上です" }
        require(maxComparedSpeakers > 0) { "maxComparedSpeakers は正の値です" }
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 0.6f
        const val DEFAULT_SELF_EXIT_THRESHOLD = 0.55f
        const val DEFAULT_UNKNOWN_FLOOR = 0.45f
        const val DEFAULT_WINDOW_MILLIS = 1_500L
        const val DEFAULT_HOP_MILLIS = 750L
        const val DEFAULT_TRAILING_SILENCE_MILLIS = 800L
        const val DEFAULT_MIN_WINDOW_MILLIS = 400L

        // 検証で、無音を含まないまとまりに使った値
        const val DEFAULT_MIN_RELIABLE_RUN_MILLIS = 1_000L

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
