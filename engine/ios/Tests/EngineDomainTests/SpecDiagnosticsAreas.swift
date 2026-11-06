@testable import EngineDomain
import EngineDomainTesting
import Foundation

// 静かな間の VAD の省略と、動作の記録の実行役

private let DEFAULT_FRAME_SAMPLES = 160

private func constantFrame(_ amplitude: Int, _ samples: Int) -> AudioFrame {
    AudioFrame(startedAtMillis: 0, samples: [Int16](repeating: Int16(truncatingIfNeeded: amplitude), count: samples))
}

@Sendable func quietGate(_ input: SpecObject) async throws -> SpecJSON {
    switch try input.str("op") {
    case "level":
        let frame = constantFrame(try input.int("amplitude"), try input.intOr("samples", DEFAULT_FRAME_SAMPLES))
        return obj(["dbfs": frame.levelDbfs()])
    case "gate":
        return try runGate(input)
    case let op:
        throw SpecError("知らない op です: \(op)")
    }
}

/// 渡されたフレームと reset を数え、フレームごとに指定された判定を返す VAD。
private final class CountingDetector: VoiceActivityDetector {
    struct State {
        var speaking = false
        var frames = 0
        var resets = 0
    }

    let state = SpecLocked(State())

    func isSpeech(_: AudioFrame) -> Bool {
        state.withLock { state in
            state.frames += 1
            return state.speaking
        }
    }

    func reset() {
        state.withLock { $0.resets += 1 }
    }
}

private func runGate(_ input: SpecObject) throws -> SpecJSON {
    let delegate = CountingDetector()
    let levels = AudioLevelCounter()
    let gate = try input.doubleOrNull("quietDbfs").map { QuietGatedVoiceActivityDetector(delegate, quietDbfs: $0, levels: levels) }
        ?? QuietGatedVoiceActivityDetector(delegate, levels: levels)
    var results: [SpecJSON] = []
    for step in try input.objects("steps") {
        switch try step.str("op") {
        case "frame":
            let speaking = try step.bool("delegateSpeech")
            delegate.state.withLock { $0.speaking = speaking }
            let frame = constantFrame(try step.int("amplitude"), try step.intOr("samples", DEFAULT_FRAME_SAMPLES))
            results.append(obj(["isSpeech": try gate.isSpeech(frame)]))
        case "reset":
            gate.reset()
            results.append(obj([:]))
        case "drain":
            results.append(obj(["summary": levels.drain().map(levelSummary)]))
        case let op:
            throw SpecError("知らない op です: \(op)")
        }
    }
    let counted = delegate.state.current
    return obj(["steps": results, "delegateFrames": counted.frames, "delegateResets": counted.resets])
}

private func levelSummary(_ summary: AudioLevelSummary) -> SpecJSON {
    obj([
        "frames": summary.frames,
        "skippedFrames": summary.skippedFrames,
        "histogram": summary.histogram.map { [$0.band, $0.count] },
    ])
}

@Sendable func diagnostics(_ input: SpecObject) async throws -> SpecJSON {
    switch try input.str("op") {
    case "encode":
        let process: DiagnosticProcess = try input.enumValue("process")
        return obj(["line": encodeDiagnostic(try input.long("atMillis"), process, try event(try input.obj("event")))])
    case "timed-llm":
        return try await timedLlm(input)
    case "recording-gaps":
        return try await recordingGaps(input)
    case let op:
        throw SpecError("知らない op です: \(op)")
    }
}

