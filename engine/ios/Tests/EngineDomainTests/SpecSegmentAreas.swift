@testable import EngineDomain
import Foundation

// 発話の切り出しと、文字起こし待ちの置き場所の実行役

@Sendable func utteranceSegmenter(_ input: SpecObject) async throws -> SpecJSON {
    let sampleRateHz = try input.int("sampleRateHz")
    let frameMillis = try input.long("frameMillis")
    let segmenter = try UtteranceSegmenter(try captureConfig(try input.objOrNull("config")), sampleRateHz)
    let stride = try input.intOr("probeStride", 0)
    var position: Int64 = 0
    var results: [SpecJSON] = []
    for step in try input.objects("steps") {
        switch try step.str("op") {
        case "feed":
            var segments: [SpecJSON] = []
            for _ in 0..<(try step.long("millis") / frameMillis) {
                // フレームの音の値は、フレームの位置から決める。何番目のフレームの音かを確かめるため
                let value = Int16(truncatingIfNeeded: Int32(truncatingIfNeeded: position / frameMillis + 1))
                let samples = [Int16](repeating: value, count: Int(frameMillis * Int64(sampleRateHz) / 1_000))
                let frame = AudioFrame(startedAtMillis: position, samples: samples)
                position += frameMillis
                segments += try segmenter.accept(frame, try step.bool("isSpeech")).map { try segmentJSON($0, stride) }
            }
            results.append(obj(["segments": segments]))
        case "frame":
            let samples = (0..<(try step.int("samples"))).map { Int16(truncatingIfNeeded: $0) }
            let segments = segmenter.accept(AudioFrame(startedAtMillis: position, samples: samples), try step.bool("isSpeech"))
            position += Int64(samples.count) * 1_000 / Int64(sampleRateHz)
            results.append(obj(["segments": try segments.map { try segmentJSON($0, stride) }]))
        case "flush":
            results.append(obj(["segment": try segmenter.flush().map { try segmentJSON($0, stride) }]))
        case "discard":
            let lost = segmenter.discard().map { obj(["startedAtMillis": $0.startedAtMillis, "endedAtMillis": $0.endedAtMillis]) }
            results.append(obj(["lost": lost]))
        case "seek":
            position = try step.long("positionMillis")
            results.append(obj([:]))
        case let op:
            throw SpecError("知らない op です: \(op)")
        }
    }
    return obj(["steps": results])
}

private func segmentJSON(_ segment: SpeechSegment, _ stride: Int) throws -> SpecJSON {
    let samples = try segment.samples
    let probes = stride > 0 ? Swift.stride(from: 0, to: samples.count, by: stride).map { Int(samples[$0]) } : []
    return obj([
        "startedAtMillis": segment.startedAtMillis,
        "endedAtMillis": segment.endedAtMillis,
        "sampleCount": segment.sampleCount,
        "first": samples.first.map { Int($0) },
        "last": samples.last.map { Int($0) },
        "probes": probes,
    ])
}

@Sendable func segmentBacklog(_ input: SpecObject) async throws -> SpecJSON {
    let backlog = SegmentBacklog(try input.long("maxSamples"))
    var offered: [(id: Int64, segment: SpeechSegment)] = []
    var results: [SpecJSON] = []
    for step in try input.objects("steps") {
        switch try step.str("op") {
        case "offer":
            let start = try step.long("startedAtMillis")
            let samples = try step.int("samples")
            // 標本化周波数は 1 kHz にして、標本の数をそのままミリ秒にする
            let segment = SpeechSegment(
                startedAtMillis: start,
                endedAtMillis: start + Int64(samples),
                sampleRateHz: 1_000,
                samples: [Int16](repeating: 1, count: samples)
            )
            offered.append((try step.long("id"), segment))
            backlog.offer(segment)
            results.append(obj([:]))
        case "poll":
            let id = try backlog.poll().map { polled in
                guard let entry = offered.first(where: { $0.segment === polled }) else { throw SpecError("置いていない発話です") }
                return entry.id
            }
            results.append(obj(["id": id]))
        case "drainGaps":
            results.append(obj(["gaps": backlog.drainGaps().map(gapJSON)]))
        case "addGap":
            backlog.addGap(try toGap(try step.obj("gap")))
            results.append(obj([:]))
        case "abandonAll":
            backlog.abandonAll(try step.enumValue("reason"))
            results.append(obj([:]))
        case "close":
            backlog.close()
            results.append(obj([:]))
        case "awaitWork":
            results.append(obj(["result": try await awaitWithoutWaiting(backlog)]))
        case let op:
            throw SpecError("知らない op です: \(op)")
        }
    }
    let released = offered.filter { $0.segment.isReleased }.map(\.id).sorted()
    return obj(["steps": results, "released": released])
}

/// awaitWork がすぐに返るときだけ結果を返す。待ち続けたら実行役の失敗にする。
private func awaitWithoutWaiting(_ backlog: SegmentBacklog) async throws -> Bool {
    try await withThrowingTaskGroup(of: Bool?.self) { group in
        group.addTask { try await backlog.awaitWork() }
        group.addTask {
            try await Task.sleep(for: .seconds(1))
            return nil
        }
        let first = try await group.next()!
        group.cancelAll()
        guard let result = first else { throw SpecError("awaitWork が待ち続けました。すぐに返るときだけ使います") }
        return result
    }
}
