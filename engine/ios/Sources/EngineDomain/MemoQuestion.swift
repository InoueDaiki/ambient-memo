import Foundation

/// 質問の答えの種類。実行基盤の失敗は例外で伝える。
public enum AnswerOutcome: String, Sendable, Hashable, CaseIterable {
    /// メモを根拠に答えた。
    case ANSWERED

    /// 関係するメモが見つからなかったか、メモから答えが分からなかった。
    case NOT_FOUND

    public var name: String { rawValue }
}

/// 質問の進み具合。モデルの読み込みは実行の組み立ての側で知らせる。
public enum AskPhase: String, Sendable, Hashable, CaseIterable {
    case SEARCHING
    case ANSWERING

    public var name: String { rawValue }
}

/// 質問の既定値。すべて仮で、実機と実際の質問で見直す。Swift の既定値は [default] にある。
public struct AskConfig: Sendable, Hashable {
    /// 検索の計画を LLM に頼む回数の上限。見つからなければ、前の語を伝えて別の語を頼む。
    public let maxSearchRounds: Int
    /// 答えの根拠として LLM に渡すメモの数の上限。
    public let maxSources: Int
    /// LLM に渡すメモの文字数の上限。文脈長 4096 トークンに、指示と答えの分を残すため。
    public let maxContextChars: Int
    /// 語ごとに読むメモの数の上限。
    public let candidatesPerTerm: Int
    /// メモごとに添える、語を含む発話の数の上限。要約から落ちた細部を拾うため。
    public let maxQuotes: Int
    /// 添える発話の文字数の上限。
    public let maxQuoteChars: Int

    public init(
        maxSearchRounds: Int = DEFAULT_MAX_SEARCH_ROUNDS,
        maxSources: Int = DEFAULT_MAX_SOURCES,
        maxContextChars: Int = DEFAULT_MAX_CONTEXT_CHARS,
        candidatesPerTerm: Int = DEFAULT_CANDIDATES_PER_TERM,
        maxQuotes: Int = DEFAULT_MAX_QUOTES,
        maxQuoteChars: Int = DEFAULT_MAX_QUOTE_CHARS
    ) throws {
        try require(maxSearchRounds > 0, "maxSearchRounds は正の値です")
        try require(maxSources > 0, "maxSources は正の値です")
        try require(maxContextChars > 0, "maxContextChars は正の値です")
        try require(candidatesPerTerm > 0, "candidatesPerTerm は正の値です")
        try require(maxQuotes >= 0, "maxQuotes は 0 以上です")
        try require(maxQuoteChars > 0, "maxQuoteChars は正の値です")
        self.maxSearchRounds = maxSearchRounds
        self.maxSources = maxSources
        self.maxContextChars = maxContextChars
        self.candidatesPerTerm = candidatesPerTerm
        self.maxQuotes = maxQuotes
        self.maxQuoteChars = maxQuoteChars
    }

    /// Kotlin の `AskConfig()`。
    public static let `default` = try! AskConfig()

    @usableFromInline static let DEFAULT_MAX_SEARCH_ROUNDS = 2
    @usableFromInline static let DEFAULT_MAX_SOURCES = 5
    @usableFromInline static let DEFAULT_MAX_CONTEXT_CHARS = 2_400
    @usableFromInline static let DEFAULT_CANDIDATES_PER_TERM = 20
    @usableFromInline static let DEFAULT_MAX_QUOTES = 3
    @usableFromInline static let DEFAULT_MAX_QUOTE_CHARS = 80
}

/**
 * 質問の答え。[text] は [AnswerOutcome.ANSWERED] のときだけある。
 * [sources] は答えが挙げたメモで、挙げた順。挙げなかったときは LLM に渡したメモをすべて入れる。
 */
public struct MemoAnswer: Sendable, Hashable {
    public let outcome: AnswerOutcome
    public let text: String?
    public let sources: [Memo]
    /// 検索の計画を LLM に頼んだ回数。
    public let searchRounds: Int

