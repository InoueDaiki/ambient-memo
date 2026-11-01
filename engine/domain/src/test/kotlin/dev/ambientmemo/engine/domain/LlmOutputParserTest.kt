package dev.ambientmemo.engine.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LlmOutputParserTest {
    private val item = """{"task": "牛乳を買う", "kind": "todo", "due": "2026-11-02"}"""
    private val raw = RawTodo("牛乳を買う", "todo", "2026-11-02")

    @Test
    fun `タイトルの飾りを外して最初の行を読む`() {
        assertEquals("週末の買い物", parseTitle("タイトル: 「週末の買い物」\n説明です"))
        assertEquals("歯医者の予約", parseTitle("- 歯医者の予約"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "2026年11月1日(日曜日)", "11月1日", "2026-11-01"])
    fun `空や日付だけのタイトルは読まない`(text: String) {
        assertNull(parseTitle(text))
    }

    @Test
    fun `素直な JSON を読む`() {
        assertEquals(ParsedTodos(listOf(raw), isComplete = true), parseTodos("""{"items": [$item]}"""))
    }

    @Test
    fun `空の items は読めた結果として扱う`() {
        assertEquals(ParsedTodos(emptyList(), isComplete = true), parseTodos("""{"items": []}"""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "```json\n{\"items\": [ITEM]}\n```",
            "```\n{\"items\": [ITEM]}\n```\n",
            "抽出結果は次のとおりです。\n{\"items\": [ITEM]}\n以上です。",
            "<think>{\"items\": []} を出すか考える</think>{\"items\": [ITEM]}",
            "{\"items\": [ITEM,],}",
            "[ITEM]",
        ],
    )
    fun `包みや余分な記号があっても読む`(template: String) {
        assertEquals(ParsedTodos(listOf(raw), isComplete = true), parseTodos(template.replace("ITEM", item)))
    }

    @Test
    fun `本文の中の括弧と引用符で区切りを誤らない`() {
        val text = """{"items": [{"task": "「}]」と \"書く\"", "kind": "todo", "due": null}]}"""

        assertEquals(listOf(RawTodo("「}]」と \"書く\"", "todo", null)), parseTodos(text)?.items)
    }

    @Test
    fun `打ち切られた出力は閉じた要素までを不完全として読む`() {
        val text = """{"items": [$item, {"task": "途中で切れ"""

        assertEquals(ParsedTodos(listOf(raw), isComplete = false), parseTodos(text))
    }

    @Test
    fun `内容のない要素は捨てて不完全にする`() {
        val text = """{"items": [$item, {"kind": "todo"}, {"task": "  "}, "文字"]}"""

        assertEquals(ParsedTodos(listOf(raw), isComplete = false), parseTodos(text))
    }

    @Test
    fun `途中が壊れていても内側の配列が読めれば不完全として読む`() {
        val text = """{"items" [$item]"""

        assertEquals(ParsedTodos(listOf(raw), isComplete = false), parseTodos(text))
    }

    @Test
    fun `別の形の JSON の後ろにある items を読む`() {
        assertEquals(listOf(raw), parseTodos("""{"note": 1} {"items": [$item]}""")?.items)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "該当なし", "{", "{\"items\": ", "{\"items\": 3}", "[1, 2]", "<think>考え中"])
    fun `読めない出力は null を返す`(text: String) {
        assertNull(parseTodos(text))
    }

    @Test
    fun `期限と種類が null や別の型でも読む`() {
        val parsed = parseTodos("""{"items": [{"task": "作業", "kind": null, "due": 20261102}]}""")

        assertEquals(listOf(RawTodo("作業", null, "20261102")), parsed?.items)
    }

    @Test
    fun `要約は箇条書きだけを残して記号を揃える`() {
        val text = "以下が要約です。\n\n- 一つ目\n* 二つ目\n・三つ目\n"

        assertEquals("- 一つ目\n- 二つ目\n- 三つ目", parseSummary(text))
    }

    @Test
    fun `箇条書きがなくても本文があれば残す`() {
        assertEquals("会議の要約", parseSummary("```\n会議の要約\n```"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " \n ", "<think>考え中</think>", "```\n```"])
    fun `本文のない要約は null を返す`(text: String) {
        assertNull(parseSummary(text))
    }

    @Test
    fun `不完全な判定は読めた件数と独立している`() {
        assertTrue(parseTodos("""{"items": [$item]}""")!!.isComplete)
        assertFalse(parseTodos("""{"items": [$item""")!!.isComplete)
    }
}
