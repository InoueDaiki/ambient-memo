@testable import EngineDomain
import EngineDomainTesting
import Foundation

// 音声から保存までの流れと、自分の声の登録の実行役

/// 読んだフレームを数える音声。`audio` の形は capture-pipeline.json の inputSchema にある。
private final class SpecAudioSource: AudioSource {
    let sampleRateHz = TEST_SAMPLE_RATE_HZ
    private let read = SpecLocked(0)
    private let endlessFrameMillis: Int64?
    private let isFailingAtEnd: Bool
    private let fake: FakeAudioSource?

    init(_ json: SpecObject) throws {
        endlessFrameMillis = try json.objOrNull("endless").map { try $0.long("frameMillis") }
        isFailingAtEnd = try json.boolOr("failAtEnd", false)
        fake = endlessFrameMillis == nil ? try Self.fakeSource(json) : nil
    }

    var framesRead: Int { read.current }

    func frames() -> AsyncThrowingStream<AudioFrame, any Error> {
        if let frameMillis = endlessFrameMillis {
            let silence = SyntheticAudio.silence(Int(frameMillis))
            return AsyncThrowingStream(unfolding: { [read] in
                // 読み取りを止められたフレームも数える
                let count = read.withLock { count in
                    count += 1
                    return count
                }
                return AudioFrame(startedAtMillis: Int64(count - 1) * frameMillis, samples: silence)
            })
        }
        let iterator = SpecIterator(fake!.frames().makeAsyncIterator())
        let isFailingAtEnd = isFailingAtEnd
        return AsyncThrowingStream(unfolding: { [read] in
            if let frame = try await iterator.next() {
                read.withLock { $0 += 1 }
                return frame
            }
            if isFailingAtEnd { throw SpecFailure("source-failed") }
            return nil
        })
    }

    private static func fakeSource(_ json: SpecObject) throws -> FakeAudioSource {
        let samples = try json.objects("pieces").flatMap { piece in
            try piece.longOrNull("tone").map { SyntheticAudio.tone(Int($0)) } ?? SyntheticAudio.silence(try piece.int("silence"))
        }
        let jumps = try json.objects("jumps").map { (from: try $0.int("fromSample"), offset: try $0.long("offsetMillis")) }
        return FakeAudioSource(
            samples,
            startMillis: try json.longOr("startMillis", 0),
            frameStartMillis: { position, defaultMillis in
                defaultMillis + jumps.filter { position >= $0.from }.reduce(0) { $0 + $1.offset }
            }
        )
    }
}

/// 別のタスクから呼ばれる AsyncIterator を包む。呼ぶのは受け取る側の 1 つのタスクだけ。
private final class SpecIterator: @unchecked Sendable {
    private var iterator: AsyncThrowingStream<AudioFrame, any Error>.AsyncIterator

    init(_ iterator: AsyncThrowingStream<AudioFrame, any Error>.AsyncIterator) {
        self.iterator = iterator
    }

    func next() async throws -> AudioFrame? {
        try await iterator.next()
    }
}

/// `[開始, 終わり)` の時間帯を、Fake の VAD の閉じた範囲にする。
private func vad(_ input: SpecObject) throws -> ScriptedVoiceActivityDetector {
    let ranges = try input.value("vad").arrayValue.map { range -> ClosedRange<Int64> in
        let bounds = try range.arrayValue.map(specLong)
        guard bounds.count == 2 else { throw SpecError("vad の時間帯は [開始, 終わり] で書きます") }
        return bounds[0]...(bounds[1] - 1)
    }
    return ScriptedVoiceActivityDetector(ranges)
}

/// 呼ばれた順の番号で応答を決める ASR の Fake。`responses` にない番号は「発話N」を返す。
private final class ScriptedTranscriber: Transcriber {
    private let responses: [SpecObject]
    private let calls = SpecLocked<[(segment: SpeechSegment, hadSound: Bool)]>([])

    init(_ json: SpecObject?) throws {
        responses = try json?.objects("responses") ?? []
    }

    var received: [(segment: SpeechSegment, hadSound: Bool)] { calls.current }

