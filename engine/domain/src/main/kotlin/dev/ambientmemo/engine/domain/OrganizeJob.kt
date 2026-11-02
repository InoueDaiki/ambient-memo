package dev.ambientmemo.engine.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
 *
 * 失敗の回数は [attempts] に残す。アプリでは実行をまたいで残る実装を渡す。
 */
@Suppress("TooManyFunctions", "LongParameterList") // まとまりの処理の流れを1か所で読めるようにする
class OrganizeJob(
    private val memos: MemoRepository,
    private val speakers: SpeakerRepository,
    private val llm: Llm,
    private val clock: Clock,
    private val timeZone: TimeZone,
    private val config: OrganizeConfig = OrganizeConfig(),
    private val attempts: OrganizeAttempts = InMemoryOrganizeAttempts(),
) {
    /**
     * 処理できるまとまりを古い順に処理する。[shouldContinue] はまとまりの間で確かめる。
     * 充電が終わったらここで止める。すぐに止めたいときはコルーチンをキャンセルする。
     * [onProgress] は対象のまとまりが決まったときと、まとまりを1つ終えるたびに呼ぶ。
     *
     * LLM の例外は [OrganizeOutcome.LLM_FAILED] で返す。DB の例外とキャンセルはそのまま投げる。
     * 特定のまとまりで止まり続けないように、読めないまとまりは要約のないメモとして記録して先へ進む。
     */
    suspend fun run(
        shouldContinue: () -> Boolean = { true },
        onProgress: (OrganizeProgress) -> Unit = {},
    ): OrganizeResult {
        val labels = SpeakerLabels(speakers.listSpeakers())
        val chunks = splitIntoChunks(memos.listUnorganizedUtterances(), config, timeZone, labels)
            .withoutOngoing(clock.nowMillis(), config)
        val pass = Pass(labels)
        onProgress(OrganizeProgress(0, chunks.size))
        val stoppedBy = chunks.withIndex().firstNotNullOfOrNull { (index, chunk) ->
            if (shouldContinue()) {
                process(chunk, pass).also { stopped ->
                    if (stopped == null) onProgress(OrganizeProgress(index + 1, chunks.size))
                }
            } else {
                OrganizeOutcome.STOPPED
            }
        }
        // 飛ばしたまとまりの後で LLM が動いたことを確かめられなかった。実行基盤の不調かもしれないので記録しない
        val finished = if (pass.suspects.isEmpty()) OrganizeOutcome.COMPLETED else OrganizeOutcome.LLM_FAILED
        return OrganizeResult(stoppedBy ?: finished, pass.memoCount, pass.todoCount, pass.unreadableCount)
    }

    private class Pass(
        val labels: SpeakerLabels,
        var memoCount: Int = 0,
        var todoCount: Int = 0,
        var unreadableCount: Int = 0,
        val suspects: MutableList<UtteranceChunk> = mutableListOf(),
    )

    private sealed interface Draft {
        class Ready(val memo: NewMemo) : Draft

        data object TooLong : Draft

        data object Failed : Draft
    }

    /** まとまりを1つ記録する。続けられないときは止まった理由を返す。 */
    private suspend fun process(chunk: UtteranceChunk, pass: Pass): OrganizeOutcome? = when {
        chunk.isBlank -> {
            memos.markOrganized(chunk.utterances.map { it.id }, clock.nowMillis())
            null
        }
        attempts.count(chunk.utterances.first().id) >= config.maxChunkAttempts -> {
            pass.suspects += chunk
            null
        }
        else -> attempt(chunk, pass)
    }

    private suspend fun attempt(chunk: UtteranceChunk, pass: Pass): OrganizeOutcome? {
        val key = chunk.utterances.first().id
        val attempted = attempts.count(key)
        // クラッシュでは後の処理が動かないので、始める前に数える
        attempts.set(key, attempted + 1)
        return when (val draft = draftOf(chunk, pass.labels, key, attempted)) {
            is Draft.Ready -> {
                record(draft.memo, key, pass)
                resolveSuspects(pass)
                null
            }
            Draft.TooLong -> {
                attempts.clear(key)
                processTooLong(chunk, pass)
            }
            Draft.Failed -> if (attempted + 1 < config.maxChunkAttempts) {
                OrganizeOutcome.LLM_FAILED
            } else {
                pass.suspects += chunk
                null
            }
        }
    }

    private suspend fun record(memo: NewMemo, key: Long, pass: Pass) {
        if (memos.recordMemo(memo) != null) {
            pass.memoCount++
            pass.todoCount += memo.todos.size
        }
        attempts.clear(key)
    }

    /** 同じ実行基盤が次のまとまりを処理できたので、飛ばしたまとまりの問題はそのまとまりにあった。 */
    private suspend fun resolveSuspects(pass: Pass) {
        pass.suspects.forEach { recordUnreadable(it, pass) }
        pass.suspects.clear()
    }

    /** 半分に分けて入力を短くする。発話が1つなら切ると文が壊れるので、読めなかったものとして記録する。 */
    private suspend fun processTooLong(chunk: UtteranceChunk, pass: Pass): OrganizeOutcome? {
        if (chunk.utterances.size < 2) {
            recordUnreadable(chunk, pass)
            return null
        }
        val half = chunk.utterances.size / 2
        return process(UtteranceChunk(chunk.utterances.take(half)), pass)
            ?: process(UtteranceChunk(chunk.utterances.drop(half)), pass)
    }

    /** 発話は残るので、要約のないメモにして先へ進む。根拠の発話はメモからたどれる。 */
    private suspend fun recordUnreadable(chunk: UtteranceChunk, pass: Pass) {
        val memo = NewMemo(
            startedAtMillis = chunk.startedAtMillis,
            endedAtMillis = chunk.endedAtMillis,
            title = null,
            summary = null,
            todoExtraction = TodoExtraction.INCOMPLETE,
            modelVersion = llm.modelVersion,
            createdAtMillis = clock.nowMillis(),
            utteranceIds = chunk.utterances.map { it.id },
            todos = emptyList(),
        )
        if (memos.recordMemo(memo) != null) {
            pass.memoCount++
            pass.unreadableCount++
        }
        attempts.clear(chunk.utterances.first().id)
    }

    // 実行基盤の失敗は型で絞れない。例外の中身は文字起こしを含みうるので、外へ渡さない
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun draftOf(chunk: UtteranceChunk, labels: SpeakerLabels, key: Long, attempted: Int): Draft = try {
        Draft.Ready(draft(chunk, labels))
    } catch (e: LlmInputTooLongException) {
        ensureNotCancelled(key, attempted)
        Draft.TooLong
    } catch (e: Exception) {
        ensureNotCancelled(key, attempted)
        Draft.Failed
    }

    /**
     * キャンセルかどうかは例外の型ではなく状態で判断する。アダプターの内側の withTimeout で止めないため。
     * キャンセルはまとまりのせいではないので、回数を戻す。
     */
    private suspend fun ensureNotCancelled(key: Long, attempted: Int) {
        if (!currentCoroutineContext().isActive) attempts.set(key, attempted)
        currentCoroutineContext().ensureActive()
    }

    private suspend fun draft(chunk: UtteranceChunk, labels: SpeakerLabels): NewMemo {
        val transcript = chunk.toTranscript(timeZone, labels)
        // 話者の名前は日付の手がかりにしない。「明日香」などを「明日」と読まないように、本文だけで確かめる
        val spokenText = chunk.toTranscript(timeZone)
        val spokenOn = CalendarDate.of(chunk.startedAtMillis, timeZone)
        val endedOn = CalendarDate.of(chunk.endedAtMillis, timeZone)
        val summary = summarize(spokenOn, transcript)
        val parsed = extractTodos(spokenOn, transcript)
        val title = summary?.let { titleOf(it) }
        return NewMemo(
            startedAtMillis = chunk.startedAtMillis,
            endedAtMillis = chunk.endedAtMillis,
            title = title,
            summary = summary,
            todoExtraction = if (parsed.isComplete) TodoExtraction.COMPLETE else TodoExtraction.INCOMPLETE,
            modelVersion = llm.modelVersion,
            createdAtMillis = clock.nowMillis(),
            utteranceIds = chunk.utterances.map { it.id },
            todos = parsed.items
                .distinctBy { item -> item.task.filterNot(Char::isWhitespace) }
                .map { item ->
                    item.toNewTodo(todoTitleOf(item.task), assessDue(item.due, spokenOn, endedOn, spokenText))
                },
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

    /** タイトルは要約から別に作る。使えない出力なら、要約の行で代わりにする。 */
    private suspend fun titleOf(summary: String): String {
        val generated = parseTitle(llm.generate(OrganizePrompts.memoTitle(summary)))
        val lines = summary.lines().map(::cleanTitle).filter { it.isNotEmpty() }
        val title = generated ?: lines.firstOrNull { !isDateOnly(it) } ?: lines.first()
        return title.take(MAX_TITLE_CHARS)
    }

    /** ToDo のタイトルは内容から別に作る。使えない出力なら、内容で代わりにする。 */
    private suspend fun todoTitleOf(task: String): String =
        (parseTitle(llm.generate(OrganizePrompts.todoTitle(task))) ?: task).take(MAX_TITLE_CHARS)

    private fun RawTodo.toNewTodo(title: String, due: AssessedDue) = NewTodo(
        task = task,
        title = title,
        kind = if (kind?.trim()?.lowercase() == "reminder") TodoKind.REMINDER else TodoKind.TODO,
        dueDate = due.date,
        status = if (due.isConfident) TodoStatus.OPEN else TodoStatus.CANDIDATE,
    )
}

/** 今整理できる量を、LLM を使わずに数える。[OrganizeJob.run] と同じ分け方にする。本文が空のまとまりは数えない。 */
suspend fun organizeBacklog(
    memos: MemoRepository,
    speakers: List<Speaker>,
    clock: Clock,
    timeZone: TimeZone,
    config: OrganizeConfig = OrganizeConfig(),
): OrganizeBacklog {
    val chunks = splitIntoChunks(memos.listUnorganizedUtterances(), config, timeZone, SpeakerLabels(speakers))
    val ready = chunks.withoutOngoing(clock.nowMillis(), config)
    return OrganizeBacklog(ready.count { !it.isBlank }, hasOngoing = ready.size < chunks.size)
}

/** プロセスの中でだけ数える。実行をまたいで数えなくてよい場面の既定値。 */
class InMemoryOrganizeAttempts : OrganizeAttempts {
    private val counts = HashMap<Long, Int>()

    override fun count(firstUtteranceId: Long): Int = counts[firstUtteranceId] ?: 0

    override fun set(firstUtteranceId: Long, count: Int) {
        counts[firstUtteranceId] = count
    }

    override fun clear(firstUtteranceId: Long) {
        counts.remove(firstUtteranceId)
    }
}
