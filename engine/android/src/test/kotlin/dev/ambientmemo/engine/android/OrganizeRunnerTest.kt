@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.android

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.ambientmemo.engine.adapters.SqliteMemoRepository
import dev.ambientmemo.engine.adapters.SqliteSpeakerRepository
import dev.ambientmemo.engine.adapters.SqliteUtteranceRepository
import dev.ambientmemo.engine.adapters.db.AmbientMemoDatabase
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.OrganizeJob
import dev.ambientmemo.engine.domain.OrganizeTrigger
import dev.ambientmemo.engine.domain.testing.FakeClock
import dev.ambientmemo.engine.domain.testing.isTodoRequest
import dev.ambientmemo.engine.llm.LocalLlm
import dev.ambientmemo.engine.llm.NativeLlm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

private const val HOUR = 3_600_000L
private const val TIMEOUT_MILLIS = 10_000L

/** 本物の SQLite と LLM の Fake で、定期実行と手動の実行の組み合わせを通す。Service と OS は通らない。 */
class OrganizeRunnerTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), AmbientMemoDatabase.Schema)
    private val database = AmbientMemoDatabase(driver)
    private val utterances = SqliteUtteranceRepository(database, Dispatchers.Unconfined)
    private val memos = SqliteMemoRepository(database, Dispatchers.Unconfined)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statuses = CopyOnWriteArrayList<OrganizeStatus>()
    private val finished = CompletableDeferred<Pair<OrganizeRunResult, OrganizeEnding?>>()

    @Volatile
    private var device = OrganizeDeviceState(isCharging = true, isTooHot = false)

    @Volatile
    private var loads = 0

    /** 1 つ目のまとまりの要約を作っている間に呼ぶ。 */
    @Volatile
    private var duringFirstChunk: () -> Unit = {}

    /** モデルを読み込んでいる間に呼ぶ。 */
    @Volatile
    private var duringLoad: () -> Unit = {}

    private inner class ScriptedNative : NativeLlm {
        private var calls = 0

        override fun generate(request: LlmRequest): String {
            if (calls++ == 0) duringFirstChunk()
            return if (request.isTodoRequest) """{"items": []}""" else "- 要約"
        }

        override fun cancel() = Unit

        override fun close() = Unit
    }

    private val runner = OrganizeRunner(
        scope = scope,
        session = { onProgress ->
            OrganizeSession(
                isModelReady = { true },
                openLlm = {
                    LocalLlm("stub-1", {
                        loads++
                        duringLoad()
                        ScriptedNative()
                    })
                },
                jobFor = { llm ->
                    OrganizeJob(
                        memos,
                        SqliteSpeakerRepository(database, Dispatchers.Unconfined),
                        llm,
                        FakeClock(100 * HOUR),
                        TimeZone.getTimeZone("UTC"),
                    )
                },
                onProgress = onProgress,
            )
        },
        device = { device },
        onStatus = { statuses += it },
        onFinished = { result, ending -> finished.complete(result to ending) },
    )

    /** 実行が DB を使い終えるのを待ってから閉じる。待たないと、閉じた DB に触れた例外が次のテストで報告される。 */
    @AfterEach
    fun close() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        driver.close()
    }

    private fun sayConversations(count: Int) = runBlocking {
        repeat(count) { index ->
            val at = index * 2 * HOUR
            utterances.insert(NewUtterance(at, at + 1, "話題$index", null, at + 1))
        }
    }

    private fun awaitFinished() = runBlocking { withTimeout(TIMEOUT_MILLIS) { finished.await() } }

    private fun unorganized() = runBlocking { memos.listUnorganizedUtterances().size }

    private fun memoCount() = runBlocking { memos.listMemos().size }

    @Test
    fun `手動の実行は充電が切れていても最後まで動く`() {
        sayConversations(3)
        device = OrganizeDeviceState(isCharging = false, isTooHot = false)

        assertEquals(OrganizeStart.STARTED, runner.start(OrganizeTrigger.MANUAL))

        assertEquals(OrganizeRunResult.COMPLETED to OrganizeEnding.COMPLETED, awaitFinished())
        assertEquals(3, memoCount())
        assertEquals(0, unorganized())
    }

    @Test
    fun `定期実行は充電が切れると読み込まずに止まり、発話は未処理のまま残る`() {
        sayConversations(2)
        device = OrganizeDeviceState(isCharging = false, isTooHot = false)

        runner.start(OrganizeTrigger.SCHEDULED)

        assertEquals(OrganizeRunResult.STOPPED to null, awaitFinished())
        assertEquals(0, loads)
        assertEquals(2, unorganized())
    }

    @Test
    fun `定期実行は途中で充電が切れるとまとまりの区切りで止まる`() {
        sayConversations(3)
        duringFirstChunk = { device = OrganizeDeviceState(isCharging = false, isTooHot = false) }

        runner.start(OrganizeTrigger.SCHEDULED)

        assertEquals(OrganizeRunResult.STOPPED, awaitFinished().first)
        assertEquals(1, memoCount())
    }

    @Test
    fun `手動の実行でも途中で熱くなると区切りで止まり、理由を残す`() {
        sayConversations(3)
        duringFirstChunk = { device = OrganizeDeviceState(isCharging = false, isTooHot = true) }

        runner.start(OrganizeTrigger.MANUAL)

        assertEquals(OrganizeRunResult.STOPPED to OrganizeEnding.TOO_HOT, awaitFinished())
        assertEquals(1, memoCount())
        assertEquals(OrganizeEnding.TOO_HOT, runner.status.ending)
    }

    @Test
    fun `読み込み中に止めると、まとまりに入る前だと分かる`() {
        sayConversations(2)
        var beforeChunks = false
        duringLoad = {
            runner.requestStop()
            beforeChunks = runner.isStoppingBeforeChunks
        }

        runner.start(OrganizeTrigger.MANUAL)

        assertEquals(OrganizeRunResult.STOPPED to OrganizeEnding.STOPPED, awaitFinished())
        assertTrue(beforeChunks)
        assertEquals(2, unorganized())
    }

    @Test
    fun `まとまりの処理中に止めても、読み込み待ちとは扱わない`() {
        sayConversations(2)
        var beforeChunks = true
        duringFirstChunk = {
            runner.requestStop()
            beforeChunks = runner.isStoppingBeforeChunks
        }

        runner.start(OrganizeTrigger.MANUAL)

        assertEquals(OrganizeRunResult.STOPPED to OrganizeEnding.STOPPED, awaitFinished())
        assertFalse(beforeChunks)
    }

    @Test
    fun `始める時点で熱ければ読み込まずに理由を残す`() {
        sayConversations(1)
        device = OrganizeDeviceState(isCharging = true, isTooHot = true)

        runner.start(OrganizeTrigger.MANUAL)

        assertEquals(OrganizeRunResult.STOPPED to OrganizeEnding.TOO_HOT, awaitFinished())
        assertEquals(0, loads)
    }

    @Test
    fun `止める操作はまとまりの区切りで止め、処理したまとまりは残る`() {
        sayConversations(3)
        duringFirstChunk = { runner.requestStop() }

        runner.start(OrganizeTrigger.MANUAL)

        assertEquals(OrganizeRunResult.STOPPED to OrganizeEnding.STOPPED, awaitFinished())
        assertEquals(1, memoCount())
        assertEquals(2, unorganized())
        assertTrue(statuses.any { it.phase == OrganizePhase.STOPPING })
    }

    @Test
    fun `止めた途中の状態は進み具合が届いても変わらない`() {
        sayConversations(2)
        duringFirstChunk = { runner.requestStop() }

        runner.start(OrganizeTrigger.MANUAL)
        awaitFinished()

        val afterStop = statuses.dropWhile { it.phase != OrganizePhase.STOPPING }.dropLast(1)
        assertTrue(afterStop.all { it.phase == OrganizePhase.STOPPING })
    }

    @Test
    fun `手動の二重の開始では読み込みも処理も1回だけ`() {
        sayConversations(2)
        val second = CompletableDeferred<OrganizeStart>()
        duringFirstChunk = { second.complete(runner.start(OrganizeTrigger.MANUAL)) }

        runner.start(OrganizeTrigger.MANUAL)
        awaitFinished()

        assertEquals(OrganizeStart.JOINED, runBlocking { second.await() })
        assertEquals(1, loads)
        assertEquals(2, memoCount())
    }

    @Test
    fun `手動の実行の間は定期実行を始めない`() {
        sayConversations(1)
        val scheduled = CompletableDeferred<OrganizeStart>()
        duringFirstChunk = { scheduled.complete(runner.start(OrganizeTrigger.SCHEDULED)) }

        runner.start(OrganizeTrigger.MANUAL)
        awaitFinished()

        assertEquals(OrganizeStart.BUSY, runBlocking { scheduled.await() })
        assertEquals(1, loads)
    }

    @Test
    fun `定期実行の途中で手動に切り替えると、充電が切れても WorkManager に止められても続く`() {
        sayConversations(3)
        duringFirstChunk = {
            runner.start(OrganizeTrigger.MANUAL)
            runner.requestSchedulerStop()
            device = OrganizeDeviceState(isCharging = false, isTooHot = false)
        }

        runner.start(OrganizeTrigger.SCHEDULED)

        assertEquals(OrganizeRunResult.COMPLETED to OrganizeEnding.COMPLETED, awaitFinished())
        assertEquals(1, loads)
        assertEquals(3, memoCount())
    }

    @Test
    fun `定期実行は WorkManager に止められるとまとまりの区切りで止まる`() {
        sayConversations(2)
        duringFirstChunk = { runner.requestSchedulerStop() }

        runner.start(OrganizeTrigger.SCHEDULED)

        assertEquals(OrganizeRunResult.STOPPED to null, awaitFinished())
        assertEquals(1, memoCount())
    }

    @Test
    fun `進み具合はまとまりごとに増える`() {
        sayConversations(2)

        runner.start(OrganizeTrigger.MANUAL)
        awaitFinished()

        val progress = statuses.filter { it.phase == OrganizePhase.ORGANIZING }
            .map { it.completedChunks to it.totalChunks }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
    }

    @Test
    fun `終わった後はもう一度始められる`() {
        sayConversations(1)
        runner.start(OrganizeTrigger.MANUAL)
        awaitFinished()

        assertEquals(OrganizeStart.STARTED, runner.start(OrganizeTrigger.SCHEDULED))
    }

    @Test
    fun `OS に止められた実行は区切りを待たずに結果を残す`() {
        sayConversations(1)
        val abandoned = CompletableDeferred<Unit>()
        duringFirstChunk = {
            runner.abandon(OrganizeEnding.TIME_LIMIT)
            abandoned.complete(Unit)
        }

        runner.start(OrganizeTrigger.MANUAL)
        runBlocking { withTimeout(TIMEOUT_MILLIS) { abandoned.await() } }

        assertEquals(OrganizeEnding.TIME_LIMIT, runner.status.ending)
        assertFalse(runner.isRunning)
    }

    @Test
    fun `読み込めないときは手動の結果を失敗にする`() {
        val failing = OrganizeRunner(
            scope = scope,
            session = {
                OrganizeSession(
                    isModelReady = { true },
                    openLlm = { LocalLlm("stub-1", { throw IOException("メモリ不足") }) },
                    jobFor = { error("読み込めないので呼ばない") },
                )
            },
            device = { device },
            onStatus = {},
            onFinished = { result, ending -> finished.complete(result to ending) },
        )

        failing.start(OrganizeTrigger.MANUAL)

        assertEquals(OrganizeRunResult.FAILED to OrganizeEnding.FAILED, awaitFinished())
    }
}