    func transcribe(_ segment: SpeechSegment) async throws -> TimedTranscription {
        let hadSound = try segment.withSamples { $0.contains { $0 != 0 } }
        let index = calls.withLock { calls in
            calls.append((segment, hadSound))
            return calls.count - 1
        }
        let response = index < responses.count ? responses[index] : nil
        if try response?.boolOr("fail", false) == true { throw SpecFailure("transcribe-failed") }
        let text = try response?.strOrNull("text") ?? "発話\(index + 1)"
        let transcription = try Transcription(text, confidence: try response?.doubleOrNull("confidence"))
        let durationMillis = segment.endedAtMillis - segment.startedAtMillis
        let tokens: [TranscriptToken]
        switch response?["tokens"] {
        case nil:
            tokens = evenTokens(text, durationMillis)
        case .string("none"):
            tokens = []
        case let given?:
            tokens = try given.arrayValue.map(token)
        }
        return TimedTranscription(transcription, tokens)
    }
}

private func filter(_ json: SpecObject?) throws -> any TranscriptionFilter {
    let texts = Set(try json?.strings("texts") ?? [])
    switch try json?.str("type") ?? "all" {
    case "all": return ClosureTranscriptionFilter { _, _ in true }
    case "none": return ClosureTranscriptionFilter { _, _ in false }
    case "rejectTexts": return ClosureTranscriptionFilter { text, _ in !texts.contains(text) }
    case "onlyTexts": return ClosureTranscriptionFilter { text, _ in texts.contains(text) }
    case "noise": return NoiseTextFilter(longDurationMillis: try json?.longOrNull("longDurationMillis") ?? 2_000)
    case let type: throw SpecError("知らない filter です: \(type)")
    }
}

/// 指定した回の insertAll で失敗する。
private final class FailingUtterances: UtteranceRepository {
    private let delegate: any UtteranceRepository
    private let failingCalls: Set<Int>
    private let calls = SpecLocked(0)

    init(_ delegate: any UtteranceRepository, _ failingCalls: Set<Int>) {
        self.delegate = delegate
        self.failingCalls = failingCalls
    }

    func insert(_ utterance: NewUtterance) async throws -> Utterance {
        try await delegate.insert(utterance)
    }

    func insertAll(_ utterances: [NewUtterance]) async throws -> [Utterance] {
        let call = calls.withLock { calls in
            defer { calls += 1 }
            return calls
        }
        if failingCalls.contains(call) { throw SpecFailure("utterance-insert-failed") }
        return try await delegate.insertAll(utterances)
    }

    func listOverlapping(fromMillis: Int64, toMillis: Int64) async throws -> [Utterance] {
        try await delegate.listOverlapping(fromMillis: fromMillis, toMillis: toMillis)
    }

    func listRecent(limit: Int) async throws -> [Utterance] {
        try await delegate.listRecent(limit: limit)
    }

    func find(id: Int64) async throws -> Utterance? {
        try await delegate.find(id: id)
    }

    func setSpeaker(id: Int64, speakerId: Int64?) async throws -> Bool {
        try await delegate.setSpeaker(id: id, speakerId: speakerId)
    }

    func countBySpeaker() async throws -> [Int64: Int] {
        try await delegate.countBySpeaker()
    }
}

private final class FailingGaps: CaptureGapRepository {
    private let delegate: any CaptureGapRepository

    init(_ delegate: any CaptureGapRepository) {
        self.delegate = delegate
    }

    func insert(_: CaptureGap) async throws {
        throw SpecFailure("gap-insert-failed")
    }

    func listOverlapping(fromMillis: Int64, toMillis: Int64) async throws -> [CaptureGap] {
        try await delegate.listOverlapping(fromMillis: fromMillis, toMillis: toMillis)
    }
}

private let WORLD_PARTS: Set<String> = ["speakers", "embeddings", "adaptation", "utterances", "embedderCalls", "diagnostics"]

