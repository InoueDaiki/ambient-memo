package dev.ambientmemo.engine.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `prompt-golden/` は `tools/spike-llm/scripts/prompts.py` の出力をそのまま保存したもの。
 * 文面がずれると検証の成績が根拠にならなくなるので、一字一句を比べる。
 */
class OrganizePromptsTest {
    private val date = CalendarDate(2026, 11, 2)
    private val transcript = "[18:02] 歯医者の予約、あさってに取れた\n[18:03] 帰りに牛乳を買う\n"

    private fun golden(name: String) = javaClass.getResource("/prompt-golden/PromptGolden-$name.txt")!!.readText()

    @Test
    fun `要約のプロンプトが検証と同じ`() {
        val request = OrganizePrompts.summary(date, transcript)

        assertEquals(golden("system"), request.system)
        assertEquals(golden("summary"), request.user)
    }

    @Test
    fun `ToDo のプロンプトが検証と同じ`() {
        val request = OrganizePrompts.todo(date, transcript)

        assertEquals(golden("system"), request.system)
        assertEquals(golden("todo"), request.user)
    }

    @Test
    fun `出力の上限は検証と同じ`() {
        assertEquals(400, OrganizePrompts.summary(date, transcript).maxOutputTokens)
        assertEquals(400, OrganizePrompts.todo(date, transcript).maxOutputTokens)
    }

    @Test
    fun `日付の表は月をまたいでも曜日がずれない`() {
        val user = OrganizePrompts.todo(CalendarDate(2026, 12, 28), transcript).user

        assertTrue("2026-12-28 月 今日" in user)
        assertTrue("2027-01-01 金" in user)
        assertTrue("2027-01-18 月" in user)
    }
}
