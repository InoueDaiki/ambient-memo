package dev.ambientmemo.engine.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EngineStatusTest {
    @Test
    fun `現在の状態はエンジンの名前と API の版を持つ`() {
        assertEquals(EngineStatus("ambient-memo-engine", 1), currentEngineStatus())
    }
}
