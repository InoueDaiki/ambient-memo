package dev.ambientmemo.engine.android

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.ambientmemo.engine.adapters.SqliteMemoRepository
import dev.ambientmemo.engine.adapters.SqliteUtteranceRepository
import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.CalendarDate
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.NewTodo
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.Speaker
import dev.ambientmemo.engine.domain.SpeakerNameSource
import dev.ambientmemo.engine.domain.TodoExtraction
import dev.ambientmemo.engine.domain.TodoKind
import dev.ambientmemo.engine.domain.TodoStatus
import dev.ambientmemo.engine.domain.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

private const val SELF_SPEAKER_ID = 7L

/**
 * `packages/engine-client/src/memo-contract.ts` の契約を、本物の [MemoBridge] と SQLite に流す。
 *
 * TS のテストは JVM で動かないので、同じケースを同じ名前で書く。[名前が TS の契約と揃っている] が差を見つける。
 * Expo の Map の変換とイベントの配送は通らない。
 */
class MemoBridgeContractTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AmbientMemoDatabase.Schema)
    private val database = AmbientMemoDatabase(driver)
    private val utterances = SqliteUtteranceRepository(database, Dispatchers.Unconfined)
    private val events = mutableListOf<MemoChange>()
    private val memos = ObservableMemoRepository(SqliteMemoRepository(database, Dispatchers.Unconfined)) {
        events += it
    }
    private val self = Speaker(SELF_SPEAKER_ID, "自分", SpeakerNameSource.SELF, isSelf = true, createdAtMillis = 0)
    private val bridge = MemoBridge(memos, { mapOf(self.id to self) }, FakeClock(50_000))

    @AfterEach
    fun close() = driver.close()

    private var nextUtteranceMillis = 1L

    @Suppress("LongParameterList") // 既定値でケースごとに必要なものだけ指定する
    private suspend fun record(
        startedAt: Long = 1,
        title: String? = "タイトル",
        summary: String? = "要約",
        createdAt: Long = 1,
        todos: List<NewTodo> = emptyList(),
        speakerId: Long? = null,
    ): Long {
        val millis = nextUtteranceMillis++
        val utterance = utterances.insert(NewUtterance(millis, millis, "発話", null, millis, speakerId = speakerId))
        val memo = NewMemo(
            startedAtMillis = startedAt,
            endedAtMillis = startedAt + 1,
            title = title,
            summary = summary,
            todoExtraction = TodoExtraction.COMPLETE,
            modelVersion = "fake-1",
            createdAtMillis = createdAt,
            utteranceIds = listOf(utterance.id),
            todos = todos,
        )
        return memos.recordMemo(memo)!!.id.also { events.clear() }
    }

    private fun todo(task: String, due: CalendarDate? = null, status: TodoStatus = TodoStatus.OPEN) =
        NewTodo(task, TodoKind.TODO, due, status)

    private fun ids(items: List<WireObject>) = items.map { it["id"] }

    private fun tasks(items: List<WireObject>) = items.map { it["task"] }

    @Test
    fun `メモは開始時刻の新しい順に limit 件まで返す`() = runTest {
        val a = record(startedAt = 1)
        val c = record(startedAt = 3)
        val b = record(startedAt = 2)

        assertEquals(listOf(c.toString(), b.toString()), ids(bridge.listMemos(2)))
        assertEquals(3, bridge.listMemos(10).size)
        assertEquals(a.toString(), bridge.listMemos(10).last()["id"])
    }

    @Test
    fun `一覧のメモには発話と ToDo を含めない`() = runTest {
        record(todos = listOf(todo("作業")))

        val item = bridge.listMemos(1).single()

        assertFalse(item.containsKey("utterances"))
        assertFalse(item.containsKey("todos"))
    }

    @Test
    fun `要約に失敗したメモも一覧と詳細で返す`() = runTest {
        val id = record(title = null, summary = null)

        assertNull(bridge.listMemos(1).single()["summary"])
        assertTrue(bridge.listMemos(1).single().containsKey("summary"))
        assertNull(bridge.getMemo(id.toString())!!["title"])
    }

    @Test
    fun `メモの詳細は根拠の発話と ToDo を返す`() = runTest {
        val id = record(todos = listOf(todo("作業")), speakerId = SELF_SPEAKER_ID)

        val detail = bridge.getMemo(id.toString())!!

        val utterance = (detail["utterances"] as List<*>).single() as Map<*, *>
        assertEquals(setOf("id", "text", "speaker", "speakerName", "startedAt"), utterance.keys)
        assertEquals("発話", utterance["text"])
        assertEquals("self", utterance["speaker"])
        assertEquals("自分", utterance["speakerName"])
        val todo = (detail["todos"] as List<*>).single() as Map<*, *>
        assertEquals(id.toString(), todo["memoId"])
        assertEquals("作業", todo["task"])
    }

    @Test
    fun `ないメモは null を返す`() = runTest {
        assertNull(bridge.getMemo("none"))
        assertNull(bridge.getMemo("12345"))
    }

    @Test
    fun `ToDo は未完了、期限の早い順、新しい順に並べ、期限のないものは後ろにする`() = runTest {
        record(
            createdAt = 1,
            todos = listOf(
                todo("done", CalendarDate(2026, 1, 1)),
                todo("no-due"),
                todo("later", CalendarDate(2026, 12, 1)),
                todo("sooner-old", CalendarDate(2026, 11, 1)),
            ),
        )
        record(createdAt = 2, todos = listOf(todo("sooner-new", CalendarDate(2026, 11, 1), TodoStatus.CANDIDATE)))
        bridge.completeTodo(bridge.listTodos(10).first { it["task"] == "done" }["id"] as String)

        assertEquals(listOf("sooner-new", "sooner-old", "later", "no-due", "done"), tasks(bridge.listTodos(10)))
    }

    @Test
    fun `ToDo は limit 件まで返す`() = runTest {
        record(todos = listOf(todo("1"), todo("2"), todo("3")))

        assertEquals(2, bridge.listTodos(2).size)
    }

    @Test
    fun `完了すると状態が変わり、解決するまでにイベントが届く`() = runTest {
        val memoId = record(todos = listOf(todo("t", status = TodoStatus.CANDIDATE)))

        bridge.completeTodo(bridge.listTodos(1).single()["id"] as String)

        assertEquals(listOf(MemoChange.TODOS), events)
        assertEquals("done", bridge.listTodos(1).single()["status"])
        val detail = bridge.getMemo(memoId.toString())!!
        assertEquals("done", ((detail["todos"] as List<*>).single() as Map<*, *>)["status"])
    }

    @Test
    fun `完了済みの ToDo の完了は何もせず成功する`() = runTest {
        record(todos = listOf(todo("t")))
        val id = bridge.listTodos(1).single()["id"] as String
        bridge.completeTodo(id)
        events.clear()

        bridge.completeTodo(id)

        assertEquals(emptyList<MemoChange>(), events)
    }

    @Test
    fun `ない ToDo の完了は reject する`() = runTest {
        listOf("none", "12345").forEach { id ->
            assertThrows(TodoNotFoundException::class.java) { runBlocking { bridge.completeTodo(id) } }
        }
    }

    @Test
    fun `名前が TS の契約と揃っている`() {
        val contract = File("../../packages/engine-client/src/memo-contract.ts").readText()
        val expected = Regex("""\bit\("([^"]+)"""").findAll(contract).map { it.groupValues[1] }.toSet()
        val own = javaClass.declaredMethods
            .filter { method -> method.isAnnotationPresent(Test::class.java) }
            .map { it.name }
            .toSet()

        assertEquals(expected, own - "名前が TS の契約と揃っている")
    }
}
