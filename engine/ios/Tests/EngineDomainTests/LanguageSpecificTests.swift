@testable import EngineDomain
import EngineDomainTesting
import Foundation
import Testing

/// 取り消しと並行に依るもの、Swift にしたことで足した部品を確かめる。
struct SelfEnrollmentTests {
    @Test func 取り消すと音声を解放し前の登録を残す() async throws {
        let speakers = InMemorySpeakerRepository()
        let previous = SpeakerEmbedding([1, 0], "fake-1")
        _ = speakers.saveSelfEmbedding(previous, sampleCount: 3, nowMillis: 0)
        let (audio, speech) = bursts(2)
        let started = Signal()
        let embedder = FakeSpeakerEmbedder(modelVersion: "fake-1") { _, _ in
            started.signal()
            try await awaitCancellation()
        }
        let enrollment = SelfEnrollment(
            audioSource: FakeAudioSource(audio),
            voiceActivityDetector: ScriptedVoiceActivityDetector(speech),
            embedder: embedder,
            speakers: speakers,
            clock: FakeClock(9_000),
            captureConfig: try CaptureConfig(maxUtteranceMillis: 1_000, minSilenceMillis: 100, preRollMillis: 0, minSpeechMillis: 0),
            config: try EnrollmentConfig(requiredSamples: 2, minSampleMillis: 400, maxListeningMillis: 5_000)
        )

        let task = Task { try await enrollment.run() }
        await started.wait()
        task.cancel()
        _ = await task.result

        #expect(embedder.received.allSatisfy { $0.isReleased })
        #expect(try speakers.listEmbeddings(modelVersion: "fake-1", limit: 10).map(\.vector) == [previous.vector])
    }
}

struct SegmentBacklogTests {
    @Test func 発話が届くまで待ち閉じると終わる() async throws {
        let backlog = SegmentBacklog(1_000)
        let waiting = Task { try await backlog.awaitWork() }
        backlog.offer(SpeechSegment(startedAtMillis: 0, endedAtMillis: 10, sampleRateHz: 16_000, samples: [1, 2]))
        #expect(try await waiting.value)
        #expect(backlog.poll() != nil)

        let closing = Task { try await backlog.awaitWork() }
        backlog.close()
        #expect(try await closing.value == false)
    }

    @Test func 待っている間に取り消すと投げる() async throws {
        let backlog = SegmentBacklog(1_000)
        let waiting = Task { try await backlog.awaitWork() }
        waiting.cancel()
        await #expect(throws: CancellationError.self) { try await waiting.value }
    }

    @Test func あふれたら古い発話を解放して欠落にする() throws {
        let backlog = SegmentBacklog(3)
        let first = SpeechSegment(startedAtMillis: 0, endedAtMillis: 10, sampleRateHz: 16_000, samples: [1, 2])
        backlog.offer(first)
        backlog.offer(SpeechSegment(startedAtMillis: 20, endedAtMillis: 30, sampleRateHz: 16_000, samples: [3, 4]))
        #expect(first.isReleased)
        #expect(backlog.drainGaps() == [try CaptureGap(0, 10, .PROCESSING_BACKLOG)])
    }
}

struct SpeechSegmentTests {
    @Test func 閉じると読めなくなる() throws {
        let segment = SpeechSegment(startedAtMillis: 0, endedAtMillis: 1, sampleRateHz: 16_000, samples: [5, 6, 7])
        #expect(try segment.samples == [5, 6, 7])
        segment.close()
        #expect(segment.isReleased)
        #expect(segment.sampleCount == 3)
        #expect(throws: IllegalStateException.self) { try segment.samples }
    }
}

struct AsyncMutexTests {
    @Test func 同時に1つだけが中に入る() async throws {
        let mutex = AsyncMutex()
        let state = LockedState((inside: 0, maxInside: 0))
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<20 {
                group.addTask {
                    try await mutex.withLock {
                        state.withLock { $0.inside += 1; $0.maxInside = max($0.maxInside, $0.inside) }
                        await Task.yield()
                        state.withLock { $0.inside -= 1 }
                    }
                }
            }
            try await group.waitForAll()
        }
        #expect(state.withLock { $0.maxInside } == 1)
    }

    @Test func 待っている間に取り消すと投げる() async throws {
        let mutex = AsyncMutex()
        let entered = Signal()
        let holder = Task {
            try await mutex.withLock {
                entered.signal()
                try await awaitCancellation()
            }
        }
        await entered.wait()
        let waiter = Task { try await mutex.withLock { 1 } }
        waiter.cancel()
        await #expect(throws: CancellationError.self) { try await waiter.value }
        holder.cancel()
        _ = await holder.result
        #expect(try await mutex.withLock { 2 } == 2)
    }
}

/// 共通の JSON テストが入るまでの、移植の確かめ。Kotlin の文字列と正規表現の扱いに合わせたところを見る。
struct KotlinCompatibilityTests {
    @Test func trimはKotlinと同じ空白を除く() {
        #expect("\u{3000}\u{00A0}a\u{2028}\u{001C}".kotlinTrim() == "a")
        #expect("\u{0085}a\u{FEFF}".kotlinTrim() == "\u{0085}a\u{FEFF}")
    }

    @Test func linesはCRLFとCRとLFで分ける() {
        #expect("a\r\nb\rc\nd\u{2028}e\n".kotlinLines() == ["a", "b", "c", "d\u{2028}e", ""])
    }

