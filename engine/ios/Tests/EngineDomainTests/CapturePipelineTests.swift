import EngineDomain
import EngineDomainTesting
import Foundation
import Testing

/// 取り消し、内側のタイムアウト、処理の遅れ、WAV の読み取りに依るものだけを確かめる。データで表せる流れは engine/spec にある。
struct CapturePipelineTests {
    private let clock = FakeClock(50_000)
    private let utterances = InMemoryUtteranceRepository()
    private let gaps = InMemoryCaptureGapRepository()

    private func pipeline(
        _ source: any AudioSource,
        _ speech: [ClosedRange<Int64>],
        transcriber: any Transcriber = FakeTranscriber(),
        captureGaps: (any CaptureGapRepository)? = nil
    ) -> CapturePipeline {
        CapturePipeline(
            audioSource: source,
            voiceActivityDetector: ScriptedVoiceActivityDetector(speech),
            transcriber: transcriber,
            utterances: utterances,
            captureGaps: captureGaps ?? gaps,
            clock: clock,
            config: pipelineConfig
        )
    }

    private func savedGaps() -> [CaptureGap] {
        gaps.listOverlapping(fromMillis: 0, toMillis: .max)
    }

    @Test func 実装の内側のタイムアウトでは処理全体を止めない() async throws {
        let (audio, speech) = bursts(2)
        let transcriber = FakeTranscriber(respond: { index, _ in
            // 取り消されていないタスクで CancellationError が届く。アダプターの内側のタイムアウトにあたる
            if index == 0 { throw CancellationError() }
            return try Transcription("次の発話")
        })

        try await pipeline(FakeAudioSource(audio), speech, transcriber: transcriber).run()

        #expect(utterances.listOverlapping(fromMillis: 0, toMillis: .max).map(\.text) == ["次の発話"])
        #expect(savedGaps().map(\.reason) == [.PROCESSING_FAILED])
    }

    @Test func WAVの発話を検知して文字起こしを保存しバッファを解放する() async throws {
        let (audio, _) = bursts(2)
        let wav = SyntheticAudio.wav(audio)
        let base = clock.nowMillis()
        let speech = [base...(base + 499), (base + 700)...(base + 1_199)]
        let transcriber = FakeTranscriber()

        try await pipeline(WavAudioSource({ wav }, clock: clock), speech, transcriber: transcriber).run()

        let saved = utterances.listOverlapping(fromMillis: 0, toMillis: .max)
        #expect(saved.map(\.text) == ["発話1", "発話2"])
        #expect(saved.map(\.startedAtMillis) == [base, base + 700])
        #expect(saved.map(\.endedAtMillis) == [base + 500, base + 1_200])
        #expect(transcriber.received.allSatisfy { $0.isReleased })
        #expect(savedGaps().isEmpty)
    }

    @Test func 追いつかないときは古い発話を捨てて欠落を記録し処理を続ける() async throws {
        let (audio, speech) = bursts(8)
        let source = SignalingAudioSource(audio, keepOpen: false)
        let transcriber = FakeTranscriber(respond: { index, _ in
            // 読み取りが終わるまで 1 つ目の処理を止めておく
            if index == 0 { await source.exhausted.wait() }
            return try Transcription("発話\(index + 1)")
        })

        try await pipeline(source, speech, transcriber: transcriber).run()

        // 1つ目は処理中で、上限の 2 秒に収まる後ろの4つが残る
        #expect(utterances.listOverlapping(fromMillis: 0, toMillis: .max).map(\.startedAtMillis) == [0, 2_800, 3_500, 4_200, 4_900])
        #expect(savedGaps() == [700, 1_400, 2_100].map { try! CaptureGap($0, $0 + 500, .PROCESSING_BACKLOG) })
        #expect(transcriber.received.allSatisfy { $0.isReleased })
    }

    @Test func 取り消すと処理中と待ちと開いている発話を捨てて欠落を記録する() async throws {
        let (audio, speech) = bursts(3)
        let started = Signal()
        let transcriber = FakeTranscriber(respond: { _, _ in
            started.signal()
            try await awaitCancellation()
        })
        let source = SignalingAudioSource(audio + SyntheticAudio.tone(100), keepOpen: true)
        let pipeline = pipeline(source, speech + [2_100...2_199], transcriber: transcriber)

        let task = Task { try await pipeline.run() }
        await started.wait()
        await source.exhausted.wait()
        task.cancel()
        let result = await task.result

        #expect(throws: CancellationError.self) { try result.get() }
        #expect(transcriber.received.count == 1)
        #expect(transcriber.received.allSatisfy { $0.isReleased })
        #expect(utterances.listOverlapping(fromMillis: 0, toMillis: .max).isEmpty)
        let expected: Set<CaptureGap> = [
            try CaptureGap(0, 500, .PIPELINE_STOPPED),
            try CaptureGap(700, 1_200, .PIPELINE_STOPPED),
            try CaptureGap(1_400, 1_900, .PIPELINE_STOPPED),
            try CaptureGap(2_100, 2_200, .PIPELINE_STOPPED),
        ]
        #expect(Set(savedGaps()) == expected)
    }

    @Test func 取り消しの途中で欠落を保存できなくても終わる() async throws {
        struct WriteFailed: Error {}
        final class FailingGaps: CaptureGapRepository {
            func insert(_ gap: CaptureGap) async throws { throw WriteFailed() }
            func listOverlapping(fromMillis: Int64, toMillis: Int64) async throws -> [CaptureGap] { [] }
        }
        let started = Signal()
        let transcriber = FakeTranscriber(respond: { _, _ in
            started.signal()
            try await awaitCancellation()
        })
        let pipeline = pipeline(FakeAudioSource(SyntheticAudio.tone(300)), [0...299], transcriber: transcriber, captureGaps: FailingGaps())

        let task = Task { try await pipeline.run() }
        await started.wait()
        task.cancel()
        _ = await task.result

        #expect(transcriber.received.count == 1)
        #expect(transcriber.received.allSatisfy { $0.isReleased })
    }

    @Test func 文字起こし側が失敗したら読み取りを止めてその例外を投げる() async throws {
        struct WriteFailed: Error {}
        final class FailingGaps: CaptureGapRepository {
            func insert(_ gap: CaptureGap) async throws { throw WriteFailed() }
            func listOverlapping(fromMillis: Int64, toMillis: Int64) async throws -> [CaptureGap] { [] }
        }
        // 時刻が飛ぶと欠落を保存しようとして失敗する
        let source = FakeAudioSource(SyntheticAudio.tone(3_000), frameStartMillis: { position, defaultMillis in
            position >= 16_000 ? defaultMillis + 5_000 : defaultMillis
        })
        let transcriber = FakeTranscriber()

        await #expect(throws: WriteFailed.self) {
            try await pipeline(source, [0...99_999], transcriber: transcriber, captureGaps: FailingGaps()).run()
        }
        #expect(transcriber.received.allSatisfy { $0.isReleased })
    }

    @Test func 処理中も読み取りは止まらない() async throws {
        let (audio, speech) = bursts(2)
        let source = SignalingAudioSource(audio, keepOpen: false)
        let transcriber = FakeTranscriber(respond: { _, _ in
            await source.exhausted.wait()
            return try Transcription("本文")
        })

        try await pipeline(source, speech, transcriber: transcriber).run()

        #expect(utterances.listOverlapping(fromMillis: 0, toMillis: .max).count == 2)
    }
}
