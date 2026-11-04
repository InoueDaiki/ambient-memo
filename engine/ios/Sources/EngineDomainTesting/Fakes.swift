import EngineDomain
import Foundation

// Swift にしたことで変わった点:
// - Kotlin の AtomicLong、CopyOnWriteArrayList、Mutex は、NSLock で守る状態か [AsyncMutex] にした。
// - 受け取ったものを残す `received` は、その時点の写しを返す。

public let TEST_SAMPLE_RATE_HZ = 16_000
public let TEST_FRAME_SAMPLES = 160
private let MILLIS_PER_SECOND: Int64 = 1_000

/// 値をロックで守る。EngineDomain の同じものは internal なので、ここにも置く。
final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) {
        self.value = value
    }

    func withLock<Result>(_ body: (inout Value) throws -> Result) rethrows -> Result {
        lock.lock()
        defer { lock.unlock() }
        return try body(&value)
    }
}

/// Kotlin の `sortedWith`。同じ順位の要素は元の順を保つ(安定)。
extension Sequence {
    func stableSorted(by areInIncreasingOrder: (Element, Element) -> Bool) -> [Element] {
        enumerated()
            .sorted { first, second in
                if areInIncreasingOrder(first.element, second.element) { return true }
                if areInIncreasingOrder(second.element, first.element) { return false }
                return first.offset < second.offset
            }
            .map(\.element)
    }
}

public final class FakeClock: Clock {
    private let now: Locked<Int64>

    public init(_ startMillis: Int64 = 0) {
        now = Locked(startMillis)
    }

    public func nowMillis() -> Int64 {
        now.withLock { $0 }
    }

    public func advanceBy(_ millis: Int64) {
        now.withLock { $0 += millis }
    }
}

/**
 * 渡したサンプルを流す。Kotlin は配列を1つ再利用するが、Swift の配列は値なので毎回作る。
 * フレームの開始時刻を変えたいときは [frameStartMillis] で指定する。
 * 受け取る側が次を求めるまで次のフレームを作らない。
 */
public final class FakeAudioSource: AudioSource {
    private let samples: [Int16]
    public let sampleRateHz: Int
    private let frameSamples: Int
    private let startMillis: Int64
    private let frameStartMillis: @Sendable (_ position: Int, _ defaultMillis: Int64) -> Int64

    public init(
        _ samples: [Int16],
        sampleRateHz: Int = TEST_SAMPLE_RATE_HZ,
        frameSamples: Int = TEST_FRAME_SAMPLES,
        startMillis: Int64 = 0,
        frameStartMillis: @escaping @Sendable (_ position: Int, _ defaultMillis: Int64) -> Int64 = { _, defaultMillis in
            defaultMillis
        }
    ) {
        self.samples = samples
        self.sampleRateHz = sampleRateHz
        self.frameSamples = frameSamples
        self.startMillis = startMillis
        self.frameStartMillis = frameStartMillis
    }

    public func frames() -> AsyncThrowingStream<AudioFrame, any Error> {
        let position = Locked(0)
        return AsyncThrowingStream(unfolding: { [self] in
            position.withLock { position -> AudioFrame? in
                guard position < samples.count else { return nil }
                let count = min(frameSamples, samples.count - position)
                let frame = Array(samples[position..<position + count])
                let defaultMillis = startMillis + Int64(position) * MILLIS_PER_SECOND / Int64(sampleRateHz)
                let startedAt = frameStartMillis(position, defaultMillis)
                position += count
                return AudioFrame(startedAtMillis: startedAt, samples: frame)
            }
        })
    }
}

/// 指定した時間帯だけ発話と判定する。フレームの中央の時刻で決める。
public final class ScriptedVoiceActivityDetector: VoiceActivityDetector {
    private let speech: [ClosedRange<Int64>]
    private let sampleRateHz: Int
    private let resets = Locked(0)

    public init(_ speech: [ClosedRange<Int64>], sampleRateHz: Int = TEST_SAMPLE_RATE_HZ) {
        self.speech = speech
        self.sampleRateHz = sampleRateHz
    }

    public var resetCount: Int { resets.withLock { $0 } }