private func event(_ json: SpecObject) throws -> any DiagnosticEvent {
    switch try json.str("type") {
    case "segment":
        return SegmentProcessed(
            try json.long("segmentMillis"),
            try json.long("waitMillis"),
            try json.long("transcribeMillis"),
            try json.long("saveMillis"),
            try json.int("textLength"),
            try json.int("turns"),
            try json.enumValue("outcome")
        )
    case "gap":
        return CaptureGapRecorded(try json.enumValue("reason"), try json.long("gapMillis"))
    case "capture":
        return CaptureChanged(isListening: try json.bool("isListening"), isInterrupted: try json.bool("isInterrupted"))
    case "llm_backend":
        return LlmBackendChosen(try json.enumValue("backend"))
    case "llm_load":
        return LlmLoaded(loadMillis: try json.long("loadMillis"), isLoaded: try json.bool("isLoaded"))
    case "llm_generate":
        return LlmGenerated(
            inputChars: try json.int("inputChars"),
            maxOutputTokens: try json.int("maxOutputTokens"),
            outputChars: try json.int("outputChars"),
            generateMillis: try json.long("generateMillis"),
            isFailed: try json.bool("isFailed")
        )
    case "organize":
        return OrganizeFinished(
            outcome: try json.enumValue("outcome"),
            memoCount: try json.int("memoCount"),
            unreadableCount: try json.int("unreadableCount"),
            totalMillis: try json.long("totalMillis")
        )
    case "ask":
        return AskFinished(
            outcome: try json.enumValue("outcome"),
            searchRounds: try json.int("searchRounds"),
            sourceCount: try json.int("sourceCount"),
            totalMillis: try json.long("totalMillis")
        )
    case "device":
        return DeviceSample(
            batteryPercent: try json.int("batteryPercent"),
            chargeMicroAmpereHours: try json.long("chargeMicroAmpereHours"),
            temperatureDeciCelsius: try json.int("temperatureDeciCelsius"),
            isCharging: try json.bool("isCharging"),
            thermalStatus: try json.int("thermalStatus"),
            isScreenOn: try json.bool("isScreenOn"),
            isDeviceIdle: try json.bool("isDeviceIdle"),
            isPowerSave: try json.bool("isPowerSave"),
            elapsedMillis: try json.long("elapsedMillis"),
            uptimeMillis: try json.long("uptimeMillis"),
            processCpuMillis: try json.long("processCpuMillis"),
            rssKilobytes: try json.long("rssKilobytes"),
            peakRssKilobytes: try json.long("peakRssKilobytes")
        )
    case "audio_level":
        let histogram = try json.value("histogram").arrayValue.map { pair -> AudioLevelBand in
            let values = try pair.arrayValue
            guard values.count == 2 else { throw SpecError("histogram は [帯, 数] の組で書きます") }
            return AudioLevelBand(try specInt(values[0]), try specInt(values[1]))
        }
        return AudioLevelSummary(try json.int("frames"), try json.int("skippedFrames"), histogram)
    case let type:
        throw SpecError("知らない type です: \(type)")
    }
}

/// 記録した出来事を順に持つ。
final class RecordedEvents: DiagnosticsLog {
    private let events = SpecLocked<[any DiagnosticEvent]>([])

    func record(_ event: any DiagnosticEvent) {
        events.withLock { $0.append(event) }
    }

    var all: [any DiagnosticEvent] { events.current }
}

/// 生成の時間は実行環境で変わるので比べない。
private func timedLlm(_ input: SpecObject) async throws -> SpecJSON {
    let events = RecordedEvents()
    let reply = try input.strOrNull("reply")
    let llm = TimedLlm(FakeLlm { _, _ in
        guard let reply else { throw SpecFailure("generate-failed") }
        return reply
    }, events)
    var output: String?
    var isRethrown = false
    do {
        output = try await llm.generate(LlmRequest(try input.str("system"), try input.str("user"), try input.int("maxOutputTokens")))
    } catch is SpecFailure {
        isRethrown = true
    }
    let generated = try events.all.map { event -> SpecJSON in
        guard let generated = event as? LlmGenerated else { throw SpecError("LlmGenerated ではない記録です: \(event)") }
        return obj([
            "inputChars": generated.inputChars,
            "maxOutputTokens": generated.maxOutputTokens,
            "outputChars": generated.outputChars,
            "isFailed": generated.isFailed,
        ])
    }
    return obj(["output": output, "rethrown": isRethrown, "events": generated])
}

private func recordingGaps(_ input: SpecObject) async throws -> SpecJSON {
    let events = RecordedEvents()
    let gaps = InMemoryCaptureGapRepository()
    try await RecordingCaptureGaps(gaps, events).insert(try toGap(try input.obj("gap")))
    let saved = gaps.listOverlapping(fromMillis: Int64.min, toMillis: Int64.max)
    let recorded = try events.all.map { event -> SpecJSON in
        guard let recorded = event as? CaptureGapRecorded else { throw SpecError("CaptureGapRecorded ではない記録です: \(event)") }
        return obj(["reason": recorded.reason, "gapMillis": recorded.gapMillis])
    }
    return obj(["saved": saved.map(gapJSON), "events": recorded])
}

func toGap(_ json: SpecObject) throws -> CaptureGap {
    try CaptureGap(try json.long("startedAtMillis"), try json.long("endedAtMillis"), try json.enumValue("reason"))
}

func gapJSON(_ gap: CaptureGap) -> SpecJSON {
    obj(["startedAtMillis": gap.startedAtMillis, "endedAtMillis": gap.endedAtMillis, "reason": gap.reason])
}
