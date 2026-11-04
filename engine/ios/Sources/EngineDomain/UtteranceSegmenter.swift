import Foundation

/**
 * 発話か無音かの判定が付いたフレームから、発話区間を切り出す。
 *
 * 持つ音声は語頭のリングバッファと、発話の上限ぶんの作業用バッファだけで、長い発話でも増えない。
 * スレッドセーフではない。
 *
 * Swift では、聞き取りのタスクと後片付けで順に使うために `@unchecked Sendable` にした。同時には使わない。
 * 作業用バッファは配列で持ち、書き換えるときもコピーが残らないように、その場で書き換える。
 */
final class UtteranceSegmenter: @unchecked Sendable {
    private let sampleRateHz: Int
    private let maxSamples: Int
    private let minSilenceSamples: Int
    private let minSpeechSamples: Int

    private let preRoll: PreRollBuffer
    private var open: [Int16]

    private var isOpen = false
    private var openSize = 0
    private var openStartedAtMillis: Int64 = 0
    private var isContinuation = false
    private var speechSamples = 0
    private var silenceRun = 0

    init(_ config: CaptureConfig, _ sampleRateHz: Int) throws {
        self.sampleRateHz = sampleRateHz
        maxSamples = try millisToSamples(config.maxUtteranceMillis, sampleRateHz)
        minSilenceSamples = try millisToSamples(config.minSilenceMillis, sampleRateHz)
        minSpeechSamples = try millisToSamples(config.minSpeechMillis, sampleRateHz)
        preRoll = PreRollBuffer(try millisToSamples(config.preRollMillis, sampleRateHz))
        open = Array(repeating: 0, count: maxSamples)
    }

    deinit {
        open.withUnsafeMutableBufferPointer { $0.update(repeating: 0) }
    }

    func accept(_ frame: AudioFrame, _ isSpeech: Bool) -> [SpeechSegment] {
        if !isOpen {
            if !isSpeech {
                preRoll.write(frame.samples, 0, frame.samples.count)
                return []
            }
            startUtterance(frame.startedAtMillis)
        }

        var segments: [SpeechSegment] = []
        var offset = 0
        while offset < frame.samples.count {
            let count = min(frame.samples.count - offset, maxSamples - openSize)
            open.withUnsafeMutableBufferPointer { open in
                frame.samples.withUnsafeBufferPointer { samples in
                    for index in 0..<count { open[openSize + index] = samples[offset + index] }
                }
            }
            openSize += count
            offset += count
            if isSpeech {
                speechSamples += count
                silenceRun = 0
            } else {
                silenceRun += count
            }
            if openSize == maxSamples {
                if let segment = splitAtLimit() { segments.append(segment) }
            }
        }
        if !isSpeech, silenceRun >= minSilenceSamples {
            if let segment = finishUtterance() { segments.append(segment) }
        }
        return segments
    }

    /// 開いている発話を閉じて返す。音声の終わりや途切れで呼ぶ。
    func flush() -> SpeechSegment? {
        isOpen ? finishUtterance() : nil
    }

    /// 持っている音声をすべて消す。発話の途中だった場合は、失われた時間を返す。
    func discard() -> CaptureSpan? {
        let lost: CaptureSpan? = if isOpen, voicedSamples() > 0 {
            CaptureSpan(openStartedAtMillis, openStartedAtMillis + samplesToMillis(voicedSamples(), sampleRateHz))
        } else {
            nil
        }
        resetOpen()
        preRoll.clear()
        return lost
    }

    private func startUtterance(_ frameStartedAtMillis: Int64) {
        openSize = preRoll.moveTo(&open)
        openStartedAtMillis = frameStartedAtMillis - samplesToMillis(openSize, sampleRateHz)
        isOpen = true
        isContinuation = false
        speechSamples = 0
        silenceRun = 0
    }

    private func splitAtLimit() -> SpeechSegment? {
        let segment = toSegment(voicedSamples())
        openStartedAtMillis += samplesToMillis(maxSamples, sampleRateHz)
        isContinuation = true
        speechSamples = 0
        clearOpen()
        openSize = 0
        return segment
    }

    private func finishUtterance() -> SpeechSegment? {
        let voiced = voicedSamples()
        // 分割の続きは短くても発話の一部なので捨てない
        let isLongEnough = isContinuation || speechSamples >= minSpeechSamples
        let segment = isLongEnough ? toSegment(voiced) : nil
        preRoll.write(open, voiced, openSize - voiced)
        resetOpen()
        return segment
    }

    /// 末尾の無音を除いた長さ。分割すると無音の一部が前の区間に入るので、バッファの長さで抑える。
    private func voicedSamples() -> Int {
        openSize - min(silenceRun, openSize)
    }

    private func toSegment(_ sampleCount: Int) -> SpeechSegment? {
        if sampleCount == 0 { return nil }
        return open.withUnsafeBufferPointer { open in
            SpeechSegment(
                startedAtMillis: openStartedAtMillis,
                endedAtMillis: openStartedAtMillis + samplesToMillis(sampleCount, sampleRateHz),
                sampleRateHz: sampleRateHz,
                copying: UnsafeBufferPointer(rebasing: open[0..<sampleCount])
            )
        }
    }

    private func clearOpen() {
        open.withUnsafeMutableBufferPointer { $0.update(repeating: 0) }
    }

    private func resetOpen() {
        clearOpen()
        openSize = 0
        isOpen = false
        isContinuation = false
        speechSamples = 0
        silenceRun = 0
    }
}

struct CaptureSpan: Sendable, Hashable {
    let startedAtMillis: Int64
    let endedAtMillis: Int64

    init(_ startedAtMillis: Int64, _ endedAtMillis: Int64) {
        self.startedAtMillis = startedAtMillis
        self.endedAtMillis = endedAtMillis
    }
}

/// 直近の音声を決まった長さだけ残すリングバッファ。
private final class PreRollBuffer {
    private var buffer: [Int16]
    private var writeIndex = 0
    private var size = 0

    init(_ capacity: Int) {
        buffer = Array(repeating: 0, count: capacity)
    }

    deinit {
        buffer.withUnsafeMutableBufferPointer { $0.update(repeating: 0) }
    }

    func write(_ source: [Int16], _ offset: Int, _ length: Int) {
        if buffer.isEmpty { return }
        let kept = min(length, buffer.count)
        for index in (offset + length - kept)..<(offset + length) {
            buffer[writeIndex] = source[index]
            writeIndex = (writeIndex + 1) % buffer.count
        }
        size = min(size + kept, buffer.count)
    }

    /// 古い順に [destination] へ移し、移した長さを返す。
    func moveTo(_ destination: inout [Int16]) -> Int {
        let moved = size
        let start = writeIndex - size
        for index in 0..<moved {
            destination[index] = buffer[floorMod(start + index, buffer.count)]
        }
        clear()
        return moved
    }

    func clear() {
        buffer.withUnsafeMutableBufferPointer { $0.update(repeating: 0) }
        writeIndex = 0
        size = 0
    }
}