    public func isSpeech(_ frame: AudioFrame) -> Bool {
        let middleMillis = frame.startedAtMillis + Int64(frame.samples.count) * MILLIS_PER_SECOND / Int64(sampleRateHz) / 2
        return speech.contains { $0.contains(middleMillis) }
    }

    public func reset() {
        resets.withLock { $0 += 1 }
    }
}

/**
 * [respond] の結果を返す。受け取った発話は解放の確認用に [received] に残す。
 * トークンは [tokens] で指定する。既定は本文の文字を区間に均等に並べる。
 */
public final class FakeTranscriber: Transcriber {
    private let tokens: @Sendable (_ text: String, _ durationMillis: Int64) -> [TranscriptToken]
    private let respond: @Sendable (_ index: Int, _ segment: SpeechSegment) async throws -> Transcription
    private let receivedSegments = Locked<[SpeechSegment]>([])

    public init(
        tokens: @escaping @Sendable (_ text: String, _ durationMillis: Int64) -> [TranscriptToken] = evenTokens,
        respond: @escaping @Sendable (_ index: Int, _ segment: SpeechSegment) async throws -> Transcription = { index, _ in
            try Transcription("発話\(index + 1)")
        }
    ) {
        self.tokens = tokens
        self.respond = respond
    }

    public var received: [SpeechSegment] { receivedSegments.withLock { $0 } }

    public func transcribe(_ segment: SpeechSegment) async throws -> TimedTranscription {
        let index = receivedSegments.withLock { received in
            received.append(segment)
            return received.count - 1
        }
        let transcription = try await respond(index, segment)
        let durationMillis = segment.endedAtMillis - segment.startedAtMillis
        return TimedTranscription(transcription, tokens(transcription.text, durationMillis))
    }
}

/**
 * 本文の文字を区間に均等に並べる。Kotlin と同じく UTF-16 の 1 単位を 1 トークンとして時刻を割り振る。
 * Swift の String はサロゲートの片方を持てないので、対の文字は前のトークンに入れ、後ろは空のトークンにする。
 * つなげた本文と、文字のあるトークンの時刻は Kotlin と同じになる。
 */
@Sendable
public func evenTokens(_ text: String, _ durationMillis: Int64) -> [TranscriptToken] {
    let length = Int64(text.utf16.count)
    var tokens: [TranscriptToken] = []
    var index: Int64 = 0
    for scalar in text.unicodeScalars {
        tokens.append(TranscriptToken(String(scalar), index * durationMillis / length))
        index += 1
        if scalar.utf16.count == 2 {
            tokens.append(TranscriptToken("", index * durationMillis / length))
            index += 1
        }
    }
    return tokens
}

/// [respond] の特徴量を返す。nil は短くて作れない音声。受け取った発話は解放の確認用に [received] に残す。
public final class FakeSpeakerEmbedder: SpeakerEmbedder {
    public let modelVersion: String
    private let respond: @Sendable (_ index: Int, _ segment: SpeechSegment) async throws -> [Float]?
    private let receivedSegments = Locked<[SpeechSegment]>([])

    public init(
        modelVersion: String = "fake-1",
        respond: @escaping @Sendable (_ index: Int, _ segment: SpeechSegment) async throws -> [Float]? = { _, _ in
            [1, 0, 0]
        }
    ) {
        self.modelVersion = modelVersion
        self.respond = respond
    }

    public var received: [SpeechSegment] { receivedSegments.withLock { $0 } }

    public func embed(_ segment: SpeechSegment) async throws -> SpeakerEmbedding? {
        let index = receivedSegments.withLock { received in
            received.append(segment)
            return received.count - 1
        }
        return try await respond(index, segment).map { SpeakerEmbedding($0, modelVersion) }
    }
}

public final class InMemoryUtteranceRepository: UtteranceRepository {
    private struct State {
        var rows: [Utterance] = []
        var nextId: Int64 = 1
    }

    private let state = Locked(State())

    public init() {}

    public func insert(_ utterance: NewUtterance) -> Utterance {
        state.withLock { state in
            let row = Utterance(
                id: state.nextId,
                startedAtMillis: utterance.startedAtMillis,
                endedAtMillis: utterance.endedAtMillis,
                text: utterance.text,
                confidence: utterance.confidence,
                createdAtMillis: utterance.createdAtMillis,
                speakerId: utterance.speakerId,
                speakerConfidence: utterance.speakerConfidence
            )
            state.nextId += 1
            state.rows.append(row)
            return row
        }
    }

