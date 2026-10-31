package dev.ambientmemo.engine.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AssessedDueTest {
    private val today = CalendarDate(2026, 11, 2)
    private val transcript = "[18:02] 歯医者の予約、あさっての十時に取れた"

    private fun assess(due: String?, text: String = transcript, endedOn: CalendarDate = today) =
        assessDue(due, today, endedOn, text)

    @Test
    fun `表の範囲内で手がかりのある日付は確度が高い`() {
        assertEquals(AssessedDue(CalendarDate(2026, 11, 4), true), assess("2026-11-04"))
    }

    @Test
    fun `表の両端は範囲内で、その次は範囲外`() {
        assertEquals(true, assess("2026-11-02").isConfident)
        assertEquals(true, assess("2026-11-23").isConfident)
        assertEquals(false, assess("2026-11-24").isConfident)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "null", "NULL", "なし", "不明"])
    fun `期限なしの表現は期限のない確定にする`(due: String) {
        assertEquals(AssessedDue(null, true), assess(due))
    }

    @Test
    fun `null は期限のない確定にする`() {
        assertEquals(AssessedDue(null, true), assess(null))
    }

    @ParameterizedTest
    @ValueSource(strings = ["来週", "2026-02-30", "11月4日", "2026-11-04 10:00"])
    fun `読めない期限は日付を捨てて候補にする`(due: String) {
        assertEquals(AssessedDue(null, false), assess(due))
    }

    @Test
    fun `会話の日より前は候補にする`() {
        assertEquals(AssessedDue(CalendarDate(2026, 11, 1), false), assess("2026-11-01"))
    }

    @Test
    fun `年をまたぐ遠い日付は候補にする`() {
        assertEquals(AssessedDue(CalendarDate(2027, 1, 15), false), assess("2027-01-15"))
    }

    @Test
    fun `文字起こしに日付の手がかりがなければ候補にする`() {
        assertEquals(false, assess("2026-11-04", text = "[18:02] 牛乳が切れてる").isConfident)
    }

    @ParameterizedTest
    @ValueSource(strings = ["明日までに", "金曜に", "11月5日", "十一月五日", "11/5", "来週", "月末まで", "三日に"])
    fun `日付の手がかりを見つける`(text: String) {
        assertEquals(true, assess("2026-11-04", text = text).isConfident)
    }

    @Test
    fun `まとまりが日付をまたぐと候補にする`() {
        assertEquals(false, assess("2026-11-04", endedOn = today.plusDays(1)).isConfident)
    }
}