    public init(_ outcome: AnswerOutcome, _ text: String?, _ sources: [Memo], _ searchRounds: Int) {
        self.outcome = outcome
        self.text = text
        self.sources = sources
        self.searchRounds = searchRounds
    }
}

/**
 * 過去のメモを探して質問に答える。
 *
 * 1. LLM に検索の語と期間を考えさせる
 * 2. 語ごとにメモのタイトル、要約、根拠の発話を探し、多くの語に当たったメモを先にする
 * 3. 見つからなければ、使った語を伝えて別の語を考えさせる。それでもなく、期間があれば、期間のメモを新しい順に使う
 * 4. 見つかったメモの要約と、語を含む発話を番号付きで渡し、番号を挙げて答えさせる
 *
 * 検索は DB の部分一致だけで、ベクトルは使わない。日本語の言い換えは LLM の語に任せる。
 * LLM の例外と取り消しはそのまま投げる。質問とメモの中身は記録に残さない。
 */
public final class MemoQuestionAnswerer: Sendable {
    private let memos: any MemoRepository
    private let speakers: any SpeakerRepository
    private let llm: any Llm
    private let clock: any Clock
    private let timeZone: TimeZone
    private let config: AskConfig

    public init(
        memos: any MemoRepository,
        speakers: any SpeakerRepository,
        llm: any Llm,
        clock: any Clock,
        timeZone: TimeZone,
        config: AskConfig = .default
    ) {
        self.memos = memos
        self.speakers = speakers
        self.llm = llm
        self.clock = clock
        self.timeZone = timeZone
        self.config = config
    }

    /// 空の質問は [IllegalArgumentException] を投げる。
    public func answer(_ question: String, onPhase: (AskPhase) -> Void = { _ in }) async throws -> MemoAnswer {
        try require(!question.kotlinIsBlank, "質問が空です")
        let today = try CalendarDate.of(clock.nowMillis(), timeZone)
        onPhase(.SEARCHING)
        let search = try await search(question, today)
        if search.memos.isEmpty { return MemoAnswer(.NOT_FOUND, nil, [], search.rounds) }
        onPhase(.ANSWERING)
        let labels = SpeakerLabels(try await speakers.listSpeakers())
        let context = try await contextOf(search.memos, search.terms, labels)
        let output = try await llm.generate(AskPrompts.answer(question, today, context.text))
        guard let parsed = parseAnswer(output, context.memos.count) else {
            return MemoAnswer(.NOT_FOUND, nil, [], search.rounds)
        }
        let cited = parsed.citations.map { context.memos[$0 - 1] }
        return MemoAnswer(.ANSWERED, parsed.text, cited.isEmpty ? context.memos : cited, search.rounds)
    }

    private struct Search {
        let memos: [Memo]
        let terms: [String]
        let rounds: Int
    }

    private func search(_ question: String, _ today: CalendarDate) async throws -> Search {
        var tried: [String] = []
        var lastRange: SearchPlan?
        for round in 1...config.maxSearchRounds {
            // 読めない出力は語も期間もない計画として扱い、次の回に任せる
            let output = try await llm.generate(try AskPrompts.plan(question, today, tried))
            let plan = parseSearchPlan(output) ?? SearchPlan([], nil, nil)
            if plan.from != nil { lastRange = plan }
            let found = try await find(plan.terms, plan)
            if !found.isEmpty { return Search(memos: found, terms: plan.terms, rounds: round) }
            // 期間だけの計画は、語を変えても同じ結果になる
            if plan.terms.isEmpty, plan.from != nil { return Search(memos: [], terms: [], rounds: round) }
            // Kotlin の `it in tried` と同じく、UTF-16 の単位で比べる
            let triedUnits = Set(tried.map { Array($0.utf16) })
            tried += plan.terms.filter { !triedUnits.contains(Array($0.utf16)) }
        }
        let inRange = if let lastRange { try await find([], lastRange) } else { [Memo]() }
        return Search(memos: inRange, terms: [], rounds: config.maxSearchRounds)
    }

