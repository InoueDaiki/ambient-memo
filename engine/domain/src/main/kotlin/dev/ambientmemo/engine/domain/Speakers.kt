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
@Suppress("LongParameterList") // 行の列をそのまま持つ
class StoredSpeakerEmbedding(
    val speakerId: Long,
    val isSelf: Boolean,
    val vector: FloatArray,
    val sampleCount: Int,
    /** 重心に入れた発話の長さの合計。 */
    val voicedMillis: Long,
    val nameSource: SpeakerNameSource,
    val updatedAtMillis: Long,
)

/**
 * 聞き取りの音声で育てた自分の声。登録の特徴量は上書きせず、別に持つ。
 * 端末の置き場所や雑音で下がった類似度を補う。[vector] は長さ 1 に正規化してある。
 */
class SelfAdaptation(
    val vector: FloatArray,
    val voicedMillis: Long,
    val modelVersion: String,
)

/**
 * 話者の統合を 1 件表す。[fromId] の発話を [intoId] へ移し、[fromId] を消す。
 * [merged] は統合後の [intoId] の特徴量。null なら [intoId] の特徴量を変えない。
 */
class SpeakerMerge(
    val fromId: Long,
    val intoId: Long,
    val merged: MergedEmbedding?,
)

class MergedEmbedding(
    val embedding: SpeakerEmbedding,
    val sampleCount: Int,
    val voicedMillis: Long,
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
@Suppress("LongParameterList") // 値はすべてここに集める
data class SpeakerConfig(
    /**
     * 自分に入るコサイン類似度。不明の帯の上端も兼ねる。docs/検証-話者区間.md の実機に寄せた測定で決めた仮の値。
     * 0.05 の違いで誤受入も再現率も大きく動き、別の話者の集団で決めた値は持ち越しにくいと分かっている。
     */
    val selfThreshold: Float = DEFAULT_SELF_THRESHOLD,
    /** 自分から出るコサイン類似度。これ以上なら自分のまま続く。 */
    val selfExitThreshold: Float = DEFAULT_SELF_EXIT_THRESHOLD,
    /** 不明の帯の下端。同じ判定が続くまとまりの平均類似度が、これ以上 [selfThreshold] 未満なら不明にする。 */
    val unknownFloor: Float = DEFAULT_UNKNOWN_FLOOR,
    /** 既存の話者と判定するコサイン類似度。短い窓の特徴量は同じ人でもばらつくので、自分より緩くしてよい。 */
    val provisionalThreshold: Float = DEFAULT_PROVISIONAL_THRESHOLD,
    /** 仮の話者の上限。達したら増やさず、最も近い仮の話者に入れる。手で名付けた話者は数えない。 */
    val maxProvisionalSpeakers: Int = DEFAULT_MAX_PROVISIONAL_SPEAKERS,
    /** 新しい仮の話者を作るのに要るまとまりの長さ。短い窓のばらつきで話者が増えないようにする。 */
    val minNewSpeakerRunMillis: Long = DEFAULT_MIN_NEW_SPEAKER_RUN_MILLIS,
    /** 上限に達していて、これだけ更新のない仮の話者は、新しい声に席を譲る。 */
    val staleProvisionalMillis: Long = DEFAULT_STALE_PROVISIONAL_MILLIS,
    /** まとめ直しで、仮の話者の数が上限以下でも統合する類似度。 */
    val consolidationThreshold: Float = DEFAULT_PROVISIONAL_THRESHOLD,
    /** これ以上自分に近いまとまりは、他人と比べずに自分の判定のままにする。 */
    val strongSelfThreshold: Float = DEFAULT_STRONG_SELF_THRESHOLD,
    /** 不明の帯を、知っている他人と比べて決めるのに要る類似度の差。 */
    val rivalMargin: Float = DEFAULT_RIVAL_MARGIN,
    /** 弱い自分の判定を、知っている他人に変えるのに要る類似度の差。自分を他人としにくいように大きくする。 */
    val selfOverrideMargin: Float = DEFAULT_SELF_OVERRIDE_MARGIN,
    /** 自分の声に足すのに要る、自分との類似度。 */
    val adaptMinScore: Float = DEFAULT_ADAPT_MIN_SCORE,
    /** 自分の声に足すのに要る、登録の特徴量との類似度。更新が登録から離れていかないようにする。 */
    val adaptMinEnrolledScore: Float = DEFAULT_ADAPT_MIN_ENROLLED_SCORE,
    /** 自分の声に足すのに要る、知っている他人との類似度の差。 */
    val adaptRivalMargin: Float = DEFAULT_ADAPT_RIVAL_MARGIN,
    /** 自分の声に足すのに要るまとまりの長さ。 */
    val adaptMinRunMillis: Long = DEFAULT_ADAPT_MIN_RUN_MILLIS,
    /**
     * 育てた自分の声で類似度を上げられる幅。登録との類似度にこれを足した値までしか上げない。
     * 育てた声は部屋や端末の癖も学ぶので、同じ場所にいる他人の類似度も上がる。誤受入を抑える。
     */
    val adaptMaxBoost: Float = DEFAULT_ADAPT_MAX_BOOST,
    /** 自分の声の重みの下限。最初の発話で大きく動かないように、登録の重みとして扱う。 */
    val adaptBaseMillis: Long = DEFAULT_ADAPT_BASE_MILLIS,
    /** 自分の声の重みの上限。古い発話の影響を残しすぎず、1 つの発話で動く量も抑える。 */
    val adaptMaxMillis: Long = DEFAULT_ADAPT_MAX_MILLIS,
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
        require(maxProvisionalSpeakers > 0) { "maxProvisionalSpeakers は正の値です" }
        require(minNewSpeakerRunMillis >= 0) { "minNewSpeakerRunMillis は 0 以上です" }
        require(staleProvisionalMillis > 0) { "staleProvisionalMillis は正の値です" }
        require(consolidationThreshold in 0f..1f) { "consolidationThreshold は 0 から 1 の範囲です" }
        require(strongSelfThreshold in selfThreshold..1f) { "strongSelfThreshold は selfThreshold から 1 の範囲です" }
        require(rivalMargin >= 0f) { "rivalMargin は 0 以上です" }
        require(selfOverrideMargin >= 0f) { "selfOverrideMargin は 0 以上です" }
        require(adaptMinScore in 0f..1f) { "adaptMinScore は 0 から 1 の範囲です" }
        require(adaptMinEnrolledScore in 0f..1f) { "adaptMinEnrolledScore は 0 から 1 の範囲です" }
        require(adaptRivalMargin >= 0f) { "adaptRivalMargin は 0 以上です" }
        require(adaptMinRunMillis >= 0) { "adaptMinRunMillis は 0 以上です" }
        require(adaptMaxBoost >= 0f) { "adaptMaxBoost は 0 以上です" }
        require(adaptBaseMillis > 0) { "adaptBaseMillis は正の値です" }
        require(adaptMaxMillis >= adaptBaseMillis) { "adaptMaxMillis は adaptBaseMillis 以上です" }
        require(windowMillis > 0) { "windowMillis は正の値です" }
        require(hopMillis > 0) { "hopMillis は正の値です" }
        require(trailingSilenceMillis >= 0) { "trailingSilenceMillis は 0 以上です" }
        require(minWindowMillis >= 0) { "minWindowMillis は 0 以上です" }
        require(minReliableMillis >= 0) { "minReliableMillis は 0 以上です" }
        require(minReliableRunMillis >= 0) { "minReliableRunMillis は 0 以上です" }
        require(maxComparedSpeakers > 0) { "maxComparedSpeakers は正の値です" }
    }

    private companion object {
        const val DEFAULT_SELF_THRESHOLD = 0.5f
        const val DEFAULT_SELF_EXIT_THRESHOLD = 0.45f
        const val DEFAULT_UNKNOWN_FLOOR = 0.4f
        const val DEFAULT_WINDOW_MILLIS = 1_500L
        const val DEFAULT_HOP_MILLIS = 750L
        const val DEFAULT_TRAILING_SILENCE_MILLIS = 800L
        const val DEFAULT_MIN_WINDOW_MILLIS = 400L

        // 検証で、無音を含まないまとまりに使った値
        const val DEFAULT_MIN_RELIABLE_RUN_MILLIS = 1_000L

        // アプリの区間は前後に約 1.4 秒の無音を含むので、実音声は約 1.1 秒。検証の 1.5 秒の EER は 1.4%
        const val DEFAULT_MIN_RELIABLE_MILLIS = 2_500L
        const val DEFAULT_MAX_COMPARED_SPEAKERS = 64
        const val DEFAULT_PROVISIONAL_THRESHOLD = 0.45f
        const val DEFAULT_MAX_PROVISIONAL_SPEAKERS = 3
        const val DEFAULT_MIN_NEW_SPEAKER_RUN_MILLIS = 2_000L
        const val DEFAULT_STALE_PROVISIONAL_MILLIS = 7L * 24 * 60 * 60 * 1_000
        const val DEFAULT_STRONG_SELF_THRESHOLD = 0.6f
        const val DEFAULT_RIVAL_MARGIN = 0.05f
        const val DEFAULT_SELF_OVERRIDE_MARGIN = 0.1f
        const val DEFAULT_ADAPT_MIN_SCORE = 0.55f
        const val DEFAULT_ADAPT_MIN_ENROLLED_SCORE = 0.4f
        const val DEFAULT_ADAPT_RIVAL_MARGIN = 0.1f
        const val DEFAULT_ADAPT_MIN_RUN_MILLIS = 2_000L
        const val DEFAULT_ADAPT_MAX_BOOST = 0.05f
        const val DEFAULT_ADAPT_BASE_MILLIS = 120_000L
        const val DEFAULT_ADAPT_MAX_MILLIS = 600_000L
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

/** 長さ 1 のベクトルの重み付き平均を、長さ 1 に戻す。重みは発話の長さ。 */
internal fun weightedMean(mean: FloatArray, meanMillis: Long, added: FloatArray, addedMillis: Long): FloatArray? {
    require(mean.size == added.size) { "特徴量の次元が違います" }
    return normalizedOrNull(FloatArray(mean.size) { mean[it] * meanMillis + added[it] * addedMillis })
}

/**
 * 自分の声に発話を足す。[stored] がなければ登録の特徴量から始める。登録の特徴量は変えない。
 * 重みに下限があるので最初の発話で大きく動かず、上限があるので今の端末の条件に追いつける。
 */
internal fun learnedSelfVoice(
    enrolled: FloatArray,
    stored: SelfAdaptation?,
    voice: TurnVoice,
    modelVersion: String,
    config: SpeakerConfig,
): SelfAdaptation? {
    val base = stored?.vector ?: enrolled
    val weight = (stored?.voicedMillis ?: 0L).coerceIn(config.adaptBaseMillis, config.adaptMaxMillis)
    val mean = weightedMean(base, weight, voice.vector, voice.voicedMillis) ?: return null
    val total = ((stored?.voicedMillis ?: 0L) + voice.voicedMillis).coerceAtMost(config.adaptMaxMillis)
    return SelfAdaptation(mean, total, modelVersion)
}

/** 長さ 1 のベクトルの平均を、長さ 1 に戻す。 */
internal fun meanOf(vectors: List<FloatArray>): FloatArray? {
    val first = vectors.firstOrNull() ?: return null
    require(vectors.all { it.size == first.size }) { "特徴量の次元が違います" }
    return normalizedOrNull(FloatArray(first.size) { index -> vectors.sumOf { it[index].toDouble() }.toFloat() })
}
