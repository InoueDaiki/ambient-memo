package dev.ambientmemo.engine.domain

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 利用者が画面で変えられる値。範囲、刻み、既定値はここだけで決め、画面にもここから渡す。
 *
 * 既定値は [SpeakerConfig]、[OrganizeConfig]、[QUIET_GATE_DBFS]、アダプターの VAD の既定値と同じにする。
 * 揃っていることはテストで確かめる。[wireName] は保存の形と JS の境界で使う名前で、変えない。
 */
@Suppress("MagicNumber") // 範囲、刻み、既定値をここに並べて読む
enum class TuningKey(
    val wireName: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val defaultValue: Double,
) {
    /** [SpeakerConfig.selfThreshold]。 */
    SELF_THRESHOLD("selfThreshold", 0.3, 0.8, 0.01, 0.5),

    /** [SpeakerConfig.selfExitThreshold]。[SELF_THRESHOLD] 以下にする。 */
    SELF_EXIT_THRESHOLD("selfExitThreshold", 0.2, 0.8, 0.01, 0.45),

    /** [SpeakerConfig.unknownFloor]。[SELF_EXIT_THRESHOLD] 以下にする。 */
    UNKNOWN_FLOOR("unknownFloor", 0.1, 0.8, 0.01, 0.4),

    /** [SpeakerConfig.provisionalThreshold]。まとめ直しの [SpeakerConfig.consolidationThreshold] も同じ値にする。 */
    PROVISIONAL_THRESHOLD("provisionalThreshold", 0.3, 0.8, 0.01, 0.45),

    /** [SpeakerConfig.maxProvisionalSpeakers]。下げると、次のまとめ直しで仮の話者が統合される。 */
    MAX_PROVISIONAL_SPEAKERS("maxProvisionalSpeakers", 1.0, 10.0, 1.0, 3.0),

    /** [QuietGatedVoiceActivityDetector] の、VAD を動かさない大きさ(dBFS)。 */
    QUIET_GATE("quietGateDbfs", -70.0, -30.0, 1.0, QUIET_GATE_DBFS),

    /** VAD が発話とみなす確率のしきい値。 */
    VAD_THRESHOLD("vadThreshold", 0.2, 0.9, 0.05, 0.5),

    /** VAD が発話を区切る無音の長さ。話者の判定の [SpeakerConfig.trailingSilenceMillis] も同じ値にする。 */
    VAD_MIN_SILENCE("vadMinSilenceMillis", 300.0, 2_000.0, 100.0, 800.0),

    /** [OrganizeConfig.minTopicLines]。大きいほど話題を前の話題にまとめる。 */
    MIN_TOPIC_LINES("minTopicLines", 1.0, 10.0, 1.0, 4.0),

    /** [OrganizeConfig.minSilenceGapMillis] を分で表す。 */
    CHUNK_GAP_MINUTES("chunkGapMinutes", 1.0, 30.0, 1.0, 5.0),
    ;

    /** 刻みが 1 以上の値は整数で表す。 */
    val isInteger: Boolean get() = step >= 1.0

    companion object {
        fun fromWireName(name: String): TuningKey? = entries.firstOrNull { it.wireName == name }
    }
}

/** 調整の値を受け付けない理由。 */
enum class TuningProblem {
    /** 知らない名前。 */
    UNKNOWN_KEY,

    /** 範囲の外か、数でない。 */
    OUT_OF_RANGE,

    /** 自分から出る類似度が、自分に入る類似度より高い。 */
    SELF_EXIT_ABOVE_SELF,

    /** 不明の帯の下端が、自分から出る類似度より高い。 */
    UNKNOWN_FLOOR_ABOVE_SELF_EXIT,
}

class InvalidTuningException(val problem: TuningProblem, val key: String?) :
    IllegalArgumentException("調整の値を受け付けません: $problem ${key.orEmpty()}")

/**
 * 調整の値の組。既定値と違う値だけを持つ。値は刻みに丸め、範囲と値どうしの関係を確かめてから作る。
 */
class Tuning private constructor(private val overrides: Map<TuningKey, Double>) {
    operator fun get(key: TuningKey): Double = overrides[key] ?: key.defaultValue

    /** 既定値と違う値。名前の順。 */
    val changed: Map<TuningKey, Double>
        get() = TuningKey.entries.mapNotNull { key -> overrides[key]?.let { key to it } }.toMap()

    /** 話者の判定の値。[base] のほかの値はそのまま使う。 */
    fun speakerConfig(base: SpeakerConfig = SpeakerConfig()): SpeakerConfig {
        val self = this[TuningKey.SELF_THRESHOLD].toFloat()
        val provisional = this[TuningKey.PROVISIONAL_THRESHOLD].toFloat()
        return base.copy(
            selfThreshold = self,
            selfExitThreshold = this[TuningKey.SELF_EXIT_THRESHOLD].toFloat(),
            unknownFloor = this[TuningKey.UNKNOWN_FLOOR].toFloat(),
            provisionalThreshold = provisional,
            consolidationThreshold = provisional,
            maxProvisionalSpeakers = this[TuningKey.MAX_PROVISIONAL_SPEAKERS].toInt(),
            // 自分に入る値より低いと、強い自分の判定が自分の判定より緩くなる
            strongSelfThreshold = maxOf(base.strongSelfThreshold, self),
            trailingSilenceMillis = this[TuningKey.VAD_MIN_SILENCE].toLong(),
        )
    }

