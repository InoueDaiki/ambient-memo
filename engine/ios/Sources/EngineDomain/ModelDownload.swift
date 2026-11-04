import Foundation

public enum ModelDownloadPhase: String, Sendable, Hashable, CaseIterable {
    case DOWNLOADING, VERIFYING, EXTRACTING

    public var name: String { rawValue }
}

public enum ModelDownloadFailure: String, Sendable, Hashable, CaseIterable {
    /// 通信が切れたか、取得先が応じなかった。途中までのファイルは残し、続きから取得できる。
    case NETWORK

    /// 配布物が期待と違った。途中までのファイルは消したので、最初から取得する。
    case CORRUPTED

    /// 端末の空き容量が足りない。途中までのファイルは残す。
    case INSUFFICIENT_STORAGE

    /// それ以外の失敗。
    case UNKNOWN

    public var name: String { rawValue }
}

/// [completedBytes] / [totalBytes] は [phase] の進み具合。段階ごとに対象のバイト数が違う。
public struct ModelDownloadProgress: Sendable, Hashable {
    public let phase: ModelDownloadPhase
    public let completedBytes: Int64
    public let totalBytes: Int64

    public init(_ phase: ModelDownloadPhase, _ completedBytes: Int64, _ totalBytes: Int64) {
        self.phase = phase
        self.completedBytes = completedBytes
        self.totalBytes = totalBytes
    }
}

/// これから取得する量。[totalBytes] は配布物の大きさの合計で、[downloadedBytes] はそのうち端末にある分。
public struct ModelDownloadPlan: Sendable, Hashable {
    public let totalBytes: Int64
    public let downloadedBytes: Int64

    public init(totalBytes: Int64, downloadedBytes: Int64) {
        self.totalBytes = totalBytes
        self.downloadedBytes = downloadedBytes
    }

    /// 取得するものがない。
    public var isComplete: Bool { totalBytes == 0 }
}

/// [shortageBytes] は空き容量の不足分。容量不足を取得の前に見つけたときだけ 0 より大きい。
public struct ModelDownloadException: Error, CustomStringConvertible {
    public let failure: ModelDownloadFailure
    public let shortageBytes: Int64
    public let cause: (any Error)?

    public init(_ failure: ModelDownloadFailure, shortageBytes: Int64 = 0, cause: (any Error)? = nil) {
        self.failure = failure
        self.shortageBytes = shortageBytes
        self.cause = cause
    }

    /// Kotlin の例外の文言と同じく、失敗の名前にする。
    public var message: String { failure.name }

    public var description: String { message }
}

/// 取得の状態。止まっているときに取得するものが残っているかは [ModelDownloadPlan] で見る。
public enum ModelDownloadState: Sendable, Hashable {
    case Idle

    case Running(progress: ModelDownloadProgress)

    case Failed(failure: ModelDownloadFailure, shortageBytes: Int64 = 0)
}
