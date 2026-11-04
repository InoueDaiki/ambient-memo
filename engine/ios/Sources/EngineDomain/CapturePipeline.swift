import Foundation

/**
 * 音声から発話を切り出し、文字起こしを保存する。
 *
 * 読み取り側は発話区間を [SegmentBacklog] に置くだけにする。
 * 文字起こしは別の子タスクで動かすので、遅れても読み取りは止まらない。
 *
 * 音声のバッファは次のどの経路でも解放する。
 * - 文字起こしと話者の判定が終わった発話: 成功、失敗、中断を問わず [process] の `use` で解放する
 * - 追いつかずに捨てた発話: [SegmentBacklog] が解放する
 * - 中断で残った発話と語頭のバッファ: [run] の後片付けで解放する
 *
 * Swift にしたことで変わった点:
 * - Kotlin の `processingContext` はない。読み取りと文字起こしは、タスクグループの子タスクとして並行に動く。
 * - Kotlin の `coroutineScope` と同じく、文字起こし側が例外で止まったら読み取り側も取り消し、その例外を投げる。
 * - Kotlin の `NonCancellable` の後片付けは、取り消しを引き継がない別のタスクで行い、終わるまで待つ。
 * - 取り消しかどうかは、Kotlin と同じく例外の型ではなく、このタスクの状態(`Task.isCancelled`)で決める。
 * - `speakerLock` は kotlinx の Mutex の代わりに [AsyncMutex] を使う。
 */
public final class CapturePipeline: Sendable {
    private let audioSource: any AudioSource
    private let voiceActivityDetector: any VoiceActivityDetector
    private let transcriber: any Transcriber
    private let utterances: any UtteranceRepository
    private let captureGaps: any CaptureGapRepository
    private let clock: any Clock
    private let config: CaptureConfig
    private let transcriptionFilter: any TranscriptionFilter
    private let speakerIdentifier: SpeakerIdentifier?
    private let recentVoices: RecentVoices?
    private let speakerLock: AsyncMutex
    private let diagnostics: any DiagnosticsLog

    public init(
        audioSource: any AudioSource,
        voiceActivityDetector: any VoiceActivityDetector,
        transcriber: any Transcriber,
        utterances: any UtteranceRepository,
        captureGaps: any CaptureGapRepository,
        clock: any Clock,
        config: CaptureConfig = .default,
        transcriptionFilter: any TranscriptionFilter = ClosureTranscriptionFilter { _, _ in true },
        speakerIdentifier: SpeakerIdentifier? = nil,
        recentVoices: RecentVoices? = nil,
        speakerLock: AsyncMutex = AsyncMutex(),
        diagnostics: any DiagnosticsLog = DiagnosticsLogs.NONE
    ) {
        self.audioSource = audioSource
        self.voiceActivityDetector = voiceActivityDetector
        self.transcriber = transcriber
        self.utterances = utterances
        self.captureGaps = captureGaps
        self.clock = clock
        self.config = config
        self.transcriptionFilter = transcriptionFilter
        self.speakerIdentifier = speakerIdentifier
        self.recentVoices = recentVoices
        self.speakerLock = speakerLock
        self.diagnostics = diagnostics
    }

