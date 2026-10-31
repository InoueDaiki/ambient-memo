package dev.ambientmemo.engine.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.TimeZone

private const val MAX_TITLE_CHARS = 30

/**
 * 未処理の発話をメモと ToDo に整理する。
 *
 * まとまりごとに、メモ、ToDo、発話の処理済みの印を1つのトランザクションで記録する。
 * 途中で止まっても、記録していないまとまりは未処理のまま残り、次回の実行が続きから処理する。
 *
 * [timeZone] は発話の日付を決める。「今日」は実行時ではなく、話した日にする。
 * 夜の充電中に整理するので、実行が日付をまたぐと「明日」がずれるため。
 */
class OrganizeJob(
    private val memos: MemoRepository,
    private val llm: Llm,
    private val clock: Clock,
    private val timeZone: TimeZone,
    private val config: OrganizeConfig = OrganizeConfig(),
) {
    /**
     * 処理できるまとまりを古い順に処理する。[shouldContinue] はまとまりの間で確かめる。
     * 充電が終わったらここで止める。すぐに止めたいときはコルーチンをキャンセルする。
     *
     * LLM の例外は [OrganizeOutcome.LLM_FAILED] で返す。DB の例外とキャンセルはそのまま投げる。
     */
    suspend fun run(shouldContinue: () -> Boolean = { true }): OrganizeResult {
        val chunks = splitIntoChunks(memos.listUnorganizedUtterances(), config, timeZone)
            .withoutOngoing(clock.nowMillis(), config)
        val tally = Tally()
        val stoppedBy = chunks.firstNotNullOfOrNull { chunk ->
            if (shouldContinue()) process(chunk, tally) else OrganizeOutcome.STOPPED
        }
        return OrganizeResult(stoppedBy ?: OrganizeOutcome.COMPLETED, tally.memoCount, tally.todoCount)
    }

    private class Tally(var memoCount: Int = 0, var todoCount: Int = 0)

    /** まとまりを1つ記録する。続けられないときは止まった理由を返す。 */
    private suspend fun process(chunk: UtteranceChunk, tally: Tally): OrganizeOutcome? {
        val memo = if (chunk.isBlank) null else draftOrNull(chunk)
        when {
            chunk.isBlank -> memos.markOrganized(chunk.utterances.map { it.id }, clock.nowMillis())
            memo == null -> return OrganizeOutcome.LLM_FAILED
            memos.recordMemo(memo) != null -> {
                tally.memoCount++
                tally.todoCount += memo.todos.size
            }
        }
        return null
    }

    // 実行基盤の失敗は型で絞れない。例外の中身は文字起こしを含みうるので、外へ渡さない
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun draftOrNull(chunk: UtteranceChunk): NewMemo? = try {
        draft(chunk)
    } catch (e: Exception) {
        // キャンセルかどうかは例外の型ではなく状態で判断する。アダプターの内側の withTimeout で止めないため
        currentCoroutineContext().ensureActive()
        null
    }

    private suspend fun draft(chunk: UtteranceChunk): NewMemo {
        val transcript = chunk.toTranscript(timeZone)
        val spokenOn = CalendarDate.of(chunk.startedAtMillis, timeZone)
        val endedOn = CalendarDate.of(chunk.endedAtMillis, timeZone)
        val summary = summarize(spokenOn, transcript)
        val parsed = extractTodos(spokenOn, transcript)
        return NewMemo(
            startedAtMillis = chunk.startedAtMillis,
            endedAtMillis = chunk.endedAtMillis,
            title = summary?.let(::titleOf),
            summary = summary,
            todoExtraction = if (parsed.isComplete) TodoExtraction.COMPLETE else TodoExtraction.INCOMPLETE,
            modelVersion = llm.modelVersion,
            createdAtMillis = clock.nowMillis(),
            utteranceIds = chunk.utterances.map { it.id },
            todos = parsed.items
                .distinctBy { item -> item.task.filterNot(Char::isWhitespace) }
                .map { item -> item.toNewTodo(assessDue(item.due, spokenOn, endedOn, transcript)) },
        )
    }

    private suspend fun summarize(date: CalendarDate, transcript: String): String? {
        val request = OrganizePrompts.summary(date, transcript)
        for (attempt in 0..config.maxRetries) {
            val summary = parseSummary(llm.generate(request.forAttempt(attempt, OrganizePrompts.SUMMARY_RETRY_NOTE)))
            if (summary != null) return summary
        }
        return null
    }

    /** 温度 0 では同じ入力に同じ出力が返るので、再試行では指示を足す。読めなければ空の不完全な結果を返す。 */
    private suspend fun extractTodos(date: CalendarDate, transcript: String): ParsedTodos {
        val request = OrganizePrompts.todo(date, transcript)
        var best: ParsedTodos? = null
        for (attempt in 0..config.maxRetries) {
            val parsed = parseTodos(llm.generate(request.forAttempt(attempt, OrganizePrompts.TODO_RETRY_NOTE)))
            if (parsed?.isComplete == true) return parsed
            best = parsed ?: best
        }
        return best ?: ParsedTodos(emptyList(), isComplete = false)
    }

    private fun LlmRequest.forAttempt(attempt: Int, retryNote: String) =
        if (attempt == 0) this else copy(user = user + retryNote)

    /** タイトルは要約の1行目から作る。検証していないプロンプトと LLM の呼び出しを増やさないため。 */
    private fun titleOf(summary: String): String =
        summary.lineSequence().first().removePrefix("- ").trim().take(MAX_TITLE_CHARS)

    private fun RawTodo.toNewTodo(due: AssessedDue) = NewTodo(
        task = task,
        kind = if (kind?.trim()?.lowercase() == "reminder") TodoKind.REMINDER else TodoKind.TODO,
        dueDate = due.date,
        status = if (due.isConfident) TodoStatus.OPEN else TodoStatus.CANDIDATE,
    )
}
