package dev.ambientmemo.engine.android

import dev.ambientmemo.engine.domain.InvalidTuningException
import dev.ambientmemo.engine.domain.Tuning
import dev.ambientmemo.engine.domain.TuningKey
import dev.ambientmemo.engine.domain.TuningProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TuningWireTest {
    @Test
    fun `すべての項目を範囲と既定値と一緒に渡す`() {
        @Suppress("UNCHECKED_CAST")
        val items = Tuning.of(mapOf("maxProvisionalSpeakers" to 5.0)).toWire()["items"] as List<Map<String, Any?>>

        assertEquals(TuningKey.entries.map { it.wireName }, items.map { it["key"] })
        val speakers = items.single { it["key"] == "maxProvisionalSpeakers" }
        assertEquals(
            mapOf(
                "key" to "maxProvisionalSpeakers",
                "value" to 5.0,
                "defaultValue" to 3.0,
                "min" to 1.0,
                "max" to 10.0,
                "step" to 1.0,
            ),
            speakers,
        )
    }

    @Test
    fun `JS の数を受け取る`() {
        assertEquals(6, parseTuning(mapOf("maxProvisionalSpeakers" to 6)).speakerConfig().maxProvisionalSpeakers)
        assertEquals(Tuning.DEFAULT, parseTuning(emptyMap()))
    }

    @Test
    fun `数でない値は断る`() {
        val error = assertThrows<InvalidTuningException> { parseTuning(mapOf("minTopicLines" to "6")) }

        assertEquals(TuningProblem.OUT_OF_RANGE, error.problem)
        assertEquals("out-of-range", error.problem.toWire())
    }
}
