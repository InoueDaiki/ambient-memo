package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.InvalidTuningException
import dev.ambientmemo.engine.domain.Tuning
import dev.ambientmemo.engine.domain.TuningKey
import dev.ambientmemo.engine.domain.TuningProblem

/** JS へ渡す調整の値。項目は [TuningKey] の順で、範囲と刻みも渡す。画面は範囲をここから取る。 */
fun Tuning.toWire(): WireObject = mapOf(
    "items" to TuningKey.entries.map {
        mapOf(
            "key" to it.wireName,
            "value" to this[it],
            "defaultValue" to it.defaultValue,
            "min" to it.min,
            "max" to it.max,
            "step" to it.step,
        )
    },
)

/** JS から受け取った名前と値で作る。数でない値は範囲の外として断る。 */
fun parseTuning(values: Map<String, Any?>): Tuning = Tuning.of(
    values.mapValues { (name, value) ->
        (value as? Number)?.toDouble() ?: throw InvalidTuningException(TuningProblem.OUT_OF_RANGE, name)
    },
)

/** 境界で渡す断りの理由。JS の `TuningProblem` と同じ綴り。 */
fun TuningProblem.toWire(): String = when (this) {
    TuningProblem.UNKNOWN_KEY -> "unknown-key"
    TuningProblem.OUT_OF_RANGE -> "out-of-range"
    TuningProblem.SELF_EXIT_ABOVE_SELF -> "self-exit-above-self"
    TuningProblem.UNKNOWN_FLOOR_ABOVE_SELF_EXIT -> "unknown-floor-above-self-exit"
}
