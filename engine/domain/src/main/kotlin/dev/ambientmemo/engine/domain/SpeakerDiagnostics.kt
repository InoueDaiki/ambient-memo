package dev.ambientmemo.engine.domain

import kotlin.math.floor

/** 話者の判定の結果。集計だけに使う。 */
enum class SpeakerDecision { SELF, OTHER, UNKNOWN }

/** 判定 1 回の値。[selfScore] は自分との類似度の平均。 */
class SpeakerDecisionSample(
    val decision: SpeakerDecision,
    val selfScore: Float,
    val voicedMillis: Long,
)

/** 判定と類似度の帯ごとの回数。 */
data class SpeakerDecisionBucket(
    val decision: SpeakerDecision,
    val bucket: Int,
    val runs: Long,
    val voicedMillis: Long,
)

/** 類似度を帯の番号にする。0.05 刻み。負の類似度は 0 の帯に入れる。 */
fun scoreBucket(score: Float): Int =
    floor(score.coerceIn(0f, 1f) * SCORE_BUCKETS).toInt().coerceAtMost(SCORE_BUCKETS - 1)

/** 帯の下端の類似度。 */
fun bucketFloor(bucket: Int): Float = bucket.toFloat() / SCORE_BUCKETS

const val SCORE_BUCKETS = 20

/** 端末の中で話者の判定を確かめる値。音声も特徴量も含まない。 */
data class SpeakerDiagnostics(
    val days: Int,
    val selfRuns: Long,
    val otherRuns: Long,
    val unknownRuns: Long,
    val selfMillis: Long,
    val otherMillis: Long,
    val unknownMillis: Long,
    /** 類似度の帯ごとの判定の数。帯の順に並ぶ。 */
    val histogram: List<SpeakerDiagnosticsBand>,
    val provisionalSpeakers: Int,
    val hasAdaptedVoice: Boolean,
    val config: SpeakerConfig,
)

data class SpeakerDiagnosticsBand(
    val floor: Float,
    val selfRuns: Long,
    val otherRuns: Long,
    val unknownRuns: Long,
)

/** 集計を読みやすい形にする。 */
class SpeakerDiagnosticsReader(
    private val log: SpeakerDecisionLog,
    private val speakers: SpeakerRepository,
    private val modelVersion: String,
    private val clock: Clock,
    private val config: SpeakerConfig = SpeakerConfig(),
) {
    suspend fun read(days: Int = DEFAULT_DAYS): SpeakerDiagnostics {
        val buckets = log.summarize(clock.nowMillis() - days * MILLIS_PER_DAY)
        val by = { decision: SpeakerDecision -> buckets.filter { it.decision == decision } }
        val embeddings = speakers.listEmbeddings(modelVersion, config.maxComparedSpeakers)
        return SpeakerDiagnostics(
            days = days,
            selfRuns = by(SpeakerDecision.SELF).sumOf { it.runs },
            otherRuns = by(SpeakerDecision.OTHER).sumOf { it.runs },
            unknownRuns = by(SpeakerDecision.UNKNOWN).sumOf { it.runs },
            selfMillis = by(SpeakerDecision.SELF).sumOf { it.voicedMillis },
            otherMillis = by(SpeakerDecision.OTHER).sumOf { it.voicedMillis },
            unknownMillis = by(SpeakerDecision.UNKNOWN).sumOf { it.voicedMillis },
            histogram = buckets.groupBy { it.bucket }.toSortedMap().map { (bucket, rows) -> band(bucket, rows) },
            provisionalSpeakers = embeddings.count { it.nameSource == SpeakerNameSource.PROVISIONAL },
            hasAdaptedVoice = speakers.findSelfAdaptation(modelVersion) != null,
            config = config,
        )
    }

    private fun band(bucket: Int, rows: List<SpeakerDecisionBucket>): SpeakerDiagnosticsBand {
        val runs = { decision: SpeakerDecision -> rows.filter { it.decision == decision }.sumOf { it.runs } }
        return SpeakerDiagnosticsBand(
            bucketFloor(bucket),
            runs(SpeakerDecision.SELF),
            runs(SpeakerDecision.OTHER),
            runs(SpeakerDecision.UNKNOWN),
        )
    }

    companion object {
        const val DEFAULT_DAYS = 7
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
