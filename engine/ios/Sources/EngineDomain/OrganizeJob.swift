import Foundation

private let MAX_TITLE_CHARS = 30

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
public final class OrganizeJob: Sendable {
    private let memos: any MemoRepository
    private let speakers: any SpeakerRepository
    private let llm: any Llm
    private let clock: any Clock
    private let timeZone: TimeZone
    private let config: OrganizeConfig
    private let attempts: any OrganizeAttempts

    public init(
        memos: any MemoRepository,
        speakers: any SpeakerRepository,
        llm: any Llm,
        clock: any Clock,
        timeZone: TimeZone,
        config: OrganizeConfig = .default,
        attempts: any OrganizeAttempts = InMemoryOrganizeAttempts()
    ) {
        self.memos = memos
        self.speakers = speakers
        self.llm = llm
        self.clock = clock
        self.timeZone = timeZone
        self.config = config
        self.attempts = attempts
    }

    /**
     * 処理できるまとまりを古い順に処理する。[shouldContinue] はまとまりの間で確かめる。
     * 充電が終わったらここで止める。すぐに止めたいときはタスクを取り消す。
     * [onProgress] は対象のまとまりが決まったときと、まとまりを1つ終えるたびに呼ぶ。
     *
     * LLM の例外は [OrganizeOutcome.LLM_FAILED] で返す。DB の例外と取り消しはそのまま投げる。
     * 特定のまとまりで止まり続けないように、読めないまとまりは要約のないメモとして記録して先へ進む。
     */
    public func run(
        shouldContinue: () -> Bool = { true },
        onProgress: (OrganizeProgress) -> Void = { _ in }
    ) async throws -> OrganizeResult {
        let labels = SpeakerLabels(try await speakers.listSpeakers())
        let chunks = splitIntoChunks(try await memos.listUnorganizedUtterances(), config, timeZone, labels)
            .withoutOngoing(clock.nowMillis(), config)
        let pass = Pass(labels)
        onProgress(OrganizeProgress(0, chunks.count))
        var stoppedBy: OrganizeOutcome?
        for (index, chunk) in chunks.enumerated() {
            if shouldContinue() {
                let stopped = try await process(chunk, pass)
                if stopped == nil { onProgress(OrganizeProgress(index + 1, chunks.count)) }
                stoppedBy = stopped
            } else {
                stoppedBy = .STOPPED
            }
            if stoppedBy != nil { break }
        }
        // 飛ばしたまとまりの後で LLM が動いたことを確かめられなかった。実行基盤の不調かもしれないので記録しない
        let finished: OrganizeOutcome = pass.suspects.isEmpty ? .COMPLETED : .LLM_FAILED
        return OrganizeResult(stoppedBy ?? finished, pass.memoCount, pass.todoCount, pass.unreadableCount)
    }

    /// 1 回の実行の数え。実行のタスクの中だけで使う。
    private final class Pass {
        let labels: SpeakerLabels
        var memoCount = 0
        var todoCount = 0
        var unreadableCount = 0
        var suspects: [UtteranceChunk] = []

        init(_ labels: SpeakerLabels) {
            self.labels = labels
        }
    }

    private enum Draft {
        case Ready(NewMemo)

        case TooLong

        case Failed
    }

    /// まとまりを1つ記録する。続けられないときは止まった理由を返す。
    private func process(_ chunk: UtteranceChunk, _ pass: Pass) async throws -> OrganizeOutcome? {
        if chunk.isBlank {
            try await memos.markOrganized(chunk.utterances.map(\.id), organizedAtMillis: clock.nowMillis())
            return nil
        }
        if try attempts.count(firstUtteranceId: chunk.utterances[0].id) >= config.maxChunkAttempts {
            pass.suspects.append(chunk)
            return nil
        }
        return try await attempt(chunk, pass)
    }

    private func attempt(_ chunk: UtteranceChunk, _ pass: Pass) async throws -> OrganizeOutcome? {
        let key = chunk.utterances[0].id
        let attempted = try attempts.count(firstUtteranceId: key)
        // クラッシュでは後の処理が動かないので、始める前に数える
        try attempts.set(firstUtteranceId: key, count: attempted + 1)
        switch try await draftOf(chunk, pass.labels, key, attempted) {
        case let .Ready(memo):
            try await record(memo, key, pass)
            try await resolveSuspects(pass)
            return nil
        case .TooLong:
            try attempts.clear(firstUtteranceId: key)
            return try await processTooLong(chunk, pass)
        case .Failed:
            if attempted + 1 < config.maxChunkAttempts { return .LLM_FAILED }
            pass.suspects.append(chunk)
            return nil
        }
    }