    @Test func 文字はUTF16の単位で数える() {
        #expect("𠮷野".kotlinLength == 3)
        #expect("𠮷野".kotlinCountLetterOrDigit() == 1)
        // 𠮷 はサロゲートの対なので数えず、2 文字の長い区間として捨てる
        #expect(!NoiseTextFilter().shouldSave("𠮷野家", durationMillis: 3_000))
    }

    @Test func 正規表現の空白と数字はASCIIだけ() {
        #expect(CalendarDate.parse("２０２６-01-05") == nil)
        #expect(cleanTitle("タイトル\u{3000}: x") == "タイトル\u{3000}: x")
        #expect(cleanTitle("タイトル : 「x」") == "x")
        #expect(parseSearchPlan("語\t: 会議")?.terms == ["会議"])
        #expect(parseSearchPlan("語\u{3000}: 会議") == nil)
    }

    @Test func 答えの番号は_Int_に収まらなければ読まない() {
        #expect(parseAnswer("答え [99999999999] [1]", 1) == ParsedAnswer("答え", [1]))
    }

    @Test func 語は_UTF_16_の単位で重ねない() {
        // Swift の == は正規化で比べるので、合成済みの「が」と分解した「か」+濁点を同じとみなす。Kotlin は別の語にする
        #expect(parseSearchPlan("語: が行, か\u{3099}行")?.terms == ["が行", "か\u{3099}行"])
    }

    @Test func 閉じていないコードブロックと考えを外す() {
        #expect("\n\n```json\n<think>x".withoutWrapping() == "")
        #expect(parseSummary("<think>a</think>\n- 一つ目\n* 二つ目") == "- 一つ目\n- 二つ目")
    }

    @Test func 日付は暦で計算する() throws {
        #expect(try CalendarDate(2028, 2, 28).plusDays(1) == (try CalendarDate(2028, 2, 29)))
        #expect(try CalendarDate(2026, 11, 2).weekdayIndex == 0)
        #expect(throws: IllegalArgumentException.self) { try CalendarDate(2026, 2, 29) }
        #expect(throws: IllegalArgumentException.self) { try CalendarDate(9_999, 12, 31).plusDays(1) }
        #expect(try CalendarDate.of(1_793_547_000_000, TimeZone(identifier: "Asia/Tokyo")!) == (try CalendarDate(2026, 11, 2)))
    }

    @Test func 範囲外の値は落ちずに投げる() {
        #expect(throws: IllegalArgumentException.self) { try SpeakerConfig(selfThreshold: -1) }
        #expect(throws: ArithmeticException.self) { try UtteranceSegmenter(try CaptureConfig(maxUtteranceMillis: 1 << 40, maxBacklogMillis: 1 << 40), 16_000) }
        #expect(scoreBucket(.nan) == 0)
    }
}

struct OrganizeJobTests {
    @Test func 発話をメモにまとめる() async throws {
        let utterances = InMemoryUtteranceRepository()
        let start: Int64 = 1_793_547_000_000
        _ = utterances.insert(try NewUtterance(startedAtMillis: start, endedAtMillis: start + 1_000, text: "明日、牛乳を買う", confidence: nil, createdAtMillis: 0))
        let memos = InMemoryMemoRepository(utterances: utterances)
        let llm = FakeLlm.organizing(
            summary: "- 牛乳を買う",
            title: "買い物"
        )
        let job = OrganizeJob(
            memos: memos,
            speakers: InMemorySpeakerRepository(utterances: utterances),
            llm: llm,
            clock: FakeClock(start + 10 * 60 * 1_000),
            timeZone: TimeZone(identifier: "Asia/Tokyo")!
        )

        let result = try await job.run()

        #expect(result == OrganizeResult(.COMPLETED, 1))
        let memo = try #require(try await memos.listMemos().first)
        #expect(memo.title == "買い物")
        #expect(memo.summary == "- 牛乳を買う")
    }

    @Test func 取り消すと回数を戻して投げる() async throws {
        let utterances = InMemoryUtteranceRepository()
        _ = utterances.insert(try NewUtterance(startedAtMillis: 0, endedAtMillis: 1_000, text: "本文", confidence: nil, createdAtMillis: 0))
        let attempts = InMemoryOrganizeAttempts()
        let started = Signal()
        let llm = FakeLlm { _, _ in
            started.signal()
            try await awaitCancellation()
        }
        let job = OrganizeJob(
            memos: InMemoryMemoRepository(utterances: utterances),
            speakers: InMemorySpeakerRepository(utterances: utterances),
            llm: llm,
            clock: FakeClock(10_000_000),
            timeZone: TimeZone(identifier: "UTC")!,
            attempts: attempts
        )

        let task = Task { try await job.run() }
        await started.wait()
        task.cancel()

        await #expect(throws: CancellationError.self) { try await task.value }
        #expect(attempts.count(firstUtteranceId: 1) == 0)
    }
}

/// 質問の、言語に依る挙動。データで表す挙動は memo-question.json にある。
struct MemoQuestionTests {
    @Test func 取り消すと答えずに投げる() async throws {
        let utterances = InMemoryUtteranceRepository()
        let started = Signal()
        let llm = FakeLlm { _, _ in
            started.signal()
            try await awaitCancellation()
        }
        let answerer = MemoQuestionAnswerer(
            memos: InMemoryMemoRepository(utterances: utterances),
            speakers: InMemorySpeakerRepository(utterances: utterances),
            llm: llm,
            clock: FakeClock(1_793_547_000_000),
            timeZone: TimeZone(identifier: "Asia/Tokyo")!
        )

        let task = Task { try await answerer.answer("歯医者はいつ?") }
        await started.wait()
        task.cancel()

        await #expect(throws: CancellationError.self) { try await task.value }
        #expect(llm.received.count == 1)
    }
}