@Sendable func capturePipeline(_ input: SpecObject) async throws -> SpecJSON {
    let speakerInput = try input.objOrNull("speakers")
    var worldInput = speakerInput ?? [:]
    worldInput["clockMillis"] = .number(String(try input.long("clockMillis")))
    let world = try SpeakerWorld(worldInput)
    let gaps = InMemoryCaptureGapRepository()
    let detector = try vad(input)
    let transcriber = try ScriptedTranscriber(try input.objOrNull("transcriber"))
    let events = RecordedEvents()
    let failures = try input.objOrNull("failures")
    let failingInserts = try failures?["utteranceInsertAll"].map { try $0.arrayValue.map(specInt) }
    let pipeline = CapturePipeline(
        audioSource: try SpecAudioSource(try input.obj("audio")),
        voiceActivityDetector: detector,
        transcriber: transcriber,
        utterances: failingInserts.map { FailingUtterances(world.utterances, Set($0)) } ?? world.utterances,
        captureGaps: try failures?.boolOr("gapInsert", false) == true ? FailingGaps(gaps) : gaps,
        clock: world.clock,
        config: try captureConfig(try input.objOrNull("captureConfig")),
        transcriptionFilter: try filter(try input.objOrNull("filter")),
        speakerIdentifier: speakerInput.map { _ in world.identifier },
        diagnostics: events
    )
    if let speakerInput { try await world.setUp(speakerInput) }
    var failure: String?
    do {
        for _ in 0..<(try input.intOr("runs", 1)) { try await pipeline.run() }
    } catch let error as SpecFailure {
        failure = error.kind
    }
    let include = try input.strings("include")
    let worldParts = try await world.step(["op": "snapshot", "include": .array(include.filter(WORLD_PARTS.contains).map { .string($0) })])
    var parts = try worldParts.objectValue
    for part in include where !WORLD_PARTS.contains(part) {
        switch part {
        case "gaps":
            parts[part] = .array(gaps.listOverlapping(fromMillis: Int64.min, toMillis: Int64.max).map(gapJSON))
        case "transcriberCalls":
            parts[part] = .array(transcriber.received.map { call in
                var values = (try? segmentCallJSON(call.segment).objectValue) ?? [:]
                values["hadSound"] = .bool(call.hadSound)
                return .object(values)
            })
        case "allTranscriberReleased":
            parts[part] = .bool(transcriber.received.allSatisfy { $0.segment.isReleased })
        case "vadResets":
            parts[part] = json(detector.resetCount)
        case "segments":
            parts[part] = .array(events.all.compactMap { $0 as? SegmentProcessed }.map(segmentEventJSON))
        default:
            throw SpecError("知らない include です: \(part)")
        }
    }
    parts["failure"] = json(failure)
    return .object(parts)
}

/// 処理の時間は実行環境で変わるので比べない。待ちの時間は Fake の時計で決まるので比べる。
private func segmentEventJSON(_ event: SegmentProcessed) -> SpecJSON {
    obj([
        "segmentMillis": event.segmentMillis,
        "waitMillis": event.waitMillis,
        "textLength": event.textLength,
        "turns": event.turns,
        "outcome": event.outcome,
    ])
}

@Sendable func selfEnrollment(_ input: SpecObject) async throws -> SpecJSON {
    let world = try SpeakerWorld(input)
    let source = try SpecAudioSource(try input.obj("audio"))
    let progress = SpecLocked<[EnrollmentProgress]>([])
    let enrollment = SelfEnrollment(
        audioSource: source,
        voiceActivityDetector: try vad(input),
        embedder: world.embedder,
        speakers: world.speakers,
        clock: world.clock,
        captureConfig: try captureConfig(try input.objOrNull("captureConfig")),
        config: try enrollmentConfig(try input.objOrNull("enrollmentConfig")),
        onProgress: { item in progress.withLock { $0.append(item) } }
    )
    try await world.setUp(input)
    var outcome: EnrollmentOutcome?
    var failure: String?
    do {
        outcome = try await enrollment.run()
    } catch let error as SpecFailure {
        failure = error.kind
    }
    var state = try await world.step(["op": "snapshot", "include": .array(["speakers", "embeddings", "embedderCalls"].map { .string($0) })]).objectValue
    state["outcome"] = json(outcome)
    state["failure"] = json(failure)
    state["progress"] = .array(progress.current.map { obj(["acceptedSamples": $0.acceptedSamples, "isRejected": $0.isRejected]) })
    state["framesRead"] = json(source.framesRead)
    return .object(state)
}

private func enrollmentConfig(_ json: SpecObject?) throws -> EnrollmentConfig {
    let defaults = EnrollmentConfig.default
    let values = json ?? [:]
    try values.requireKeys(["requiredSamples", "minSampleMillis", "maxListeningMillis"], "enrollmentConfig")
    return try EnrollmentConfig(
        requiredSamples: try values.intOr("requiredSamples", defaults.requiredSamples),
        minSampleMillis: try values.longOr("minSampleMillis", defaults.minSampleMillis),
        maxListeningMillis: try values.longOr("maxListeningMillis", defaults.maxListeningMillis)
    )
}