    public func insertAll(_ utterances: [NewUtterance]) -> [Utterance] {
        utterances.map { insert($0) }
    }

    public func listOverlapping(fromMillis: Int64, toMillis: Int64) -> [Utterance] {
        state.withLock { $0.rows }
            .filter { $0.startedAtMillis < toMillis && $0.endedAtMillis > fromMillis }
            .stableSorted { ($0.startedAtMillis, $0.id) < ($1.startedAtMillis, $1.id) }
    }

    public func listRecent(limit: Int) -> [Utterance] {
        Array(
            state.withLock { $0.rows }
                .stableSorted { ($0.startedAtMillis, $0.id) > ($1.startedAtMillis, $1.id) }
                .prefix(limit)
        )
    }

    public func find(id: Int64) -> Utterance? {
        state.withLock { $0.rows.first { $0.id == id } }
    }

    public func setSpeaker(id: Int64, speakerId: Int64?) -> Bool {
        state.withLock { state in
            let exists = state.rows.contains { $0.id == id }
            state.rows = state.rows.map { row in
                guard row.id == id else { return row }
                var changed = row
                changed.speakerId = speakerId
                changed.speakerConfidence = nil
                return changed
            }
            return exists
        }
    }

    public func countBySpeaker() -> [Int64: Int] {
        state.withLock { state in
            state.rows.compactMap(\.speakerId).reduce(into: [:]) { counts, id in counts[id, default: 0] += 1 }
        }
    }

    /// 話者の統合で発話を付け替える。本物は 1 つのトランザクションで行う。
    public func moveSpeaker(_ fromId: Int64, _ intoId: Int64) {
        state.withLock { state in
            state.rows = state.rows.map { row in
                guard row.speakerId == fromId else { return row }
                var changed = row
                changed.speakerId = intoId
                changed.speakerConfidence = nil
                return changed
            }
        }
    }

    /// 話者のリセットで発話の話者を外す。本物は 1 つのトランザクションで行う。
    public func clearSpeakers() {
        state.withLock { state in
            state.rows = state.rows.map { row in
                var changed = row
                changed.speakerId = nil
                changed.speakerConfidence = nil
                return changed
            }
        }
    }
}

public final class InMemorySpeakerDecisionLog: SpeakerDecisionLog {
    private struct Key {
        let day: Int64
        let decision: SpeakerDecision
        let bucket: Int
    }

    private let rows = Locked<[(key: Key, sample: SpeakerDecisionSample)]>([])

    public init() {}

    public func record(_ samples: [SpeakerDecisionSample], nowMillis: Int64) {
        rows.withLock { rows in
            for sample in samples {
                rows.append((Key(day: Self.day(nowMillis), decision: sample.decision, bucket: scoreBucket(sample.selfScore)), sample))
            }
        }
    }

    public func summarize(fromMillis: Int64) -> [SpeakerDecisionBucket] {
        let fromDay = Self.day(fromMillis)
        let rows = rows.withLock { $0 }.filter { $0.key.day >= fromDay }
        // Kotlin の groupBy と同じく、最初に現れた順にまとめる
        var order: [String] = []
        var groups: [String: (decision: SpeakerDecision, bucket: Int, runs: Int64, voicedMillis: Int64)] = [:]
        for row in rows {
            let id = "\(row.key.decision.name):\(row.key.bucket)"
            if groups[id] == nil {
                order.append(id)
                groups[id] = (row.key.decision, row.key.bucket, 0, 0)
            }
            groups[id]!.runs += 1
            groups[id]!.voicedMillis += row.sample.voicedMillis
        }
        return order.map { groups[$0]! }
            .map { SpeakerDecisionBucket($0.decision, $0.bucket, $0.runs, $0.voicedMillis) }
            .stableSorted { ($0.decision.name.lowercased(), $0.bucket) < ($1.decision.name.lowercased(), $1.bucket) }
    }

    public func prune(beforeMillis: Int64) {
        let beforeDay = Self.day(beforeMillis)
        rows.withLock { $0.removeAll { $0.key.day < beforeDay } }
    }

