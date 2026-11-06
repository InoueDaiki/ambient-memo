package dev.ambientmemo.engine.android

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.ambientmemo.engine.adapters.SqliteMemoRepository
import dev.ambientmemo.engine.adapters.SqliteSpeakerRepository
import dev.ambientmemo.engine.adapters.SqliteUtteranceRepository
import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.MemoQuestionAnswerer
import dev.ambientmemo.engine.domain.NewMemo
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.Speaker
import dev.ambientmemo.engine.domain.SpeakerNameSource
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.isSearchPlanRequest
import dev.ambientmemo.engine.llm.LocalLlm
import dev.ambientmemo.engine.llm.NativeLlm
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
import java.util.TimeZone

private const val SELF_SPEAKER_ID = 7L
private const val TEN_MINUTES = 10 * 60 * 1_000L

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
    private val memos = ObservableMemoRepository(SqliteMemoRepository(database, Dispatchers.Unconfined)) {}
    private val self = Speaker(SELF_SPEAKER_ID, "自分", SpeakerNameSource.SELF, isSelf = true, createdAtMillis = 0)

    private class FakeOrganize : OrganizeControl {
        var modelReady = true
        var starts = 0
        override var status = OrganizeStatus()

        override fun isModelReady() = modelReady

        override fun start(): Boolean {
            starts++
            status = OrganizeStatus(OrganizePhase.PREPARING, isManual = true)
            return true
        }

        override fun stop() {
            status = status.copy(phase = OrganizePhase.STOPPING)
        }
    }

    /** 検索の語は質問そのもの。答えは 1 つ目のメモを挙げる。 */
    private class ScriptedNative : NativeLlm {
        override fun generate(request: LlmRequest): String = if (request.isSearchPlanRequest) {
            "語: ${request.user.substringAfterLast("質問: ")}\n期間: なし"
        } else {
            "メモにあります [1]"
        }

        override fun cancel() = Unit

        override fun close() = Unit
    }

    private val organize = FakeOrganize()
    private val clock = FakeClock(50_000)
    private val utc = TimeZone.getTimeZone("UTC")

    /** `:organize` の Service と同じ組み立てを、bind を通さずに動かす。 */
    private val asks = object : AskControl {
        private val session = AskSession(
            isModelReady = { true },
            openLlm = { LocalLlm("stub-1", { ScriptedNative() }, Dispatchers.Unconfined) },
            answererFor = { llm ->
                MemoQuestionAnswerer(memos, SqliteSpeakerRepository(database, Dispatchers.Unconfined), llm, clock, utc)
            },
        )

        override suspend fun ask(question: String, onStep: (AskStep) -> Unit) = session.run(question, onStep)

        override fun cancel() = Unit
    }
    private val bridge = MemoBridge(memos, { mapOf(self.id to self) }, clock, organize, asks, utc)

    @AfterEach
    fun close() = driver.close()

    private var nextUtteranceMillis = 1L

    @Suppress("LongParameterList") // 既定値でケースごとに必要なものだけ指定する
    private suspend fun record(
        startedAt: Long = 1,
        title: String? = "タイトル",
        summary: String? = "要約",
        createdAt: Long = 1,
        speakerId: Long? = null,
    ): Long {
        val millis = nextUtteranceMillis++
        val utterance = utterances.insert(NewUtterance(millis, millis, "発話", null, millis, speakerId = speakerId))
        val memo = NewMemo(
            startedAtMillis = startedAt,
            endedAtMillis = startedAt + 1,
            title = title,
            summary = summary,
            modelVersion = "fake-1",
            createdAtMillis = createdAt,
            utteranceIds = listOf(utterance.id),
        )
        return memos.recordMemo(memo)!!.id
    }

    private fun ids(items: List<WireObject>) = items.map { it["id"] }

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
    fun `一覧のメモには発話を含めない`() = runTest {
        record()

        assertFalse(bridge.listMemos(1).single().containsKey("utterances"))
    }

    @Test
    fun `要約に失敗したメモも一覧と詳細で返す`() = runTest {
        val id = record(title = null, summary = null)

        assertNull(bridge.listMemos(1).single()["summary"])
        assertTrue(bridge.listMemos(1).single().containsKey("summary"))
        assertNull(bridge.getMemo(id.toString())!!["title"])
    }

    @Test
    fun `メモの詳細は根拠の発話を返す`() = runTest {
        val id = record(speakerId = SELF_SPEAKER_ID)

        val detail = bridge.getMemo(id.toString())!!

        val utterance = (detail["utterances"] as List<*>).single() as Map<*, *>
        assertEquals(setOf("id", "text", "speaker", "speakerId", "speakerName", "startedAt"), utterance.keys)
        assertEquals("発話", utterance["text"])
        assertEquals("self", utterance["speaker"])
        assertEquals("自分", utterance["speakerName"])
    }

    @Test
    fun `ないメモは null を返す`() = runTest {
        assertNull(bridge.getMemo("none"))
        assertNull(bridge.getMemo("12345"))
    }

    private suspend fun leaveUnorganized(atMillis: Long) {
        utterances.insert(NewUtterance(atMillis, atMillis, "発話", null, atMillis))
    }

    @Test
    fun `未処理がなければ整理を始めない`() = runTest {
        assertEquals("nothing-to-organize", bridge.startOrganize())
        assertEquals("idle", bridge.organizeStatus()["state"])
        assertEquals(0, bridge.organizeStatus()["pendingChunks"])
    }

    @Test
    fun `話が途切れていない発話は待っていると伝える`() = runTest {
        leaveUnorganized(clock.nowMillis() - 1)

        val status = bridge.organizeStatus()

        assertEquals(0, status["pendingChunks"])
        assertEquals(true, status["isWaitingForSilence"])
        assertEquals("nothing-to-organize", bridge.startOrganize())
    }

    @Test
    fun `未処理があれば整理を始めて、進行の状態を返す`() = runTest {
        leaveUnorganized(0)
        clock.advanceBy(TEN_MINUTES)
        leaveUnorganized(clock.nowMillis())
        clock.advanceBy(TEN_MINUTES)

        assertEquals(2, bridge.organizeStatus()["pendingChunks"])
        assertEquals("started", bridge.startOrganize())

        assertEquals("preparing", bridge.organizeStatus()["state"])
    }

    @Test
    fun `動いている間に始めても二重には始めない`() = runTest {
        leaveUnorganized(0)
        clock.advanceBy(TEN_MINUTES)
        bridge.startOrganize()

        assertEquals("already-running", bridge.startOrganize())

        assertEquals(1, organize.starts)
    }

    @Test
    fun `止めると区切りで止まる途中の状態になる`() = runTest {
        leaveUnorganized(0)
        clock.advanceBy(TEN_MINUTES)
        bridge.startOrganize()

        bridge.stopOrganize()

        assertEquals("stopping", bridge.organizeStatus()["state"])
    }

    @Test
    fun `動いていないときに止めても何も起きない`() = runTest {
        bridge.stopOrganize()

        assertEquals("idle", bridge.organizeStatus()["state"])
    }

    @Test
    fun `整理のモデルがなければ始めない`() = runTest {
        leaveUnorganized(0)
        clock.advanceBy(TEN_MINUTES)
        organize.modelReady = false

        assertEquals("model-unavailable", bridge.startOrganize())
        assertEquals(0, organize.starts)
    }

    /** 読書会のメモが 2 つと、関係のないメモが 1 つ。 */
    private suspend fun recordRelated(): Map<String, String> = mapOf(
        record(title = "読書会の日程", summary = "次の読書会は来月の第2日曜", startedAt = 1).toString() to "old",
        record(title = "読書会の本", summary = "課題の本を読んでおく", startedAt = 3).toString() to "new",
        record(title = "庭の手入れ", summary = "土曜に土を買う", startedAt = 2).toString() to "other",
    )

    private suspend fun ask(question: String, progress: MutableList<String> = mutableListOf()) =
        bridge.askMemos(question) { progress += it }

    private fun unanswered(outcome: String) =
        mapOf("outcome" to outcome, "answer" to null, "sources" to emptyList<Any>())

    @Test
    fun `空の質問は reject する`() = runTest {
        recordRelated()

        listOf("", "  ").forEach { question ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { ask(question) } }
        }
    }

    @Test
    fun `関係するメモがなければ not-found で、答えも根拠もない`() = runTest {
        recordRelated()

        assertEquals(unanswered("not-found"), ask("引っ越し"))
    }

    @Test
    fun `関係するメモがあれば answered で、答えと根拠のメモを返す`() = runTest {
        val names = recordRelated()

        val answer = ask("読書会")

        assertEquals("answered", answer["outcome"])
        assertTrue((answer["answer"] as String).isNotEmpty())
        val sources = (answer["sources"] as List<*>).map { it as Map<*, *> }
        assertTrue(sources.isNotEmpty())
        sources.forEach { source ->
            assertTrue(names[source["id"]] in setOf("old", "new"), source.toString())
            assertFalse(source.containsKey("utterances"))
        }
    }

    @Test
    fun `解決するまでに進み具合が準備、検索、回答の順に届く`() = runTest {
        recordRelated()
        val progress = mutableListOf<String>()

        ask("読書会", progress)

        assertEquals(listOf("preparing", "searching", "answering"), progress)
    }

    @Test
    fun `整理のモデルがなければ model-unavailable を返す`() = runTest {
        recordRelated()
        organize.modelReady = false

        assertEquals(unanswered("model-unavailable"), ask("読書会"))
    }

    @Test
    fun `整理が動いている間は busy を返す`() = runTest {
        recordRelated()
        leaveUnorganized(0)
        clock.advanceBy(TEN_MINUTES)
        bridge.startOrganize()

        assertEquals(unanswered("busy"), ask("読書会"))
    }

    @Test
    fun `質問が動いていないときに止めても何も起きない`() = runTest {
        recordRelated()

        bridge.cancelAsk()

        assertEquals("answered", ask("読書会")["outcome"])
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
