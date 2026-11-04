import Foundation

// 時刻はすべて UNIX エポックからのミリ秒。Kotlin の Long は Int64、Int は Int で表す。
//
// Swift にしたことで変わった点:
// - Kotlin の `require` で値を確かめる型は、初期化が `throws` になり [IllegalArgumentException] を投げる。
// - 列挙の case の名前は Kotlin と同じ大文字にする。`rawValue` が Kotlin の `name` と同じになる。

/**
 * 16 ビットのモノラル PCM のひとかたまり。
 *
 * Kotlin では [samples] は [AudioSource] の持ち物で、collect の処理から戻ると再利用されることがある。
 * Swift の配列は値なので再利用で書き換わらない。残したい場合もコピーは要らない。
 */
public struct AudioFrame: Sendable {
    public let startedAtMillis: Int64
    public let samples: [Int16]

    public init(startedAtMillis: Int64, samples: [Int16]) {
        self.startedAtMillis = startedAtMillis
        self.samples = samples
    }
}

public struct Transcription: Sendable, Hashable {
    public let text: String
    public let confidence: Double?

    public init(_ text: String, confidence: Double? = nil) throws {
        try requireConfidence(confidence)
        self.text = text
        self.confidence = confidence
    }
}

/// 話者の特徴量。モデルが変わると比べられないので、版を一緒に持つ。
public struct SpeakerEmbedding: Sendable {
    public let vector: [Float]
    public let modelVersion: String

    public init(_ vector: [Float], _ modelVersion: String) {
        self.vector = vector
        self.modelVersion = modelVersion
    }
}

/// 保存前の発話。
public struct NewUtterance: Sendable, Hashable {
    public let startedAtMillis: Int64
    public let endedAtMillis: Int64
    public let text: String
    public let confidence: Double?
    public let createdAtMillis: Int64
    public let speakerId: Int64?
    public let speakerConfidence: Double?

    public init(
        startedAtMillis: Int64,
        endedAtMillis: Int64,
        text: String,
        confidence: Double?,
        createdAtMillis: Int64,
        speakerId: Int64? = nil,
        speakerConfidence: Double? = nil
    ) throws {
        try require(endedAtMillis >= startedAtMillis, "終了時刻が開始時刻より前です")
        try requireConfidence(confidence)
        try requireConfidence(speakerConfidence)
        self.startedAtMillis = startedAtMillis
        self.endedAtMillis = endedAtMillis
        self.text = text
        self.confidence = confidence
        self.createdAtMillis = createdAtMillis
        self.speakerId = speakerId
        self.speakerConfidence = speakerConfidence
    }
}

/// [speakerId] が nil の発話は、話者を判定していないか、判定できなかった。[confidence] は文字起こしの信頼度。
public struct Utterance: Sendable, Hashable {
    public var id: Int64
    public var startedAtMillis: Int64
    public var endedAtMillis: Int64
    public var text: String
    public var confidence: Double?
    public var createdAtMillis: Int64
    public var speakerId: Int64?
    public var speakerConfidence: Double?

    public init(
        id: Int64,
        startedAtMillis: Int64,
        endedAtMillis: Int64,
        text: String,
        confidence: Double?,
        createdAtMillis: Int64,
        speakerId: Int64? = nil,
        speakerConfidence: Double? = nil
    ) {
        self.id = id
        self.startedAtMillis = startedAtMillis
        self.endedAtMillis = endedAtMillis
        self.text = text
        self.confidence = confidence
        self.createdAtMillis = createdAtMillis
        self.speakerId = speakerId
        self.speakerConfidence = speakerConfidence
    }
}

/// 記録できなかった時間。
public struct CaptureGap: Sendable, Hashable {
    public let startedAtMillis: Int64
    public let endedAtMillis: Int64
    public let reason: CaptureGapReason

    public init(_ startedAtMillis: Int64, _ endedAtMillis: Int64, _ reason: CaptureGapReason) throws {
        try require(endedAtMillis >= startedAtMillis, "終了時刻が開始時刻より前です")
        self.startedAtMillis = startedAtMillis
        self.endedAtMillis = endedAtMillis
        self.reason = reason
    }
}

public enum CaptureGapReason: String, Sendable, Hashable, CaseIterable {
    /// 文字起こしが追いつかず、古い発話を捨てた。
    case PROCESSING_BACKLOG

    /// 文字起こしか保存に失敗した。音声は残さないので、やり直せない。
    case PROCESSING_FAILED

    /// 音声のフレームの時刻が飛んだ。
    case SOURCE_DISCONTINUITY

    /// 処理を中断したときに、未処理の音声を捨てた。
    case PIPELINE_STOPPED

    /// Kotlin の `name`。
    public var name: String { rawValue }
}

func requireConfidence(_ confidence: Double?) throws {
    try require(confidence.map { isIn($0, 0.0, 1.0) } ?? true, "信頼度は 0 から 1 の範囲です")
}