class OrganizeStatusTrackerTest {
    private val running = OrganizeStatus(OrganizePhase.ORGANIZING, 1, 3, isManual = true)

    @Test
    fun `別のプロセスが落ちたら手動の実行は失敗として出し、見守る側は動き続ける`() {
        val tracker = OrganizeStatusTracker()
        tracker.onStatus(running)

        assertTrue(tracker.onLost())

        assertEquals(OrganizeEnding.FAILED, tracker.status.ending)
        assertFalse(tracker.status.isRunning)
    }

    @Test
    fun `定期実行が落ちても結果は画面に出さない`() {
        val tracker = OrganizeStatusTracker()
        tracker.onStatus(running.copy(isManual = false))

        assertFalse(tracker.onLost())

        assertNull(tracker.status.ending)
        assertFalse(tracker.status.isRunning)
    }

    @Test
    fun `終わった後に接続が切れても結果を上書きしない`() {
        val tracker = OrganizeStatusTracker()
        tracker.onStatus(OrganizeStatus(isManual = true, ending = OrganizeEnding.TOO_HOT))

        assertFalse(tracker.onLost())

        assertEquals(OrganizeEnding.TOO_HOT, tracker.status.ending)
    }

    @Test
    fun `開始の依頼の直後に前の待機中の状態が届いても動いている表示のまま`() {
        val tracker = OrganizeStatusTracker()
        tracker.onStartRequested()

        tracker.onStatus(OrganizeStatus())

        assertTrue(tracker.status.isRunning)
    }

    @Test
    fun `返事がないまま時間がたつと失敗にする`() {
        val tracker = OrganizeStatusTracker()
        tracker.onStartRequested()

        assertTrue(tracker.onStartTimedOut())

        assertEquals(OrganizeEnding.FAILED, tracker.status.ending)
    }

    @Test
    fun `返事があれば時間切れにしない`() {
        val tracker = OrganizeStatusTracker()
        tracker.onStartRequested()
        tracker.onStatus(running)

        assertFalse(tracker.onStartTimedOut())

        assertTrue(tracker.status.isRunning)
    }
}