    private func record(_ memo: NewMemo, _ key: Int64, _ pass: Pass) async throws {
        if try await memos.recordMemo(memo) != nil {
            pass.memoCount += 1
            pass.todoCount += memo.todos.count
        }
        try attempts.clear(firstUtteranceId: key)
    }

    /// 同じ実行基盤が次のまとまりを処理できたので、飛ばしたまとまりの問題はそのまとまりにあった。
    private func resolveSuspects(_ pass: Pass) async throws {
        for suspect in pass.suspects { try await recordUnreadable(suspect, pass) }
        pass.suspects.removeAll()
    }

    /// 半分に分けて入力を短くする。発話が1つなら切ると文が壊れるので、読めなかったものとして記録する。
    private func processTooLong(_ chunk: UtteranceChunk, _ pass: Pass) async throws -> OrganizeOutcome? {
        if chunk.utterances.count < 2 {
            try await recordUnreadable(chunk, pass)
            return nil
        }
        let half = chunk.utterances.count / 2
        if let stopped = try await process(UtteranceChunk(Array(chunk.utterances.prefix(half))), pass) { return stopped }
        return try await process(UtteranceChunk(Array(chunk.utterances.dropFirst(half))), pass)
    }

    /// 発話は残るので、要約のないメモにして先へ進む。根拠の発話はメモからたどれる。
    private func recordUnreadable(_ chunk: UtteranceChunk, _ pass: Pass) async throws {
        let memo = try NewMemo(
            startedAtMillis: chunk.startedAtMillis,
            endedAtMillis: chunk.endedAtMillis,
            title: nil,
            summary: nil,
            todoExtraction: .INCOMPLETE,
            modelVersion: llm.modelVersion,
            createdAtMillis: clock.nowMillis(),
            utteranceIds: chunk.utterances.map(\.id),
            todos: []
        )
        if try await memos.recordMemo(memo) != nil {
            pass.memoCount += 1
            pass.unreadableCount += 1
        }
        try attempts.clear(firstUtteranceId: chunk.utterances[0].id)
    }

    // 実行基盤の失敗は型で絞れない。例外の中身は文字起こしを含みうるので、外へ渡さない
    private func draftOf(_ chunk: UtteranceChunk, _ labels: SpeakerLabels, _ key: Int64, _ attempted: Int) async throws -> Draft {
        do {
            return .Ready(try await draft(chunk, labels))
        } catch is LlmInputTooLongException {
            try ensureNotCancelled(key, attempted)
            return .TooLong
        } catch {
            try ensureNotCancelled(key, attempted)
            return .Failed
        }
    }

    /**
     * 取り消しかどうかは例外の型ではなく状態で判断する。アダプターの内側のタイムアウトで止めないため。
     * 取り消しはまとまりのせいではないので、回数を戻す。
     */
    private func ensureNotCancelled(_ key: Int64, _ attempted: Int) throws {
        if Task.isCancelled { try attempts.set(firstUtteranceId: key, count: attempted) }
        try Task.checkCancellation()
    }

    private func draft(_ chunk: UtteranceChunk, _ labels: SpeakerLabels) async throws -> NewMemo {
        let transcript = chunk.toTranscript(timeZone, labels)
        // 話者の名前は日付の手がかりにしない。「明日香」などを「明日」と読まないように、本文だけで確かめる
        let spokenText = chunk.toTranscript(timeZone)
        let spokenOn = try CalendarDate.of(chunk.startedAtMillis, timeZone)
        let endedOn = try CalendarDate.of(chunk.endedAtMillis, timeZone)
        let summary = try await summarize(spokenOn, transcript)
        let parsed = try await extractTodos(spokenOn, transcript)
        let title = try await summary.asyncMap { try await titleOf($0) }
        var seen = Set<String>()
        var todos: [NewTodo] = []
        // Kotlin の distinctBy と同じく、空白を除いた内容が同じものは最初の 1 件だけを残す
        for item in parsed.items where seen.insert(item.task.kotlinWithoutWhitespace()).inserted {
            let todoTitle = try await todoTitleOf(item.task)
            todos.append(try toNewTodo(item, todoTitle, try assessDue(item.due, spokenOn, endedOn, spokenText)))
        }
        return try NewMemo(
            startedAtMillis: chunk.startedAtMillis,
            endedAtMillis: chunk.endedAtMillis,
            title: title,
            summary: summary,
            todoExtraction: parsed.isComplete ? .COMPLETE : .INCOMPLETE,
            modelVersion: llm.modelVersion,
            createdAtMillis: clock.nowMillis(),
            utteranceIds: chunk.utterances.map(\.id),
            todos: todos
        )
    }

