package dev.ambientmemo.engine.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.TimeZone

class CalendarDateTest {
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")

    @Test
    fun `日本時間の日付は UTC の日付と違うことがある`() {
        // 2026-11-01T15:30:00Z
        val millis = 1_793_547_000_000L

        assertEquals(CalendarDate(2026, 11, 2), CalendarDate.of(millis, tokyo))
        assertEquals(CalendarDate(2026, 11, 1), CalendarDate.of(millis, TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `月と年と閏日をまたいで日数を足せる`() {
        assertEquals(CalendarDate(2026, 12, 1), CalendarDate(2026, 11, 30).plusDays(1))
        assertEquals(CalendarDate(2027, 1, 1), CalendarDate(2026, 12, 31).plusDays(1))
        assertEquals(CalendarDate(2028, 2, 29), CalendarDate(2028, 2, 28).plusDays(1))
        assertEquals(CalendarDate(2026, 11, 22), CalendarDate(2026, 11, 1).plusDays(21))
    }

    @Test
    fun `曜日は月曜を 0 にして数える`() {
        assertEquals(0, CalendarDate(2026, 11, 2).weekdayIndex)
        assertEquals(6, CalendarDate(2026, 11, 1).weekdayIndex)
    }

    @Test
    fun `日付の前後を比べられる`() {
        assertTrue(CalendarDate(2026, 12, 31) < CalendarDate(2027, 1, 1))
    }

    @Test
    fun `文字列と相互に変換できる`() {
        assertEquals("2026-01-05", CalendarDate(2026, 1, 5).toString())
        assertEquals(CalendarDate(2026, 1, 5), CalendarDate.parse("2026-01-05"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["2026-02-30", "2026-13-01", "2026-00-10", "2026-1-5", "2026/01/05", "明日", "", "2026-11-02T10:00"],
    )
    fun `形式が違うか存在しない日付は読まない`(text: String) {
        assertNull(CalendarDate.parse(text))
    }

    @Test
    fun `存在しない日付は作れない`() {
        assertThrows(IllegalArgumentException::class.java) { CalendarDate(2026, 2, 29) }
    }
}