    /// Java の Math.floorDiv。
    private static func day(_ millis: Int64) -> Int64 {
        let quotient = millis / MILLIS_PER_DAY
        return (millis % MILLIS_PER_DAY != 0 && millis < 0) ? quotient - 1 : quotient
    }

    private static let MILLIS_PER_DAY: Int64 = 86_400_000
}

public final class InMemorySpeakerRepository: SpeakerRepository {
    private struct Saved {
        let embedding: SpeakerEmbedding
        let sampleCount: Int
        let updatedAtMillis: Int64
        let voicedMillis: Int64
    }

    private struct EmbeddingKey: Hashable {
        let speakerId: Int64
        let modelVersion: String
    }

    private struct State {
        var speakers: [Speaker] = []
        var embeddings: [EmbeddingKey: Saved] = [:]
        var adaptations: [String: SelfAdaptation] = [:]
        var nextId: Int64 = 1

        mutating func add(_ name: String, _ source: SpeakerNameSource, _ isSelf: Bool, _ nowMillis: Int64) -> Speaker {
            let speaker = Speaker(nextId, name, source, isSelf, nowMillis)
            nextId += 1
            speakers.append(speaker)
            return speaker
        }

        mutating func addProvisional(_ nowMillis: Int64) -> Speaker {
            let used = Set(speakers.map(\.displayName))
            var number = 1
            while used.contains("話者\(number)") { number += 1 }
            return add("話者\(number)", .PROVISIONAL, false, nowMillis)
        }

        mutating func save(_ speakerId: Int64, _ embedding: SpeakerEmbedding, _ sampleCount: Int, _ nowMillis: Int64, _ voicedMillis: Int64) {
            embeddings[EmbeddingKey(speakerId: speakerId, modelVersion: embedding.modelVersion)] =
                Saved(embedding: embedding, sampleCount: sampleCount, updatedAtMillis: nowMillis, voicedMillis: voicedMillis)
        }

        mutating func rename(_ speakerId: Int64, _ name: String, _ source: SpeakerNameSource) {
            speakers = speakers.map { speaker in
                guard speaker.id == speakerId else { return speaker }
                var changed = speaker
                changed.displayName = name
                changed.nameSource = source
                return changed
            }
        }

        mutating func renumber() {
            let taken = Set(speakers.filter { $0.nameSource != .PROVISIONAL }.map(\.displayName))
            var number = 0
            func nextName() -> String {
                repeat { number += 1 } while taken.contains("話者\(number)")
                return "話者\(number)"
            }
            speakers = speakers.map { speaker in
                guard speaker.nameSource == .PROVISIONAL else { return speaker }
                var changed = speaker
                changed.displayName = nextName()
                return changed
            }
        }
    }

    private let utterances: InMemoryUtteranceRepository
    private let state = Locked(State())

    public init(utterances: InMemoryUtteranceRepository = InMemoryUtteranceRepository()) {
        self.utterances = utterances
    }

    public func listSpeakers() -> [Speaker] {
        state.withLock { $0.speakers }
    }

    public func listEmbeddings(modelVersion: String, limit: Int) throws -> [StoredSpeakerEmbedding] {
        try state.withLock { state in
            let isSelf = Set(state.speakers.filter(\.isSelf).map(\.id))
            return try state.embeddings
                .filter { $0.key.modelVersion == modelVersion }
                .stableSorted { first, second in
                    let firstKey = (isSelf.contains(first.key.speakerId) ? 1 : 0, first.value.updatedAtMillis, first.key.speakerId)
                    let secondKey = (isSelf.contains(second.key.speakerId) ? 1 : 0, second.value.updatedAtMillis, second.key.speakerId)
                    return firstKey > secondKey
                }
                .prefix(limit)
                .map { key, saved in
                    guard let speaker = state.speakers.first(where: { $0.id == key.speakerId }) else {
                        throw NoSuchElementException("Collection contains no element matching the predicate.")
                    }
                    return StoredSpeakerEmbedding(
                        key.speakerId,
                        isSelf.contains(key.speakerId),
                        saved.embedding.vector,
                        saved.sampleCount,
                        saved.voicedMillis,
                        speaker.nameSource,
                        saved.updatedAtMillis
                    )
                }
        }
    }