    private func summarize(_ date: CalendarDate, _ transcript: String) async throws -> String? {
        let request = OrganizePrompts.summary(date, transcript)
        for attempt in 0...config.maxRetries {
            let output = try await llm.generate(forAttempt(request, attempt, OrganizePrompts.SUMMARY_RETRY_NOTE))
            if let summary = parseSummary(output) { return summary }
        }
        return nil
    }

    /// 温度 0 では同じ入力に同じ出力が返るので、再試行では指示を足す。読めなければ空の不完全な結果を返す。
    private func extractTodos(_ date: CalendarDate, _ transcript: String) async throws -> ParsedTodos {
        let request = try OrganizePrompts.todo(date, transcript)
        var best: ParsedTodos?
        for attempt in 0...config.maxRetries {
            let parsed = parseTodos(try await llm.generate(forAttempt(request, attempt, OrganizePrompts.TODO_RETRY_NOTE)))
            if parsed?.isComplete == true { return parsed! }
            best = parsed ?? best
        }
        return best ?? ParsedTodos([], isComplete: false)
    }

    private func forAttempt(_ request: LlmRequest, _ attempt: Int, _ retryNote: String) -> LlmRequest {
        if attempt == 0 { return request }
        var retried = request
        retried.user = request.user + retryNote
        return retried
    }

    /// タイトルは要約から別に作る。使えない出力なら、要約の行で代わりにする。
    private func titleOf(_ summary: String) async throws -> String {
        let generated = parseTitle(try await llm.generate(OrganizePrompts.memoTitle(summary)))
        let lines = summary.kotlinLines().map(cleanTitle).filter { !$0.isEmpty }
        // Kotlin の lines.first() と同じく、使える行がなければ NoSuchElementException を投げる
        guard let title = generated ?? lines.first(where: { !isDateOnly($0) }) ?? lines.first else {
            throw NoSuchElementException("List is empty.")
        }
        return title.kotlinTake(MAX_TITLE_CHARS)
    }

    /// ToDo のタイトルは内容から別に作る。使えない出力なら、内容で代わりにする。
    private func todoTitleOf(_ task: String) async throws -> String {
        (parseTitle(try await llm.generate(OrganizePrompts.todoTitle(task))) ?? task).kotlinTake(MAX_TITLE_CHARS)
    }

    private func toNewTodo(_ item: RawTodo, _ title: String, _ due: AssessedDue) throws -> NewTodo {
        try NewTodo(
            task: item.task,
            kind: item.kind?.kotlinTrim().kotlinLowercase() == "reminder" ? .REMINDER : .TODO,
            dueDate: due.date,
            status: due.isConfident ? .OPEN : .CANDIDATE,
            title: title
        )
    }
}

extension Optional {
    fileprivate func asyncMap<Mapped>(_ transform: (Wrapped) async throws -> Mapped) async rethrows -> Mapped? {
        guard let value = self else { return nil }
        return try await transform(value)
    }
}

/// 今整理できる量を、LLM を使わずに数える。[OrganizeJob.run] と同じ分け方にする。本文が空のまとまりは数えない。
public func organizeBacklog(
    memos: any MemoRepository,
    speakers: [Speaker],
    clock: any Clock,
    timeZone: TimeZone,
    config: OrganizeConfig = .default
) async throws -> OrganizeBacklog {
    let chunks = splitIntoChunks(try await memos.listUnorganizedUtterances(), config, timeZone, SpeakerLabels(speakers))
    let ready = chunks.withoutOngoing(clock.nowMillis(), config)
    return OrganizeBacklog(readyChunks: ready.count { !$0.isBlank }, hasOngoing: ready.count < chunks.count)
}

/// プロセスの中でだけ数える。実行をまたいで数えなくてよい場面の既定値。
public final class InMemoryOrganizeAttempts: OrganizeAttempts {
    private let counts = LockedState<[Int64: Int]>([:])

    public init() {}

    public func count(firstUtteranceId: Int64) -> Int {
        counts.withLock { $0[firstUtteranceId] ?? 0 }
    }

    public func set(firstUtteranceId: Int64, count: Int) {
        counts.withLock { $0[firstUtteranceId] = count }
    }

    public func clear(firstUtteranceId: Int64) {
        counts.withLock { $0[firstUtteranceId] = nil }
    }
}
