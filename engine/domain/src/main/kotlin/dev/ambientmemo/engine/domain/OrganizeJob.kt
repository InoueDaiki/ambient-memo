package dev.ambientmemo.engine.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.util.TimeZone

private const val MAX_TITLE_CHARS = 30

/**
 * 未処理の発話をメモに整理する。
 *
 * 発話をまとまりに分け、まとまりを話題に分ける。
 * 話題ごとに、メモと発話の処理済みの印を1つのトランザクションで記録する。
 * 途中で止まっても、記録していないまとまりは未処理のまま残り、次回の実行が続きから処理する。
 *
 * [timeZone] は発話の日付を決める。「今日」は実行時ではなく、話した日にする。
 * 夜の充電中に整理するので、実行が日付をまたぐと要約の「明日」がずれるため。
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
        val sorted = memos.listUnorganizedUtterances()
        val nowMillis = clock.nowMillis()
        val readyFrom = { from: Int ->
            splitIntoChunks(sorted.subList(from, sorted.size), config, timeZone, labels)
                .withoutOngoing(nowMillis, config)
        }
        var chunks = readyFrom(0)
        val total = chunks.size
        val pass = Pass(labels)
        onProgress(OrganizeProgress(0, total))
        var completed = 0
        var stoppedBy: OrganizeOutcome? = null
        while (stoppedBy == null && chunks.isNotEmpty()) {
            val step = if (shouldContinue()) processChunk(chunks.first(), pass) else Step(OrganizeOutcome.STOPPED)
            stoppedBy = step.stoppedBy
            if (stoppedBy == null) {
                // 回した話題からまとまりを作り直す。作り直すと数が変わるので、進み具合は残りの数から出す
                chunks = step.carried?.let { carried -> readyFrom(sorted.indexOfFirst { it.id == carried.id }) }
                    ?: chunks.drop(1)
                completed = (total - chunks.size).coerceIn(completed, total)
                onProgress(OrganizeProgress(completed, total))
            }
        }
        // 飛ばしたまとまりの後で LLM が動いたことを確かめられなかった。実行基盤の不調かもしれないので記録しない
        val finished = if (pass.suspects.isEmpty()) OrganizeOutcome.COMPLETED else OrganizeOutcome.LLM_FAILED
        return OrganizeResult(stoppedBy ?: finished, pass.memoCount, pass.unreadableCount)
    }

    /** まとまりを1つ処理した結果。[carried] は、次のまとまりの頭に回した話題の最初の発話。 */
    private class Step(
        val stoppedBy: OrganizeOutcome?,
        val carried: Utterance? = null,
    )

    /**
     * まとまりを話題に分け、話題ごとにメモにする。
     * 文字数の上限で切ったまとまりの最後の話題は記録せず、次のまとまりの頭に回す。続きと一緒に1件のメモにするため。
     * 話題が1つだけなら回さない。回しても同じところで切れて進まないため。
     */
    private suspend fun processChunk(chunk: UtteranceChunk, pass: Pass): Step {
        val topics = topicsOf(chunk, pass.labels)
        val carried = topics.last().takeIf { chunk.continues && topics.size > 1 }
        for (topic in if (carried == null) topics else topics.dropLast(1)) {
            process(topic, pass)?.let { return Step(it) }
        }
        return Step(null, carried?.utterances?.first())
    }

    /**
     * 話題の切れ目を LLM に頼む。読めないか失敗したら、まとまり全体を1つの話題にする。
     * 実行基盤の失敗は、続く要約の依頼でも起きて、そこで数える。
     */
    // 実行基盤の失敗は型で絞れない。例外の中身は文字起こしを含みうるので、外へ渡さない
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun topicsOf(chunk: UtteranceChunk, labels: SpeakerLabels): List<UtteranceChunk> {
        val lines = chunk.transcriptLines(timeZone, labels)
        val key = chunk.utterances.first().id
        val attempted = attempts.count(key)
        if (lines.size < 2 || attempted >= config.maxChunkAttempts) return listOf(chunk)
        // 要約と同じく、クラッシュで後の処理が動かなくても回数が残るように、始める前に数える
        attempts.set(key, attempted + 1)
        val starts = try {
            parseTopicStarts(llm.generate(OrganizePrompts.topics(lines)), lines.size)
        } catch (e: Exception) {
            ensureNotCancelled(key, attempted)
            null
        }
        if (attempted == 0) attempts.clear(key) else attempts.set(key, attempted)
        return starts?.let(chunk::splitAtLines) ?: listOf(chunk)
    }

    private class Pass(
        val labels: SpeakerLabels,
        var memoCount: Int = 0,
        var unreadableCount: Int = 0,
        val suspects: MutableList<UtteranceChunk> = mutableListOf(),
    )

    private sealed interface Draft {
        class Ready(val memo: NewMemo) : Draft

        data object TooLong : Draft

        data object Failed : Draft
    }

    /** 話題を1つ記録する。続けられないときは止まった理由を返す。 */
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
        if (memos.recordMemo(memo) != null) pass.memoCount++
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
            modelVersion = llm.modelVersion,
            createdAtMillis = clock.nowMillis(),
            utteranceIds = chunk.utterances.map { it.id },
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
        val summary = summarize(CalendarDate.of(chunk.startedAtMillis, timeZone), transcript)
        return NewMemo(
            startedAtMillis = chunk.startedAtMillis,
            endedAtMillis = chunk.endedAtMillis,
            title = summary?.let { titleOf(it) },
            summary = summary,
            modelVersion = llm.modelVersion,
            createdAtMillis = clock.nowMillis(),
            utteranceIds = chunk.utterances.map { it.id },
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

    private fun LlmRequest.forAttempt(attempt: Int, retryNote: String) =
        if (attempt == 0) this else copy(user = user + retryNote)

    /** タイトルは要約から別に作る。使えない出力なら、要約の行で代わりにする。 */
    private suspend fun titleOf(summary: String): String {
        val generated = parseTitle(llm.generate(OrganizePrompts.memoTitle(summary)))
        val lines = summary.lines().map(::cleanTitle).filter { it.isNotEmpty() }
        val title = generated ?: lines.firstOrNull { !isDateOnly(it) } ?: lines.first()
        return title.take(MAX_TITLE_CHARS)
    }
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
