import Foundation

private let FULL_SCALE = 32_768.0
private let DECIBELS_PER_DECADE = 20.0
private let LEVEL_BAND_DECIBELS = 5

/// 無音の配列の大きさ。どの帯よりも下に置く。
private let SILENT_DBFS = -120.0

/**
 * これより静かなフレームは VAD に渡さない。仮の値で、実機の [AudioLevelSummary] を見て見直す。
 *
 * 1〜2 m の会話はおよそ -35〜-25 dBFS。離れた小さな声を落とさないように、それより十分に下にした。
 */
public let QUIET_GATE_DBFS = -55.0

extension AudioFrame {
    /// フレームの大きさ(RMS)を、最大の振幅を 0 とした dB で返す。
    ///
    /// log10 は Kotlin(JVM)と Swift(Darwin の libm)で最後の桁が違うことがある。
    public func levelDbfs() -> Double {
        if samples.isEmpty { return SILENT_DBFS }
        var sum = 0.0
        for sample in samples { sum += Double(sample) * Double(sample) }
        let rms = (sum / Double(samples.count)).squareRoot()
        return rms == 0 ? SILENT_DBFS : DECIBELS_PER_DECADE * log10(rms / FULL_SCALE)
    }
}

/**
 * 静かな間は VAD を動かさない。VAD のモデルは無音にも毎回計算するので、聞き取りの CPU の多くを占める。
 *
 * VAD が発話と判定している間は、静かなフレームも渡す。区切りの無音を VAD が数えるため。
 * 静かなフレームを飛ばしたあとは、VAD の状態を捨ててから次のフレームを渡す。飛ばした間の音を続きとして扱わないため。
 * 語頭が [quietDbfs] より静かでも、パイプラインが区間の前の音声を足すので、文字起こしからは落ちない。
 *
 * Swift では `Sendable` にするため、状態をロックで守る。呼び出しは Kotlin と同じく時系列で 1 つずつ行う。
 */
public final class QuietGatedVoiceActivityDetector: VoiceActivityDetector, @unchecked Sendable {
    private let delegate: any VoiceActivityDetector
    private let quietDbfs: Double
    private let levels: AudioLevelCounter?

    // isSpeaking と hasSkipped は lock で守る
    private let lock = NSLock()
    private var isSpeaking = false
    private var hasSkipped = false

    public init(_ delegate: any VoiceActivityDetector, quietDbfs: Double = QUIET_GATE_DBFS, levels: AudioLevelCounter? = nil) {
        self.delegate = delegate
        self.quietDbfs = quietDbfs
        self.levels = levels
    }

    public func isSpeech(_ frame: AudioFrame) throws -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let level = frame.levelDbfs()
        let isQuiet = level < quietDbfs
        levels?.add(level, isSkipped: isQuiet && !isSpeaking)
        if isQuiet, !isSpeaking {
            hasSkipped = true
            return false
        }
        if hasSkipped {
            delegate.reset()
            hasSkipped = false
        }
        isSpeaking = try delegate.isSpeech(frame)
        return isSpeaking
    }

    public func reset() {
        lock.lock()
        defer { lock.unlock() }
        isSpeaking = false
        hasSkipped = false
        delegate.reset()
    }
}

/// 一定の間の、フレームの大きさの分布と、VAD を飛ばした数。大きさだけで、音声の中身は持たない。
public struct AudioLevelSummary: DiagnosticEvent, Hashable {
    public let frames: Int
    public let skippedFrames: Int
    /// 帯の下端の dBFS と、その帯のフレームの数。下端の小さい順。Kotlin の `List<Pair<Int, Int>>`。
    public let histogram: [AudioLevelBand]

    public init(_ frames: Int, _ skippedFrames: Int, _ histogram: [AudioLevelBand]) {
        self.frames = frames
        self.skippedFrames = skippedFrames
        self.histogram = histogram
    }
}

/// Kotlin の `Pair<Int, Int>` の代わり。Swift のタプルは Hashable にできないので型にした。
public struct AudioLevelBand: Sendable, Hashable {
    /// 帯の下端の dBFS。Kotlin の `first`。
    public let band: Int
    /// その帯のフレームの数。Kotlin の `second`。
    public let count: Int

    public init(_ band: Int, _ count: Int) {
        self.band = band
        self.count = count
    }
}

/// [QuietGatedVoiceActivityDetector] が数え、記録の側が [drain] で取り出す。読み取りと記録は別のスレッドで動く。
public final class AudioLevelCounter: Sendable {
    private struct Counts {
        var frames = 0
        var skippedFrames = 0
        var bands: [Int: Int] = [:]
    }

    private let state = LockedState(Counts())

    public init() {}

    public func add(_ levelDbfs: Double, isSkipped: Bool) {
        let band = kotlinToInt((levelDbfs / Double(LEVEL_BAND_DECIBELS)).rounded(.down) * Double(LEVEL_BAND_DECIBELS))
        state.withLock { counts in
            counts.frames += 1
            if isSkipped { counts.skippedFrames += 1 }
            counts.bands[band, default: 0] += 1
        }
    }

    /// 数えた分を返して 0 に戻す。何も数えていなければ nil。
    public func drain() -> AudioLevelSummary? {
        state.withLock { counts in
            if counts.frames == 0 { return nil }
            let histogram = counts.bands.keys.sorted().map { AudioLevelBand($0, counts.bands[$0]!) }
            let summary = AudioLevelSummary(counts.frames, counts.skippedFrames, histogram)
            counts = Counts()
            return summary
        }
    }
}