    /**
     * 音声の終わりまで処理する。
     *
     * 音声が終わるか読み取りに失敗すると、溜まった発話を処理してから戻る。失敗の例外はその後に投げる。
     * 取り消すと、未処理の音声を捨てて欠落を記録する。
     */
    public func run() async throws {
        let sampleRateHz = audioSource.sampleRateHz
        let segmenter = try UtteranceSegmenter(config, sampleRateHz)
        let backlog = SegmentBacklog(Int64(try millisToSamples(config.maxBacklogMillis, sampleRateHz)))
        let failure = LockedState<(any Error)?>(nil)
        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask { try await self.processBacklog(backlog) }
                group.addTask {
                    let captured = try await self.capture(segmenter, backlog)
                    backlog.close()
                    failure.withLock { $0 = captured }
                }
                do {
                    while try await group.next() != nil {}
                } catch {
                    group.cancelAll()
                    throw error
                }
            }
        } catch {
            await cleanUp(segmenter, backlog)
            throw error
        }
        await cleanUp(segmenter, backlog)
        if let error = failure.withLock({ $0 }) { throw error }
    }

    /// Kotlin の `withContext(NonCancellable) { abandon(...) }`。取り消しを引き継がないタスクで行い、終わるまで待つ。
    private func cleanUp(_ segmenter: UtteranceSegmenter, _ backlog: SegmentBacklog) async {
        await Task { await self.abandon(segmenter, backlog) }.value
    }

    /// 読み取りの失敗は例外を投げずに返す。溜まった発話の処理を先に終わらせるため。取り消しのときだけ投げる。
    private func capture(_ segmenter: UtteranceSegmenter, _ backlog: SegmentBacklog) async throws -> (any Error)? {
        var failure: (any Error)?
        do {
            voiceActivityDetector.reset()
            var expectedStartMillis: Int64?
            for try await frame in audioSource.frames() {
                if let expected = expectedStartMillis { detectDiscontinuity(expected, frame, segmenter, backlog) }
                let isSpeech = try voiceActivityDetector.isSpeech(frame)
                segmenter.accept(frame, isSpeech).forEach(backlog.offer)
                expectedStartMillis = frame.startedAtMillis + samplesToMillis(frame.samples.count, audioSource.sampleRateHz)
                // Kotlin の yield() と同じく、ほかのタスクに譲り、取り消しを確かめる
                await Task.yield()
                try Task.checkCancellation()
            }
            // Swift の AsyncThrowingStream は取り消されると投げずに終わるので、ここで確かめる
            try Task.checkCancellation()
        } catch {
            try Task.checkCancellation()
            failure = error
        }
        if let segment = segmenter.flush() { backlog.offer(segment) }
        return failure
    }

    private func detectDiscontinuity(
        _ expectedStartMillis: Int64,
        _ frame: AudioFrame,
        _ segmenter: UtteranceSegmenter,
        _ backlog: SegmentBacklog
    ) {
        let jumpMillis = frame.startedAtMillis - expectedStartMillis
        if abs(jumpMillis) <= config.discontinuityToleranceMillis { return }

        // 途切れの前後を1つの発話につなげない
        if let segment = segmenter.flush() { backlog.offer(segment) }
        voiceActivityDetector.reset()
        if jumpMillis > 0, let gap = try? CaptureGap(expectedStartMillis, frame.startedAtMillis, .SOURCE_DISCONTINUITY) {
            backlog.addGap(gap)
        }
    }

    private func processBacklog(_ backlog: SegmentBacklog) async throws {
        while try await backlog.awaitWork() {
            for gap in backlog.drainGaps() { try await captureGaps.insert(gap) }
            if let segment = backlog.poll() { try await process(segment, backlog) }
        }
    }

    /**
     * 1つの発話を処理する。失敗しても欠落を記録して次へ進む。音声を残さないので、やり直しはできない。
     *
     * 実装の内側のタイムアウトなどが投げた CancellationError で処理全体を止めないように、
     * 取り消しかどうかは例外の型ではなく、このタスクの状態で判断する。
     */
    private func process(_ segment: SpeechSegment, _ backlog: SegmentBacklog) async throws {
        try await segment.use { segment in
            let waitMillis = clock.nowMillis() - segment.endedAtMillis
            let started = MonotonicMark()
            let durationMillis = segment.endedAtMillis - segment.startedAtMillis
            do {
                let transcription = try await transcriber.transcribe(segment)
                let transcribeMillis = started.elapsedMillis()
                let text = transcription.text
                let isKept = !text.kotlinIsBlank && transcriptionFilter.shouldSave(text, durationMillis: durationMillis)
                let turns = isKept ? try await save(segment, transcription) : 0
                let outcome: SegmentOutcome = if isKept {
                    .SAVED
                } else if text.kotlinIsBlank {
                    .BLANK
                } else {
                    .FILTERED
                }
                let totalMillis = started.elapsedMillis()
                diagnostics.record(
                    SegmentProcessed(
                        durationMillis,
                        waitMillis,
                        transcribeMillis,
                        totalMillis - transcribeMillis,
                        text.kotlinLength,
                        turns,
                        outcome
                    )
                )
            } catch {
                let isStopping = Task.isCancelled
                let reason: CaptureGapReason = isStopping ? .PIPELINE_STOPPED : .PROCESSING_FAILED
                if let gap = try? CaptureGap(segment.startedAtMillis, segment.endedAtMillis, reason) {
                    backlog.addGap(gap)
                }
                if isStopping { throw error }
                let totalMillis = started.elapsedMillis()
                diagnostics.record(SegmentProcessed(durationMillis, waitMillis, totalMillis, 0, 0, 0, .FAILED))
            }
        }
    }

    /**
     * 判定から保存までを [speakerLock] の内側で行う。手の操作で話者が統合されても、消えた話者の行を残さない。
     * 文字起こしは含めないので、待つのは判定の間だけ。保存した行の数を返す。
     */
    private func save(_ segment: SpeechSegment, _ transcription: TimedTranscription) async throws -> Int {
        try await speakerLock.withLock {
            let createdAtMillis = clock.nowMillis()
            let turns = try await identifySpeakers(segment, transcription)
            let saved = try await utterances.insertAll(try turns.map { try toUtterance($0, transcription, createdAtMillis) })
            for (utterance, turn) in zip(saved, turns) {
                if let voice = turn.voice { recentVoices?.remember(utterance.id, voice) }
            }
            return saved.count
        }
    }

    /**
     * 話者が分からなくても文字起こしは残す。音声を残さないので、ここで諦めると文字起こしもやり直せない。
     * 取り消しかどうかは [process] と同じく、このタスクの状態で判断する。
     */
    private func identifySpeakers(_ segment: SpeechSegment, _ transcription: TimedTranscription) async throws -> [SpeakerTurn] {
        let unidentified = [SpeakerTurn(segment.startedAtMillis, segment.endedAtMillis, transcription.text, speaker: nil)]
        guard let speakerIdentifier else { return unidentified }
        do {
            return try await speakerIdentifier.identify(segment, transcription)
        } catch {
            // 例外の中身に音声由来の情報を持ち出さない
            try Task.checkCancellation()
            return unidentified
        }
    }

    private func toUtterance(_ turn: SpeakerTurn, _ transcription: TimedTranscription, _ createdAtMillis: Int64) throws -> NewUtterance {
        try NewUtterance(
            startedAtMillis: turn.startedAtMillis,
            endedAtMillis: turn.endedAtMillis,
            text: turn.text,
            confidence: transcription.transcription.confidence,
            createdAtMillis: createdAtMillis,
            speakerId: turn.speaker?.speakerId,
            speakerConfidence: turn.speaker?.confidence
        )
    }

    /// 例外やキャンセルで抜けたときに残った音声を消す。正常に終わったときは語頭のバッファだけを消す。
    private func abandon(_ segmenter: UtteranceSegmenter, _ backlog: SegmentBacklog) async {
        let lost = segmenter.discard()
        backlog.abandonAll(.PIPELINE_STOPPED)
        if let lost, let gap = try? CaptureGap(lost.startedAtMillis, lost.endedAtMillis, .PIPELINE_STOPPED) {
            backlog.addGap(gap)
        }
        do {
            for gap in backlog.drainGaps() { try await captureGaps.insert(gap) }
        } catch {
            // ここに来るのは失敗かキャンセルの途中だけ。音声はもう消してあり、欠落の記録は諦める。元の例外を隠さない
        }
    }
}