    public func saveSelfEmbedding(_ embedding: SpeakerEmbedding, sampleCount: Int, nowMillis: Int64) -> Speaker {
        state.withLock { state in
            let selfSpeaker = state.speakers.first(where: \.isSelf) ?? state.add("自分", .SELF, true, nowMillis)
            state.save(selfSpeaker.id, embedding, sampleCount, nowMillis, 0)
            state.adaptations.removeAll()
            return selfSpeaker
        }
    }

    public func createProvisionalSpeaker(_ embedding: SpeakerEmbedding, nowMillis: Int64, voicedMillis: Int64) -> Speaker {
        state.withLock { state in
            let speaker = state.addProvisional(nowMillis)
            state.save(speaker.id, embedding, 1, nowMillis, voicedMillis)
            return speaker
        }
    }

    public func createProvisionalSpeakerWithoutVoice(nowMillis: Int64) -> Speaker {
        state.withLock { $0.addProvisional(nowMillis) }
    }

    public func updateEmbedding(
        speakerId: Int64,
        _ embedding: SpeakerEmbedding,
        sampleCount: Int,
        nowMillis: Int64,
        voicedMillis: Int64
    ) {
        state.withLock { $0.save(speakerId, embedding, sampleCount, nowMillis, voicedMillis) }
    }

    public func mergeSpeakers(_ merges: [SpeakerMerge], nowMillis: Int64, renumberProvisional: Bool) {
        state.withLock { state in
            let applicable = merges.filter { merge in state.speakers.contains { $0.id == merge.fromId && !$0.isSelf } }
            for merge in applicable {
                utterances.moveSpeaker(merge.fromId, merge.intoId)
                state.embeddings = state.embeddings.filter { $0.key.speakerId != merge.fromId }
                state.speakers.removeAll { $0.id == merge.fromId }
                if let merged = merge.merged {
                    state.save(merge.intoId, merged.embedding, merged.sampleCount, nowMillis, merged.voicedMillis)
                }
            }
            if renumberProvisional { state.renumber() }
        }
    }

    public func resetSpeakers() {
        state.withLock { state in
            utterances.clearSpeakers()
            state.adaptations.removeAll()
            state.embeddings.removeAll()
            state.speakers.removeAll()
        }
    }

    public func findSelfAdaptation(modelVersion: String) -> SelfAdaptation? {
        state.withLock { $0.adaptations[modelVersion] }
    }

    public func saveSelfAdaptation(_ adaptation: SelfAdaptation, nowMillis: Int64) {
        state.withLock { $0.adaptations[adaptation.modelVersion] = adaptation }
    }

    public func setManualName(speakerId: Int64, name: String) -> Bool {
        state.withLock { state in
            let exists = state.speakers.contains { $0.id == speakerId }
            if exists { state.rename(speakerId, name, .MANUAL) }
            return exists
        }
    }

    public func setInferredName(speakerId: Int64, name: String) -> Bool {
        state.withLock { state in
            let speaker = state.speakers.first { $0.id == speakerId }
            let isReplaceable = speaker.map { !$0.isSelf && [.PROVISIONAL, .INFERRED].contains($0.nameSource) } ?? false
            if isReplaceable { state.rename(speakerId, name, .INFERRED) }
            return isReplaceable
        }
    }
}

public final class InMemoryCaptureGapRepository: CaptureGapRepository {
    private let rows = Locked<[CaptureGap]>([])

    public init() {}

    public func insert(_ gap: CaptureGap) {
        rows.withLock { $0.append(gap) }
    }

    public func listOverlapping(fromMillis: Int64, toMillis: Int64) -> [CaptureGap] {
        rows.withLock { $0 }
            .filter { $0.startedAtMillis < toMillis && $0.endedAtMillis > fromMillis }
            .stableSorted { $0.startedAtMillis < $1.startedAtMillis }
    }
}

/// [respond] の結果を返す。受け取った依頼は [received] に残す。
public final class FakeLlm: Llm {
    public let modelVersion: String
    private let respond: @Sendable (_ index: Int, _ request: LlmRequest) async throws -> String
    private let receivedRequests = Locked<[LlmRequest]>([])

    public init(
        modelVersion: String = "fake-1",
        respond: @escaping @Sendable (_ index: Int, _ request: LlmRequest) async throws -> String = { _, _ in "" }
    ) {
        self.modelVersion = modelVersion
        self.respond = respond
    }

