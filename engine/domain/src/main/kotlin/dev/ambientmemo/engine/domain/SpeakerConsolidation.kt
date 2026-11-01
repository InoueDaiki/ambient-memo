package dev.ambientmemo.engine.domain

/**
 * 増えすぎた仮の話者をまとめ直す。聞き取りを始める前に呼ぶ。
 *
 * 上限以下なら何もしないので、何度呼んでも結果は変わらない。上限を超えていたら、近い順に統合して上限まで減らす。
 * 手で名付けた話者、推定の名前の話者、自分は対象にしない。発話の話者と特徴量は 1 つのトランザクションで付け替える。
 * 仮の話者には自分の声が入っていることもあるが、自分へは自動で統合しない。手で直す画面に任せる。
 */
class SpeakerConsolidation(
    private val speakers: SpeakerRepository,
    private val modelVersion: String,
    private val clock: Clock,
    private val config: SpeakerConfig = SpeakerConfig(),
) {
    /** まとめ直した仮の話者の数を返す。 */
    suspend fun run(): Int {
        val merges = planConsolidation(speakers.listEmbeddings(modelVersion, Int.MAX_VALUE), config, modelVersion)
        if (merges.isEmpty()) return 0
        speakers.mergeSpeakers(merges, clock.nowMillis(), renumberProvisional = true)
        return merges.size
    }
}

private class Cluster(
    val members: List<StoredSpeakerEmbedding>,
    val vector: FloatArray,
) {
    val voicedMillis: Long get() = members.sumOf { it.voicedMillis }

    /** 古い話者の id と名前を残す。 */
    val survivor: StoredSpeakerEmbedding get() = members.minBy { it.speakerId }
}

/**
 * 統合の計画を作る。上限を超えているときだけ、最も近い 2 つを統合することを上限に収まるまで繰り返す。
 * 収まった後も、[SpeakerConfig.consolidationThreshold] 以上に近い組は統合する。
 */
internal fun planConsolidation(
    embeddings: List<StoredSpeakerEmbedding>,
    config: SpeakerConfig,
    modelVersion: String,
): List<SpeakerMerge> {
    val provisional = embeddings.filter { !it.isSelf && it.nameSource == SpeakerNameSource.PROVISIONAL }
    if (provisional.size <= config.maxProvisionalSpeakers) return emptyList()

    val clusters = provisional.map { Cluster(listOf(it), it.vector) }.toMutableList()
    var pair = mergeablePair(clusters, config)
    while (pair != null) {
        val (first, second) = pair
        // 2 つが完全に打ち消し合うときだけ null になる。そこで止める
        val merged = merge(clusters[first], clusters[second])
        pair = if (merged == null) {
            null
        } else {
            clusters.removeAt(second)
            clusters.removeAt(first)
            clusters += merged
            mergeablePair(clusters, config)
        }
    }
    return clusters.filter { it.members.size > 1 }.flatMap { mergesOf(it, modelVersion) }
}

/** 次に統合する組を返す。上限を超えている間は最も近い組、収まったらしきい値以上の組だけ。 */
private fun mergeablePair(clusters: List<Cluster>, config: SpeakerConfig): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    var bestScore = Float.NEGATIVE_INFINITY
    for (first in clusters.indices) {
        for (second in first + 1 until clusters.size) {
            val score = similarity(clusters[first].vector, clusters[second].vector)
            if (score > bestScore) {
                best = first to second
                bestScore = score
            }
        }
    }
    val isOver = clusters.size > config.maxProvisionalSpeakers
    return best?.takeIf { isOver || bestScore >= config.consolidationThreshold }
}

private fun merge(first: Cluster, second: Cluster): Cluster? {
    // 長さのない古い特徴量でも 0 で割らないように、最低の重みを付ける
    val firstMillis = first.voicedMillis.coerceAtLeast(1)
    val secondMillis = second.voicedMillis.coerceAtLeast(1)
    val vector = weightedMean(first.vector, firstMillis, second.vector, secondMillis) ?: return null
    return Cluster(first.members + second.members, vector)
}

private fun mergesOf(cluster: Cluster, modelVersion: String): List<SpeakerMerge> {
    val into = cluster.survivor
    val merged = MergedEmbedding(
        SpeakerEmbedding(cluster.vector, modelVersion),
        cluster.members.sumOf { it.sampleCount },
        cluster.voicedMillis,
    )
    return cluster.members.filter { it.speakerId != into.speakerId }
        .map { SpeakerMerge(it.speakerId, into.speakerId, merged) }
}