    /// 語がなければ、期間のメモを新しい順に返す。語も期間もなければ探さない。
    private func find(_ terms: [String], _ range: SearchPlan) async throws -> [Memo] {
        let from = range.from?.startOfDayMillis(timeZone) ?? Int64.min
        let to = try range.to.map { try $0.plusDays(1).startOfDayMillis(timeZone) } ?? Int64.max
        if terms.isEmpty {
            if range.from == nil { return [] }
            return try await memos.searchMemos(term: "", fromMillis: from, toMillis: to, limit: config.maxSources)
        }
        // 先に挙げた語ほど大事なので、重くする。Kotlin の LinkedHashMap と同じく、最初に当たった順を残す
        var order: [Int64] = []
        var scores: [Int64: (memo: Memo, score: Int)] = [:]
        for (index, term) in terms.enumerated() {
            let found = try await memos.searchMemos(
                term: term, fromMillis: from, toMillis: to, limit: config.candidatesPerTerm
            )
            for memo in found {
                if scores[memo.id] == nil { order.append(memo.id) }
                scores[memo.id] = (memo, (scores[memo.id]?.score ?? 0) + terms.count - index)
            }
        }
        return order.map { scores[$0]! }
            .kotlinSorted { first, second in
                if first.score != second.score { return first.score > second.score }
                if first.memo.startedAtMillis != second.memo.startedAtMillis {
                    return first.memo.startedAtMillis > second.memo.startedAtMillis
                }
                return first.memo.id > second.memo.id
            }
            .prefix(config.maxSources)
            .map(\.memo)
    }

    private struct Context {
        let memos: [Memo]
        let text: String
    }

    /// 上限を超えるメモは渡さない。最初のメモだけは、上限で切って渡す。文字数は UTF-16 の単位で数える。
    private func contextOf(_ found: [Memo], _ terms: [String], _ labels: SpeakerLabels) async throws -> Context {
        var blocks: [String] = []
        var used = 0
        for memo in found {
            let block = try await blockOf(blocks.count + 1, memo, terms, labels)
            if !blocks.isEmpty, used + block.kotlinLength > config.maxContextChars { break }
            blocks.append(block.kotlinTake(config.maxContextChars))
            used += block.kotlinLength
        }
        return Context(memos: Array(found.prefix(blocks.count)), text: blocks.joined(separator: "\n\n"))
    }

    /// 要約のないメモは、最初の発話を代わりに添える。
    private func blockOf(_ number: Int, _ memo: Memo, _ terms: [String], _ labels: SpeakerLabels) async throws -> String {
        let date = try CalendarDate.of(memo.startedAtMillis, timeZone)
        let header = "[\(number)] \(AskPrompts.label(date)) \(clockLabel(memo.startedAtMillis, timeZone))" +
            (memo.title.map { " \($0)" } ?? "")
        let utterances = try await memos.listMemoUtterances(memoId: memo.id).filter { !$0.text.kotlinIsBlank }
        let keys = terms.map(foldAscii)
        let matched = utterances.filter { utterance in
            let folded = foldAscii(utterance.text)
            return keys.contains { folded.kotlinContains($0) }
        }
        let quoted = (matched.isEmpty ? (memo.summary == nil ? utterances : []) : matched)
            .prefix(config.maxQuotes)
            .map { "発話: " + $0.toTranscriptLine(timeZone, labels).kotlinTake(config.maxQuoteChars) }
        return ([header] + [memo.summary?.kotlinTrim()].compactMap { $0 } + quoted).joined(separator: "\n")
    }
}

/// 英字だけを小文字にする。SQLite の lower と同じく、ほかの文字は変えない。
public func foldAscii(_ text: String) -> String {
    stringFromUTF16(text.utf16.map { $0 >= 0x41 && $0 <= 0x5A ? $0 + 0x20 : $0 })
}