    public var received: [LlmRequest] { receivedRequests.withLock { $0 } }

    public func generate(_ request: LlmRequest) async throws -> String {
        let index = receivedRequests.withLock { received in
            received.append(request)
            return received.count - 1
        }
        return try await respond(index, request)
    }

    /// 要約、タイトル、ToDo の依頼を見分けて、固定の出力を返す。ToDo のタイトルは [todoTitle] の結果を返す。
    public static func organizing(
        summary: String = "- 要約",
        todos: String = #"{"items": []}"#,
        title: String? = nil,
        todoTitle: @escaping @Sendable (String) -> String = { $0 }
    ) -> FakeLlm {
        let title = title ?? summary
        return FakeLlm { _, request in
            if request.isTodoRequest { return todos }
            if request.isTodoTitleRequest { return todoTitle(request.user.substringAfter("ToDo:\n")) }
            if request.isTitleRequest { return title }
            return summary
        }
    }
}

extension LlmRequest {
    public var isTodoRequest: Bool { user.utf16Contains("JSON だけを出力") }

    public var isTitleRequest: Bool { user.utf16Contains("タイトルだけを") }

    public var isTodoTitleRequest: Bool { isTitleRequest && user.utf16Contains("ToDo:") }
}

extension String {
    /// Kotlin の `in`。UTF-16 の単位で比べる。
    func utf16Contains(_ other: String) -> Bool {
        utf16IndexOf(other) != nil
    }

    /// Kotlin の `substringAfter`。見つからなければ全体を返す。
    func substringAfter(_ delimiter: String) -> String {
        guard let index = utf16IndexOf(delimiter) else { return self }
        return String(decoding: Array(utf16)[(index + delimiter.utf16.count)...], as: UTF16.self)
    }

    private func utf16IndexOf(_ other: String) -> Int? {
        let units = Array(utf16)
        let target = Array(other.utf16)
        if target.isEmpty { return 0 }
        guard units.count >= target.count else { return nil }
        for index in 0...(units.count - target.count) where Array(units[index..<index + target.count]) == target {
            return index
        }
        return nil
    }
}

/// [utterances] の発話を整理の対象として読む。本物と同じ 1 件ずつの記録だけを行う。
public final class InMemoryMemoRepository: MemoRepository {
    private final class Record: @unchecked Sendable {
        let memo: Memo
        let utteranceIds: [Int64]
        var todos: [Todo]

        init(_ memo: Memo, _ utteranceIds: [Int64], _ todos: [Todo]) {
            self.memo = memo
            self.utteranceIds = utteranceIds
            self.todos = todos
        }
    }

    private struct State {
        var records: [Record] = []
        var organized: Set<Int64> = []
        var nextMemoId: Int64 = 1
        var nextTodoId: Int64 = 1
    }

    private let utterances: any UtteranceRepository
    // Kotlin と同じく、DB の読み出し(await)をまたいでロックを持つ
    private let mutex = AsyncMutex()
    private let state = Locked(State())

    public init(utterances: any UtteranceRepository) {
        self.utterances = utterances
    }

    private func all() async throws -> [Utterance] {
        try await utterances.listOverlapping(fromMillis: Int64.min, toMillis: Int64.max)
    }

    public func listUnorganizedUtterances() async throws -> [Utterance] {
        try await mutex.withLock {
            let organized = state.withLock { $0.organized }
            return try await all().filter { !organized.contains($0.id) }
        }
    }

    public func recordMemo(_ memo: NewMemo) async throws -> Memo? {
        try await mutex.withLock {
            let all = try await all()
            return state.withLock { state -> Memo? in
                let unorganized = Set(all.map(\.id)).subtracting(state.organized)
                guard Set(memo.utteranceIds).isSubset(of: unorganized) else { return nil }
                let saved = Memo(
                    id: state.nextMemoId,
                    startedAtMillis: memo.startedAtMillis,
                    endedAtMillis: memo.endedAtMillis,
                    title: memo.title,
                    summary: memo.summary,
                    todoExtraction: memo.todoExtraction,
                    modelVersion: memo.modelVersion,
                    createdAtMillis: memo.createdAtMillis
                )
                state.nextMemoId += 1
                let todos = memo.todos.map { todo in
                    let saved = Todo(
                        id: state.nextTodoId,
                        memoId: saved.id,
                        task: todo.task,
                        title: todo.title,
                        kind: todo.kind,
                        dueDate: todo.dueDate,
                        status: todo.status,
                        createdAtMillis: memo.createdAtMillis
                    )
                    state.nextTodoId += 1
                    return saved
                }
                state.records.append(Record(saved, memo.utteranceIds, todos))
                state.organized.formUnion(memo.utteranceIds)
                return saved
            }
        }
    }

