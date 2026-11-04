import Foundation

/// 自分の声の登録の既定値。仮で、端末のマイクで見直す。Swift の既定値は [default] にある。
public struct EnrollmentConfig: Sendable, Hashable {
    /// 平均する発話の数。
    public let requiredSamples: Int
    /// これより短い区間は登録に使わない。
    public let minSampleMillis: Int64
    /// 次の発話が使えるまで、これだけ聞いても集まらなければ諦める。
    public let maxListeningMillis: Int64

    public init(
        requiredSamples: Int = DEFAULT_REQUIRED_SAMPLES,
        minSampleMillis: Int64 = DEFAULT_MIN_SAMPLE_MILLIS,
        maxListeningMillis: Int64 = DEFAULT_MAX_LISTENING_MILLIS
    ) throws {
        try require(requiredSamples > 0, "requiredSamples は正の値です")
        try require(minSampleMillis >= 0, "minSampleMillis は 0 以上です")
        try require(maxListeningMillis > 0, "maxListeningMillis は正の値です")
        self.requiredSamples = requiredSamples
        self.minSampleMillis = minSampleMillis
        self.maxListeningMillis = maxListeningMillis
    }

    /// Kotlin の `EnrollmentConfig()`。
    public static let `default` = try! EnrollmentConfig()

    @usableFromInline static let DEFAULT_REQUIRED_SAMPLES = 3

    // 実音声で 1.5 秒ほど。検証では 3 秒の EER が 0.40%、1.5 秒が 1.40%
    @usableFromInline static let DEFAULT_MIN_SAMPLE_MILLIS: Int64 = 3_000
    @usableFromInline static let DEFAULT_MAX_LISTENING_MILLIS: Int64 = 45_000
}

/// 登録の途中の知らせ。音声も特徴量も含まない。
public struct EnrollmentProgress: Sendable, Hashable {
    /// ここまでに使えた発話の数。
    public let acceptedSamples: Int
    /// 話した発話が、短いなどの理由で使えなかった。
    public let isRejected: Bool

    public init(_ acceptedSamples: Int, isRejected: Bool = false) {
        self.acceptedSamples = acceptedSamples
        self.isRejected = isRejected
    }
}

public enum EnrollmentOutcome: String, Sendable, Hashable, CaseIterable {
    /// 自分の特徴量を記録した。
    case ENROLLED

    /// 時間内に発話が足りなかった。何も記録していない。
    case NOT_ENOUGH_SPEECH

    public var name: String { rawValue }
}

/**
 * 自分の声を聞き取り、複数の発話の特徴量の平均を自分として記録する。
 *
 * 音声は [UtteranceSegmenter] の作業用バッファと発話の [SpeechSegment] だけに持ち、特徴量を作ったら捨てる。
 * 例外やキャンセルでは何も記録しない。特徴量が揃うまで保存しないので、途中で止めても前の登録は残る。
 */
public final class SelfEnrollment: Sendable {
    private let audioSource: any AudioSource
    private let voiceActivityDetector: any VoiceActivityDetector
    private let embedder: any SpeakerEmbedder
    private let speakers: any SpeakerRepository
    private let clock: any Clock
    private let captureConfig: CaptureConfig
    private let config: EnrollmentConfig
    private let onProgress: @Sendable (EnrollmentProgress) -> Void

    public init(
        audioSource: any AudioSource,
        voiceActivityDetector: any VoiceActivityDetector,
        embedder: any SpeakerEmbedder,
        speakers: any SpeakerRepository,
        clock: any Clock,
        captureConfig: CaptureConfig = .default,
        config: EnrollmentConfig = .default,
        onProgress: @escaping @Sendable (EnrollmentProgress) -> Void = { _ in }
    ) {
        self.audioSource = audioSource
        self.voiceActivityDetector = voiceActivityDetector
        self.embedder = embedder
        self.speakers = speakers
        self.clock = clock
        self.captureConfig = captureConfig
        self.config = config
        self.onProgress = onProgress
    }

    public func run() async throws -> EnrollmentOutcome {
        let sampleRateHz = audioSource.sampleRateHz
        let segmenter = try UtteranceSegmenter(captureConfig, sampleRateHz)
        let maxSamples = config.maxListeningMillis * Int64(sampleRateHz) / MILLIS_PER_SECOND
        var vectors: [[Float]] = []
        do {
            defer { _ = segmenter.discard() }
            voiceActivityDetector.reset()
            var heardSamples: Int64 = 0
            // Kotlin の takeWhile と同じく、条件を満たさなくなったフレームまで処理して止める
            for try await frame in audioSource.frames() {
                try Task.checkCancellation()
                let isSpeech = try voiceActivityDetector.isSpeech(frame)
                let accepted = vectors.count
                try await embedAll(segmenter.accept(frame, isSpeech), &vectors)
                heardSamples = vectors.count > accepted ? 0 : heardSamples + Int64(frame.samples.count)
                if !(vectors.count < config.requiredSamples && heardSamples < maxSamples) { break }
            }
            // Swift の AsyncThrowingStream は取り消されると投げずに終わるので、ここで確かめる
            try Task.checkCancellation()
            if vectors.count < config.requiredSamples {
                try await embedAll([segmenter.flush()].compactMap { $0 }, &vectors)
            }
        }
        return try await record(vectors)
    }

    /// 途中で失敗しても、残りの発話を含めてすべて解放する。
    private func embedAll(_ segments: [SpeechSegment], _ vectors: inout [[Float]]) async throws {
        defer { segments.forEach { $0.close() } }
        for segment in segments {
            try await accept(segment, &vectors)
        }
    }

    private func accept(_ segment: SpeechSegment, _ vectors: inout [[Float]]) async throws {
        let vector = try await vectorOf(segment)
        let millis = segment.endedAtMillis - segment.startedAtMillis
        if let vector {
            vectors.append(vector)
            onProgress(EnrollmentProgress(vectors.count))
        } else if millis >= config.minSampleMillis / 2 {
            // 咳や物音まで読み直しを頼まない
            onProgress(EnrollmentProgress(vectors.count, isRejected: true))
        }
    }

    private func vectorOf(_ segment: SpeechSegment) async throws -> [Float]? {
        let isLongEnough = segment.endedAtMillis - segment.startedAtMillis >= config.minSampleMillis
        guard isLongEnough, let embedding = try await embedder.embed(segment) else { return nil }
        return normalizedOrNull(embedding.vector)
    }

    private func record(_ vectors: [[Float]]) async throws -> EnrollmentOutcome {
        // Kotlin と同じく、数を確かめる前に平均を求める(次元が違えば投げる)
        let mean = try meanOf(vectors)
        guard vectors.count >= config.requiredSamples, let mean else {
            return .NOT_ENOUGH_SPEECH
        }
        _ = try await speakers.saveSelfEmbedding(
            SpeakerEmbedding(mean, embedder.modelVersion),
            sampleCount: vectors.count,
            nowMillis: clock.nowMillis()
        )
        return .ENROLLED
    }
}
