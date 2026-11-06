package dev.ambientmemo.engine.adapters

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.InMemoryOrganizeAttempts
import dev.ambientmemo.engine.domain.Llm
import dev.ambientmemo.engine.domain.LlmInputTooLongException
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.OrganizeAttempts
import dev.ambientmemo.engine.domain.OrganizeBacklog
import dev.ambientmemo.engine.domain.OrganizeConfig
import dev.ambientmemo.engine.domain.OrganizeJob
import dev.ambientmemo.engine.domain.OrganizeOutcome
import dev.ambientmemo.engine.domain.OrganizeProgress
import dev.ambientmemo.engine.domain.SpeakerEmbedding
import dev.ambientmemo.engine.domain.organizeBacklog
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.FakeLlm
import dev.ambientmemo.engine.domain.testing.isTitleRequest
import dev.ambientmemo.engine.domain.testing.isTopicRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.TimeZone

/** 本物の SQLite と Fake の LLM で整理ジョブを通す。会話はすべて架空。 */
class OrganizeJobIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    private val config = OrganizeConfig()

    /** 2026-11-02 18:00 日本時間(月曜)。 */
    private val evening = 1_793_610_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val afterLastTalk = evening + 12 * hour

    private inner class Harness(val driver: JdbcSqliteDriver) {
        val database = AmbientMemoDatabase(driver)
        val utterances = SqliteUtteranceRepository(database)
        val memos = SqliteMemoRepository(database)
        val clock = FakeClock(afterLastTalk)

        val speakers = SqliteSpeakerRepository(database)

        fun job(llm: Llm, attempts: OrganizeAttempts = InMemoryOrganizeAttempts()) =
            OrganizeJob(memos, speakers, llm, clock, tokyo, config, attempts)

        suspend fun say(startedAtMillis: Long, text: String) = utterances
            .insert(NewUtterance(startedAtMillis, startedAtMillis + 2_000, text, 0.9, startedAtMillis + 2_000))
            .id

        /** 別の話題の会話を [count] 本、1 時間おきに入れる。 */
        suspend fun sayConversations(count: Int) = (0 until count).map { index ->
            val start = evening + index * hour
            listOf(say(start, "話題${index}の一言目"), say(start + minute, "話題${index}の二言目"))
        }

        fun column(sql: String): List<Any?> {
            val connection = driver.getConnection()
            try {
                return connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { result ->
                        buildList { while (result.next()) add(result.getObject(1)) }
                    }
                }
            } finally {
                driver.closeConnection(connection)
            }
        }

        fun count(table: String) = (column("SELECT count(*) FROM $table").single() as Number).toLong()
    }

    private fun withDatabase(block: suspend (Harness) -> Unit) = runTest {
        openTestDriver(dir.resolve("test.db")).use { driver -> block(Harness(driver)) }
    }

    @Test
    fun `発話からメモと根拠を作り、発話を処理済みにする`() = withDatabase { db ->
        val first = db.say(evening, "歯医者の予約、あさっての十時に取れたって")
        val second = db.say(evening + minute, "よかったね")
        val llm = FakeLlm.organizing(
            summary = "歯医者の予約です\n- 歯医者はあさって十時\n- 保険証を持っていく",
            title = "歯医者の予約",
        )

        val result = db.job(llm).run()

        assertEquals(OrganizeOutcome.COMPLETED, result.outcome)
        assertEquals(1, result.memoCount)
        val memo = db.memos.listMemos().single()
        assertEquals("歯医者の予約", memo.title)
        assertEquals("- 歯医者はあさって十時\n- 保険証を持っていく", memo.summary)
        assertEquals("fake-1", memo.modelVersion)
        assertEquals(evening, memo.startedAtMillis)
        assertEquals(evening + minute + 2_000, memo.endedAtMillis)
        assertEquals(listOf(first, second), db.memos.listUtteranceIds(memo.id))
        assertTrue(db.memos.listUnorganizedUtterances().isEmpty())
        assertEquals(listOf(afterLastTalk, afterLastTalk), db.column("SELECT organized_at_ms FROM utterance"))
    }

    @Test
    fun `タイトルは要約の1行目ではなく別に作る`() = withDatabase { db ->
        db.say(0, "週末に買い物へ行こう")
        val llm = FakeLlm.organizing(summary = "- 2026年11月1日(日曜日)\n- 週末に買い物へ行く", title = "週末の買い物")

        db.job(llm).run()

        assertEquals("週末の買い物", db.memos.listMemos().single().title)
    }

    @Test
    fun `タイトルが日付だけなら、日付でない要約の行で代わりにする`() = withDatabase { db ->
        db.say(0, "週末に買い物へ行こう")
        val llm = FakeLlm.organizing(summary = "- 2026年11月1日(日曜日)\n- 週末に買い物へ行く", title = "11月1日")

        db.job(llm).run()

        assertEquals("週末に買い物へ行く", db.memos.listMemos().single().title)
    }

    @Test
    fun `プロンプトの今日は実行日ではなく話した日で、文字起こしは端末の時刻になる`() = withDatabase { db ->
        db.say(evening, "明日までに請求書を送る")
        db.clock.advanceBy(3 * 24 * hour)
        val llm = FakeLlm.organizing()

        db.job(llm).run()

        assertEquals(2, llm.received.size)
        llm.received.filterNot { it.isTitleRequest }.forEach { request ->
            assertTrue("今日は 2026-11-02(月曜日) です" in request.user)
            assertTrue(request.user.endsWith("[18:00] 明日までに請求書を送る"))
        }
    }

    @Test
    fun `日本時間の深夜の会話は UTC の日付に引きずられない`() = withDatabase { db ->
        // 2026-11-03 00:30 日本時間。UTC ではまだ 11-02
        val midnight = evening + 6 * hour + 30 * minute
        db.say(midnight, "明日の朝、燃えるごみを出す")
        val llm = FakeLlm.organizing()

        db.job(llm).run()

        assertTrue("今日は 2026-11-03(火曜日) です" in llm.received.first().user)
    }

    @Test
    fun `無音の長さで別のメモに分ける`() = withDatabase { db ->
        val conversations = db.sayConversations(3)
        val llm = FakeLlm.organizing()

        val result = db.job(llm).run()

        assertEquals(3, result.memoCount)
        val memos = db.memos.listMemos()
        assertEquals(conversations, memos.map { db.memos.listUtteranceIds(it.id) })
        // まとまりごとに、話題の切れ目、要約、タイトル
        assertEquals(9, llm.received.size)
    }

    @Test
    fun `1 つのまとまりの中の話題を別のメモにする`() = withDatabase { db ->
        val ids = (0 until 4).map { db.say(evening + it * minute, "発話$it") }
        val llm = FakeLlm.organizing(topics = "1, 3")

        assertEquals(2, db.job(llm).run().memoCount)

        assertEquals(listOf(ids.take(2), ids.drop(2)), db.memos.listMemos().map { db.memos.listUtteranceIds(it.id) })
        assertTrue(db.memos.listUnorganizedUtterances().isEmpty())
    }

    @Test
    fun `話している途中の会話は処理せず、静かになってから1件のメモにする`() = withDatabase { db ->
        val first = db.say(evening, "一言目")
        db.clock.advanceBy(evening + minute - db.clock.nowMillis())
        val llm = FakeLlm.organizing()

        val during = db.job(llm).run()

        assertEquals(0, during.memoCount)
        assertTrue(llm.received.isEmpty())

        val second = db.say(evening + 2 * minute, "二言目")
        db.clock.advanceBy(hour)

        assertEquals(1, db.job(llm).run().memoCount)
        assertEquals(listOf(first, second), db.memos.listUtteranceIds(db.memos.listMemos().single().id))
    }

    @Test
    fun `もう一度実行しても重複せず、LLM も呼ばない`() = withDatabase { db ->
        db.sayConversations(2)
        val llm = FakeLlm.organizing()
        db.job(llm).run()
        val calls = llm.received.size

        val second = db.job(llm).run()

        assertEquals(0, second.memoCount)
        assertEquals(calls, llm.received.size)
        assertEquals(2, db.memos.listMemos().size)
        assertEquals(4L, db.count("memo_utterance"))
    }

    @Test
    fun `途中で止めると区切りまで記録し、次回は続きから処理して重複しない`() = withDatabase { db ->
        val conversations = db.sayConversations(3)
        val llm = FakeLlm.organizing()
        var checks = 0

        val stopped = db.job(llm).run(shouldContinue = { ++checks <= 1 })

        assertEquals(OrganizeOutcome.STOPPED, stopped.outcome)
        assertEquals(1, stopped.memoCount)
        assertEquals(conversations.drop(1).flatten(), db.memos.listUnorganizedUtterances().map { it.id })

        val resumed = db.job(llm).run()

        assertEquals(OrganizeOutcome.COMPLETED, resumed.outcome)
        assertEquals(2, resumed.memoCount)
        assertEquals(conversations, db.memos.listMemos().map { db.memos.listUtteranceIds(it.id) })
    }

    @Test
    fun `プロセスが死んでも、開き直せば続きから処理する`() = runTest {
        val path = dir.resolve("restart.db")
        openTestDriver(path).use { driver ->
            val db = Harness(driver)
            db.sayConversations(2)
            var checks = 0
            db.job(FakeLlm.organizing()).run(shouldContinue = { ++checks <= 1 })
        }

        openTestDriver(path).use { driver ->
            val db = Harness(driver)
            db.job(FakeLlm.organizing()).run()

            assertEquals(2, db.memos.listMemos().size)
            assertTrue(db.memos.listUnorganizedUtterances().isEmpty())
        }
    }

    @Test
    fun `書き込みの途中で失敗すると、そのまとまりは何も残らず、次回やり直せる`() = withDatabase { db ->
        val conversations = db.sayConversations(2)
        db.driver.execute(
            null,
            "CREATE TRIGGER fail_link BEFORE INSERT ON memo_utterance BEGIN SELECT RAISE(ABORT, '失敗'); END",
            0,
        )
        val llm = FakeLlm.organizing()

        val failure = runCatching { db.job(llm).run() }

        assertTrue(failure.isFailure)
        assertTrue(db.memos.listMemos().isEmpty())
        assertEquals(conversations.flatten(), db.memos.listUnorganizedUtterances().map { it.id })
        assertEquals(0L, db.count("memo_utterance"))

        db.driver.execute(null, "DROP TRIGGER fail_link", 0)

        assertEquals(2, db.job(llm).run().memoCount)
    }

    @Test
    fun `同じまとまりを別のジョブが先に記録したら、後の結果は捨てる`() = withDatabase { db ->
        db.say(evening, "一言目")
        val competing = FakeLlm.organizing()
        val llm = FakeLlm { index, request ->
            // 自分のタイトルの依頼の間に、別のジョブが同じまとまりを記録する
            if (request.isTitleRequest && index == 1) db.job(competing).run()
            "- 要約"
        }

        val result = db.job(llm).run()

        assertEquals(0, result.memoCount)
        assertEquals(1, db.memos.listMemos().size)
    }

    @Test
    fun `LLM が動かなければそのまとまりで止まり、次回は続きから処理する`() = withDatabase { db ->
        val conversations = db.sayConversations(3)
        var isBroken = false
        var calls = 0
        val llm = FakeLlm { _, request ->
            // 話題の切れ目の依頼は数えない。3 回目は 2 つ目のまとまりの要約
            if (!request.isTopicRequest && ++calls == 3) isBroken = true
            if (isBroken) throw IOException("メモリ不足")
            "- 要約"
        }

        val failed = db.job(llm).run()

        assertEquals(OrganizeOutcome.LLM_FAILED, failed.outcome)
        assertEquals(1, failed.memoCount)
        assertEquals(conversations.drop(1).flatten(), db.memos.listUnorganizedUtterances().map { it.id })

        isBroken = false
        calls = 10

        assertEquals(2, db.job(llm).run().memoCount)
    }

    @Test
    fun `LLM の内側のタイムアウトは LLM の失敗で、処理全体のキャンセルにしない`() = withDatabase { db ->
        db.say(evening, "一言目")
        val llm = FakeLlm { _, _ -> withTimeout(1) { delay(1_000); "" } }

        val result = db.job(llm).run()

        assertEquals(OrganizeOutcome.LLM_FAILED, result.outcome)
        assertEquals(1, db.memos.listUnorganizedUtterances().size)
    }

    @Test
    fun `キャンセルは呼び出し元へ伝わり、処理中のまとまりは残らない`() = withDatabase { db ->
        db.sayConversations(1)
        val started = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        val llm = FakeLlm { _, _ ->
            started.complete(Unit)
            CompletableDeferred<String>().await()
        }

        coroutineScope {
            val running = launch { runCatching { db.job(llm).run() }.onFailure { failure = it } }
            started.await()
            running.cancelAndJoin()
        }

        assertTrue(failure is CancellationException)
        assertFalse(failure is TimeoutCancellationException)
        assertTrue(db.memos.listMemos().isEmpty())
        assertEquals(2, db.memos.listUnorganizedUtterances().size)
    }

    @Test
    fun `要約が空でも、根拠の発話は残す`() = withDatabase { db ->
        db.say(evening, "あさって十時に歯医者")
        val llm = FakeLlm.organizing(summary = "  ")

        db.job(llm).run()

        val memo = db.memos.listMemos().single()
        assertNull(memo.title)
        assertNull(memo.summary)
        assertEquals(1, db.memos.listUtteranceIds(memo.id).size)
        // 要約と、指示を足した要約の再試行
        assertEquals(2, llm.received.size)
    }

    @Test
    fun `本文が空のまとまりは LLM を呼ばず、メモも作らずに処理済みにする`() = withDatabase { db ->
        db.say(evening, "   ")
        val llm = FakeLlm.organizing()

        db.job(llm).run()

        assertTrue(llm.received.isEmpty())
        assertTrue(db.memos.listMemos().isEmpty())
        assertTrue(db.memos.listUnorganizedUtterances().isEmpty())
    }

    @Test
    fun `文字数の上限を超える会話は無音がなくても分ける`() = withDatabase { db ->
        val long = "長い話が続く。".repeat(100)
        repeat(4) { db.say(evening + it * minute, long) }
        val llm = FakeLlm.organizing()

        val result = db.job(llm).run()

        assertEquals(2, result.memoCount)
        assertTrue(db.memos.listUnorganizedUtterances().isEmpty())
    }

    private fun FakeLlm.Companion.failingOn(word: String, failure: () -> Exception) = FakeLlm { _, request ->
        if (word in request.user) throw failure()
        "- 要約"
    }

    private fun Harness.unreadableMemos() = column("SELECT id FROM memo WHERE summary IS NULL")

    @Test
    fun `文脈長を超えたまとまりは半分に分けて処理する`() = withDatabase { db ->
        db.say(evening, "前半")
        db.say(evening + minute, "長すぎる後半")
        db.say(evening + 2 * minute, "続き")
        // 3 発話がそろっているときだけ長すぎる
        val llm = FakeLlm { _, request ->
            if ("前半" in request.user && "続き" in request.user) throw LlmInputTooLongException()
            "- 要約"
        }

        val result = db.job(llm).run()

        assertEquals(OrganizeOutcome.COMPLETED, result.outcome)
        assertEquals(2, result.memoCount)
        assertEquals(0, result.unreadableCount)
        assertTrue(db.memos.listUnorganizedUtterances().isEmpty())
    }

    @Test
    fun `1 発話でも文脈長を超えるなら、要約のないメモにして先へ進む`() = withDatabase { db ->
        val tooLong = db.say(evening, "とても長い発話")
        db.say(evening + hour, "次の話題")
        val llm = FakeLlm.failingOn("とても長い") { LlmInputTooLongException() }

        val result = db.job(llm).run()

        assertEquals(OrganizeOutcome.COMPLETED, result.outcome)
        assertEquals(2, result.memoCount)
        assertEquals(1, result.unreadableCount)
        val memo = db.memos.listMemos().first()
        assertNull(memo.summary)
        assertEquals(listOf(tooLong), db.memos.listUtteranceIds(memo.id))
        assertTrue(db.memos.listUnorganizedUtterances().isEmpty())
    }

    @Test
    fun `同じまとまりで失敗が続いても、後のまとまりが動けばそのまとまりを記録して進む`() = withDatabase { db ->
        db.say(evening, "壊れた話題")
        db.say(evening + hour, "次の話題")
        val llm = FakeLlm.failingOn("壊れた") { IOException("実行基盤の失敗") }
        val attempts = InMemoryOrganizeAttempts()

        val first = db.job(llm, attempts).run()
        val second = db.job(llm, attempts).run()
        val third = db.job(llm, attempts).run()

        assertEquals(OrganizeOutcome.LLM_FAILED, first.outcome)
        assertEquals(OrganizeOutcome.LLM_FAILED, second.outcome)
        assertEquals(OrganizeOutcome.COMPLETED, third.outcome)
        assertEquals(2, third.memoCount)
        assertEquals(1, third.unreadableCount)
        assertTrue(db.memos.listUnorganizedUtterances().isEmpty())
    }

    @Test
    fun `どのまとまりでも失敗するなら実行基盤の問題なので、発話を未処理のまま残す`() = withDatabase { db ->
        db.sayConversations(3)
        val llm = FakeLlm { _, _ -> throw IOException("GPU が使えない") }
        val attempts = InMemoryOrganizeAttempts()

        val results = List(6) { db.job(llm, attempts).run() }

        assertTrue(results.all { it.outcome == OrganizeOutcome.LLM_FAILED })
        assertEquals(0, db.count("memo"))
        assertEquals(6, db.memos.listUnorganizedUtterances().size)
    }

    @Test
    fun `クラッシュで処理が返らなくても回数が残り、そのまとまりを飛ばす`() = withDatabase { db ->
        val first = db.say(evening, "クラッシュする話題")
        db.say(evening + hour, "次の話題")
        val attempts = InMemoryOrganizeAttempts()
        // 始める前の記録だけが残る状態。ネイティブのクラッシュでは例外も後始末も動かない
        attempts.set(first, config.maxChunkAttempts)
        val llm = FakeLlm.failingOn("クラッシュ") { AssertionError("呼ばれないはず") as Exception }

        val result = db.job(llm, attempts).run()

        assertEquals(OrganizeOutcome.COMPLETED, result.outcome)
        assertEquals(1, result.unreadableCount)
        assertTrue(llm.received.none { "クラッシュ" in it.user })
        assertEquals(1, db.unreadableMemos().size)
    }

    @Test
    fun `飛ばしたまとまりしか残っていなければ、記録せず次回に回す`() = withDatabase { db ->
        val only = db.say(evening, "最後の話題")
        val attempts = InMemoryOrganizeAttempts()
        attempts.set(only, config.maxChunkAttempts)

        val result = db.job(FakeLlm.organizing(), attempts).run()

        assertEquals(OrganizeOutcome.LLM_FAILED, result.outcome)
        assertEquals(0, db.count("memo"))
    }

    @Test
    fun `成功したら回数を消す`() = withDatabase { db ->
        val id = db.say(evening, "話題")
        val attempts = InMemoryOrganizeAttempts()
        attempts.set(id, 1)

        db.job(FakeLlm.organizing(), attempts).run()

        assertEquals(0, attempts.count(id))
    }

    @Test
    fun `キャンセルはまとまりのせいではないので、回数を増やさない`() = withDatabase { db ->
        val id = db.say(evening, "話題")
        val started = CompletableDeferred<Unit>()
        val llm = FakeLlm { _, _: LlmRequest ->
            started.complete(Unit)
            delay(Long.MAX_VALUE)
            ""
        }
        val attempts = InMemoryOrganizeAttempts()

        coroutineScope {
            val job = launch { db.job(llm, attempts).run() }
            started.await()
            job.cancelAndJoin()
        }

        assertEquals(0, attempts.count(id))
        assertEquals(1, db.memos.listUnorganizedUtterances().size)
    }

    @Test
    fun `版 1 の発話は移行後に未処理として整理できる`() = runTest {
        val path = dir.resolve("old.db")
        Files.copy(Path.of(System.getProperty("ambientmemo.schemaDir")).resolve("1.db"), path)
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            driver.execute(
                null,
                "INSERT INTO utterance (started_at_ms, ended_at_ms, body, confidence, created_at_ms) " +
                    "VALUES ($evening, ${evening + 1_000}, '移行前の発話', NULL, $evening)",
                0,
            )
            driver.execute(null, "PRAGMA user_version = 1", 0)
        }

        openTestDriver(path).use { driver ->
            val db = Harness(driver)

            assertEquals(1, db.memos.listUnorganizedUtterances().size)
            assertEquals(1, db.job(FakeLlm.organizing()).run().memoCount)
        }
    }

    @Test
    fun `文字起こしを話者のラベルつきで LLM に渡す`() = runTest {
        openTestDriver(dir.resolve("labels.db")).use { driver ->
            val harness = Harness(driver)
            val self = harness.speakers.saveSelfEmbedding(SpeakerEmbedding(floatArrayOf(1f, 0f), "m"), 3, evening)
            val other = harness.speakers.createProvisionalSpeaker(SpeakerEmbedding(floatArrayOf(0f, 1f), "m"), evening)
            val llm = FakeLlm.organizing()
            harness.utterances.insert(
                NewUtterance(evening, evening + 1_000, "請求書、明日までに送ってくれる?", null, evening, other.id, 0.8),
            )
            harness.utterances.insert(
                NewUtterance(evening + minute, evening + minute + 1_000, "はい、送ります。", null, evening, self.id, 0.9),
            )
            harness.utterances.insert(
                NewUtterance(evening + 2 * minute, evening + 2 * minute + 1_000, "うん", null, evening),
            )

            harness.job(llm).run()

            val expected = "[18:00] 話者1: 請求書、明日までに送ってくれる?\n[18:01] 自分: はい、送ります。\n[18:02] うん"
            assertEquals(3, llm.received.size)
            val (topics, others) = llm.received.filterNot { it.isTitleRequest }.partition { it.isTopicRequest }
            val numbered = "1 [18:00] 話者1: 請求書、明日までに送ってくれる?\n2 [18:01] 自分: はい、送ります。\n3 [18:02] うん"
            assertTrue(topics.single().user.endsWith(numbered), topics.single().user)
            others.forEach { assertTrue(it.user.endsWith(expected), it.user) }
        }
    }

    @Test
    fun `今整理できる量は実行と同じ分け方で数え、話の途中の発話は待っているものとして数える`() = runTest {
        openTestDriver(dir.resolve("backlog.db")).use { driver ->
            val harness = Harness(driver)
            harness.sayConversations(2)
            harness.say(afterLastTalk - minute, "今の話")

            val backlog = organizeBacklog(harness.memos, emptyList(), harness.clock, tokyo, config)

            assertEquals(OrganizeBacklog(readyChunks = 2, hasOngoing = true), backlog)
            assertEquals(2, harness.job(FakeLlm.organizing()).run().memoCount)
            assertEquals(
                OrganizeBacklog(readyChunks = 0, hasOngoing = true),
                organizeBacklog(harness.memos, emptyList(), harness.clock, tokyo, config),
            )
        }
    }

    @Test
    fun `進み具合は対象が決まったときとまとまりを終えるたびに届く`() = runTest {
        openTestDriver(dir.resolve("progress.db")).use { driver ->
            val harness = Harness(driver)
            harness.sayConversations(2)
            val progress = mutableListOf<OrganizeProgress>()

            harness.job(FakeLlm.organizing()).run(onProgress = { progress += it })

            assertEquals(listOf(OrganizeProgress(0, 2), OrganizeProgress(1, 2), OrganizeProgress(2, 2)), progress)
        }
    }
}