    public func markOrganized(_ utteranceIds: [Int64], organizedAtMillis: Int64) async throws {
        try await mutex.withLock { state.withLock { $0.organized.formUnion(utteranceIds) } }
    }

    public func listMemos() async throws -> [Memo] {
        try await mutex.withLock {
            state.withLock { $0.records.map(\.memo) }.stableSorted { ($0.startedAtMillis, $0.id) < ($1.startedAtMillis, $1.id) }
        }
    }

    public func listUtteranceIds(memoId: Int64) async throws -> [Int64] {
        try await mutex.withLock {
            let ids = Set(state.withLock { $0.records.first { $0.memo.id == memoId }?.utteranceIds ?? [] })
            return try await all().filter { ids.contains($0.id) }.map(\.id)
        }
    }

    public func listTodos(memoId: Int64) async throws -> [Todo] {
        try await mutex.withLock { state.withLock { $0.records.first { $0.memo.id == memoId }?.todos ?? [] } }
    }

    public func listRecentMemos(limit: Int) async throws -> [Memo] {
        try await mutex.withLock {
            Array(
                state.withLock { $0.records.map(\.memo) }
                    .stableSorted { ($0.startedAtMillis, $0.id) > ($1.startedAtMillis, $1.id) }
                    .prefix(limit)
            )
        }
    }

    public func findMemo(memoId: Int64) async throws -> Memo? {
        try await mutex.withLock { state.withLock { $0.records.first { $0.memo.id == memoId }?.memo } }
    }

    public func listMemoUtterances(memoId: Int64) async throws -> [Utterance] {
        try await mutex.withLock {
            let ids = Set(state.withLock { $0.records.first { $0.memo.id == memoId }?.utteranceIds ?? [] })
            return try await all().filter { ids.contains($0.id) }
        }
    }

    public func listTodosByPriority(limit: Int) async throws -> [Todo] {
        try await mutex.withLock {
            let todos = state.withLock { $0.records.flatMap(\.todos) }
            return Array(todos.stableSorted(by: Self.isHigherPriority).prefix(limit))
        }
    }

    /// 未完了、期限あり、期限の早い順、新しい順、記録した順。Kotlin の compareBy は null を先に置く。
    private static func isHigherPriority(_ first: Todo, _ second: Todo) -> Bool {
        let firstDone = first.status == .DONE
        let secondDone = second.status == .DONE
        if firstDone != secondDone { return !firstDone }
        let firstNoDue = first.dueDate == nil
        let secondNoDue = second.dueDate == nil
        if firstNoDue != secondNoDue { return !firstNoDue }
        let firstDue = first.dueDate?.description
        let secondDue = second.dueDate?.description
        if firstDue != secondDue {
            guard let firstDue else { return true }
            guard let secondDue else { return false }
            return firstDue < secondDue
        }
        if first.createdAtMillis != second.createdAtMillis { return first.createdAtMillis > second.createdAtMillis }
        return first.id < second.id
    }

    public func completeTodo(todoId: Int64, completedAtMillis: Int64) async throws -> TodoCompletion {
        try await mutex.withLock {
            state.withLock { state in
                guard let record = state.records.first(where: { $0.todos.contains { $0.id == todoId } }) else {
                    return .NOT_FOUND
                }
                if record.todos.first(where: { $0.id == todoId })!.status == .DONE { return .ALREADY_DONE }
                record.todos = record.todos.map { todo in
                    guard todo.id == todoId else { return todo }
                    var done = todo
                    done.status = .DONE
                    done.completedAtMillis = completedAtMillis
                    return done
                }
                return .COMPLETED
            }
        }
    }
}
