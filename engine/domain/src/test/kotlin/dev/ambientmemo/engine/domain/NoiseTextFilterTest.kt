@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoiseTextFilterTest {
    private val filter = NoiseTextFilter(longDurationMillis = 2_000)

    @Test
    fun `1 文字は短い区間でも除く`() {
        assertFalse(filter.shouldSave("え", 1_600))
    }

    @Test
    fun `2 文字は区間が短ければ残す`() {
        assertTrue(filter.shouldSave("そう", 1_999))
    }

    @Test
    fun `2 文字は区間が長ければ除く`() {
        assertFalse(filter.shouldSave("はい", 2_000))
    }

    @Test
    fun `3 文字以上は区間が長くても残す`() {
        assertTrue(filter.shouldSave("なるほど", 20_000))
    }

    @Test
    fun `句読点と空白は文字に数えない`() {
        assertFalse(filter.shouldSave("え。 ", 1_600))
        assertFalse(filter.shouldSave("はい。", 3_000))
    }
}
