package dev.ambientmemo.engine.llm.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ambientmemo.engine.adapters.SqliteMemoRepository
import dev.ambientmemo.engine.adapters.SqliteUtteranceRepository
import dev.ambientmemo.engine.adapters.openAndroidDatabase
import dev.ambientmemo.engine.domain.Llm
import dev.ambientmemo.engine.domain.LlmInputTooLongException
import dev.ambientmemo.engine.domain.LlmRequest
import dev.ambientmemo.engine.domain.NewUtterance
import dev.ambientmemo.engine.domain.OrganizeJob
import dev.ambientmemo.engine.domain.OrganizeOutcome
import dev.ambientmemo.engine.domain.SystemClock
import dev.ambientmemo.engine.domain.TodoExtraction
import dev.ambientmemo.engine.domain.testing.LlmContract
import dev.ambientmemo.engine.llm.LocalLlm
import dev.ambientmemo.engine.sherpa.ModelCatalog
import dev.ambientmemo.engine.sherpa.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.TimeZone

private const val TAG = "AndroidLlmContract"
private const val CANCEL_AFTER_MILLIS = 2_000L
private const val LONG_INPUT_REPEATS = 2_000
private const val DAY_MILLIS = 86_400_000L
private const val UTTERANCE_MILLIS = 3_000L

private class AndroidLlmContract(private val llm: Llm) : LlmContract() {
    public override fun llm(): Llm = llm
}

/**
 * 契約テストを本物の LiteRT-LM と Gemma に流す。
 *
 * 契約テストは JUnit 5 で書いてあり、端末のランナーでは動かないので、`@Test` の付いたメソッドを探して呼ぶ。
 * モデルは `scripts/push-llm-model.sh dev.ambientmemo.engine.llm.android.test.test` で内部ストレージに置く。
 * 手で動かすテストなので、モデルがなければ飛ばさず失敗させる。飛ばすと、通っていないのに OK と表示される。
 */
@RunWith(AndroidJUnit4::class)
class AndroidLlmContractTest {
    private lateinit var llm: LocalLlm

    @Before
    fun loadModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = ModelCatalog.ACTIVE_LLM
        val store = ModelStore(context.filesDir.resolve("models"))
        check(store.isInstalled(model.modelPackage)) { "モデルが内部ストレージにありません" }
        val cache = File(context.cacheDir, "llm").apply { mkdirs() }
        val started = System.currentTimeMillis()
        llm = LocalLlm(model.version, LiteRtLlmLoader(store.pathOf(model.modelPackage, model.model), cache.path))
        llm.load()
        Log.i(TAG, "読み込み: ${System.currentTimeMillis() - started} ms")
    }

    @After
    fun releaseModel() = llm.close()

    @Test
    fun contract() {
        val contract = AndroidLlmContract(llm)
        val tests = generateSequence<Class<*>>(contract.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { it.isAnnotationPresent(org.junit.jupiter.api.Test::class.java) }
            .toList()
        check(tests.isNotEmpty()) { "契約テストが見つかりません" }
        tests.forEach { test ->
            test.isAccessible = true
            try {
                test.invoke(contract)
                Log.i(TAG, "通過: ${test.name}")
            } catch (e: InvocationTargetException) {
                throw AssertionError("${test.name} が失敗しました", e.cause)
            }
        }
    }

    /** 架空の会話を、本物の SQLite、プロンプト、解析、モデルで通す。 */
    @Test
    fun organizesFictionalConversation() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("ambient-memo.db")
        val database = openAndroidDatabase(context)
        val utterances = SqliteUtteranceRepository(database)
        val startedAt = System.currentTimeMillis() - DAY_MILLIS
        listOf(
            "明日の午前中に歯医者の予約を入れておいてくれる?",
            "わかった、自分が電話しておくよ。",
            "保険証も忘れないでね。",
        ).forEachIndexed { index, text ->
            val at = startedAt + index * UTTERANCE_MILLIS
            utterances.insert(NewUtterance(at, at + UTTERANCE_MILLIS, text, null, at + UTTERANCE_MILLIS))
        }
        val memos = SqliteMemoRepository(database)

        val started = System.currentTimeMillis()
        val result = OrganizeJob(memos, llm, SystemClock, TimeZone.getDefault()).run()
        Log.i(TAG, "整理: ${System.currentTimeMillis() - started} ms $result")

        assertEquals(OrganizeOutcome.COMPLETED, result.outcome)
        val memo = memos.listMemos().single()
        Log.i(TAG, "要約: ${memo.summary}")
        Log.i(TAG, "ToDo: ${memos.listTodos(memo.id).map { it.task to it.dueDate }}")
        assertTrue(memo.summary.orEmpty().contains("歯医者"))
        assertEquals(TodoExtraction.COMPLETE, memo.todoExtraction)
        assertTrue(memos.listTodos(memo.id).any { "歯医者" in it.task })
        assertEquals(llm.modelVersion, memo.modelVersion)
    }

    @Test
    fun answersInJapanese() = runBlocking<Unit> {
        val output = llm.generate(LlmRequest("あなたは整理係です。", "日本の首都を一語で答えてください。", maxOutputTokens = 16))

        assertTrue(output, "東京" in output)
    }

    @Test
    fun tellsInputTooLong() = runBlocking<Unit> {
        val input = "今日は会議で来週の予定について話し合いました。".repeat(LONG_INPUT_REPEATS)

        try {
            llm.generate(LlmRequest("あなたは整理係です。", input, maxOutputTokens = 16))
            fail("文脈長を超えたのに失敗しませんでした")
        } catch (_: LlmInputTooLongException) {
            Log.i(TAG, "文脈長の超過を見分けました")
        }
    }

    @Test
    fun staysUsableAfterCancellation() = runBlocking<Unit> {
        val running = async(Dispatchers.Default) {
            llm.generate(LlmRequest("あなたは整理係です。", "日本の歴史を詳しく説明してください。", maxOutputTokens = 400))
        }
        delay(CANCEL_AFTER_MILLIS)
        running.cancelAndJoin()

        assertTrue(runCatching { running.await() }.exceptionOrNull() is CancellationException)
        assertEquals(
            true,
            "はい" in llm.generate(LlmRequest("あなたは整理係です。", "「はい」とだけ答えてください。", maxOutputTokens = 16)),
        )
    }
}