    /** 整理ジョブの値。[base] のほかの値はそのまま使う。 */
    fun organizeConfig(base: OrganizeConfig = OrganizeConfig()): OrganizeConfig = base.copy(
        minTopicLines = this[TuningKey.MIN_TOPIC_LINES].toInt(),
        minSilenceGapMillis = this[TuningKey.CHUNK_GAP_MINUTES].toLong() * MILLIS_PER_MINUTE,
    )

    val quietGateDbfs: Double get() = this[TuningKey.QUIET_GATE]
    val vadThreshold: Float get() = this[TuningKey.VAD_THRESHOLD].toFloat()
    val vadMinSilenceSeconds: Float get() = (this[TuningKey.VAD_MIN_SILENCE] / MILLIS_PER_SECOND).toFloat()

    /** 保存の形。1 行に `名前=値` を 1 つ。既定値の項目は書かない。 */
    fun encode(): String = buildString {
        append(VERSION_LINE).append('\n')
        changed.forEach { (key, value) -> append(key.wireName).append('=').append(format(key, value)).append('\n') }
    }

    override fun equals(other: Any?): Boolean = other is Tuning && other.overrides == overrides

    override fun hashCode(): Int = overrides.hashCode()

    override fun toString(): String = "Tuning(${changed.entries.joinToString { "${it.key.wireName}=${it.value}" }})"

    companion object {
        val DEFAULT = Tuning(emptyMap())

        private const val VERSION_LINE = "version=1"
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val DECIMAL_SCALE = 1_000_000.0
        private const val TOLERANCE = 1e-9

        /** 名前と値から作る。書かない項目は既定値。受け付けなければ [InvalidTuningException]。 */
        fun of(values: Map<String, Double>): Tuning {
            // 例外の種類が Swift と揃うように、名前の順に確かめる
            val keyed = values.toSortedMap().map { (name, value) ->
                val key = TuningKey.fromWireName(name) ?: throw InvalidTuningException(TuningProblem.UNKNOWN_KEY, name)
                key to snap(key, value)
            }.toMap()
            return checked(keyed)
        }

        /**
         * 保存の形から読む。例外を投げない。読めない行、知らない名前、範囲の外の値は飛ばす。
         * 残った値どうしの関係が合わなければ、すべて既定値にする。片方だけ使うと、利用者が選んでいない組になるため。
         */
        fun decode(text: String): Tuning {
            val keyed = text.lineSequence().mapNotNull { line ->
                val name = line.substringBefore('=', missingDelimiterValue = "").trim()
                val key = TuningKey.fromWireName(name) ?: return@mapNotNull null
                val value = line.substringAfter('=').trim().toDoubleOrNull() ?: return@mapNotNull null
                try {
                    key to snap(key, value)
                } catch (_: InvalidTuningException) {
                    null
                }
            }.toMap()
            return try {
                checked(keyed)
            } catch (_: InvalidTuningException) {
                DEFAULT
            }
        }

        private fun snap(key: TuningKey, value: Double): Double {
            if (!value.isFinite() || value < key.min - TOLERANCE || value > key.max + TOLERANCE) {
                throw InvalidTuningException(TuningProblem.OUT_OF_RANGE, key.wireName)
            }
            val steps = ((value - key.min) / key.step).roundToLong()
            // 刻みを足した誤差を、小数 6 桁で丸めて消す。Swift でも同じ値になる
            val snapped = ((key.min + steps * key.step) * DECIMAL_SCALE).roundToLong() / DECIMAL_SCALE
            return snapped.coerceIn(key.min, key.max)
        }

        private fun checked(values: Map<TuningKey, Double>): Tuning {
            fun value(key: TuningKey) = values[key] ?: key.defaultValue
            if (value(TuningKey.SELF_EXIT_THRESHOLD) > value(TuningKey.SELF_THRESHOLD) + TOLERANCE) {
                throw InvalidTuningException(TuningProblem.SELF_EXIT_ABOVE_SELF, TuningKey.SELF_EXIT_THRESHOLD.wireName)
            }
            if (value(TuningKey.UNKNOWN_FLOOR) > value(TuningKey.SELF_EXIT_THRESHOLD) + TOLERANCE) {
                throw InvalidTuningException(
                    TuningProblem.UNKNOWN_FLOOR_ABOVE_SELF_EXIT,
                    TuningKey.UNKNOWN_FLOOR.wireName,
                )
            }
            return Tuning(values.filter { (key, value) -> abs(value - key.defaultValue) > TOLERANCE })
        }

        private fun format(key: TuningKey, value: Double): String =
            if (key.isInteger) value.roundToLong().toString() else value.toString()
    }
}
