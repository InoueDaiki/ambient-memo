import EngineDomain
import Foundation

private let MILLIS_PER_SECOND: Int64 = 1_000
private let BYTES_PER_SAMPLE = 2
private let BITS_PER_SAMPLE = 16
private let FORMAT_PCM = 1
private let RIFF_HEADER_BYTES = 12
private let CHUNK_HEADER_BYTES = 8
private let FMT_CHUNK_MIN_BYTES: Int64 = 16
private let ID_BYTES = 4
private let SIZE_OFFSET = 4
private let CHANNELS_OFFSET = 2
private let SAMPLE_RATE_OFFSET = 4
private let BITS_PER_SAMPLE_OFFSET = 14

/// ファイルが途中で終わっている。Java の EOFException にあたる。
public struct EOFException: Error, Equatable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/**
 * 16 ビットのモノラル PCM の WAV ファイルを、[AudioSource] として流す。
 *
 * マイクの代わりに結合テストで使う。実時間には合わせず、読めるだけ速く流す。
 * 時刻は読み取りの開始時の [clock] を起点にして、サンプル数から計算する。
 *
 * Kotlin は InputStream を少しずつ読む。Swift では [open] がファイルの中身を Data で返し、それを少しずつ読む。
 * 形式の誤りは、Kotlin と同じく読み取りを始めたときに投げる(`IllegalArgumentException` か [EOFException])。
 */
public final class WavAudioSource: AudioSource {
    private let open: @Sendable () throws -> Data
    private let clock: any Clock
    public let sampleRateHz: Int
    private let frameSamples: Int

    public init(
        _ open: @escaping @Sendable () throws -> Data,
        clock: any Clock,
        sampleRateHz: Int = TEST_SAMPLE_RATE_HZ,
        frameSamples: Int = TEST_FRAME_SAMPLES
    ) {
        self.open = open
        self.clock = clock
        self.sampleRateHz = sampleRateHz
        self.frameSamples = frameSamples
    }

    public convenience init(path: URL, clock: any Clock, sampleRateHz: Int = TEST_SAMPLE_RATE_HZ) {
        self.init({ try Data(contentsOf: path) }, clock: clock, sampleRateHz: sampleRateHz)
    }

    private struct Reading {
        let input: ByteReader
        let remaining: Int64
        let startMillis: Int64
        var readBytes: Int64 = 0
        var position: Int64 = 0
    }

    public func frames() -> AsyncThrowingStream<AudioFrame, any Error> {
        let reading = Locked<Reading?>(nil)
        return AsyncThrowingStream(unfolding: { [self] in
            try reading.withLock { reading -> AudioFrame? in
                if reading == nil {
                    let input = ByteReader(try open())
                    let remaining = try readDataChunkSize(input, sampleRateHz)
                    reading = Reading(input: input, remaining: remaining, startMillis: clock.nowMillis())
                }
                guard var current = reading, current.readBytes < current.remaining else { return nil }
                let wanted = Int(min(Int64(frameSamples * BYTES_PER_SAMPLE), current.remaining - current.readBytes))
                let bytes = current.input.readUpTo(wanted)
                let sampleCount = bytes.count / BYTES_PER_SAMPLE
                if sampleCount == 0 {
                    current.readBytes = current.remaining
                    reading = current
                    return nil
                }
                let samples = (0..<sampleCount).map { index in
                    Int16(bitPattern: UInt16(bytes[index * 2]) | UInt16(bytes[index * 2 + 1]) << 8)
                }
                let frame = AudioFrame(
                    startedAtMillis: current.startMillis + current.position * MILLIS_PER_SECOND / Int64(sampleRateHz),
                    samples: samples
                )
                current.readBytes += Int64(bytes.count)
                current.position += Int64(sampleCount)
                reading = current
                return frame
            }
        })
    }
}

/// Data を前から読む。Java の InputStream の readNBytes と skipNBytes にあたる。
final class ByteReader: @unchecked Sendable {
    private let data: [UInt8]
    private var offset = 0

    init(_ data: Data) {
        self.data = [UInt8](data)
    }

    /// 最大 [count] バイトを読む。終わりに達したら短くなる。
    func readUpTo(_ count: Int) -> [UInt8] {
        let end = min(offset + count, data.count)
        let bytes = Array(data[offset..<end])
        offset = end
        return bytes
    }

    func readExactly(_ count: Int) throws -> [UInt8] {
        let bytes = readUpTo(count)
        if bytes.count < count { throw EOFException("WAV ファイルの途中で終わっています") }
        return bytes
    }

    func skip(_ count: Int64) throws {
        guard Int64(data.count - offset) >= count else {
            offset = data.count
            throw EOFException("WAV ファイルの途中で終わっています")
        }
        offset += Int(count)
    }
}

/// ヘッダーを確かめ、data チャンクの先頭まで読み進めて、そのバイト数を返す。
private func readDataChunkSize(_ input: ByteReader, _ expectedSampleRateHz: Int) throws -> Int64 {
    let riff = try input.readExactly(RIFF_HEADER_BYTES)
    guard id(riff, 0) == "RIFF", id(riff, RIFF_HEADER_BYTES - ID_BYTES) == "WAVE" else {
        throw IllegalArgumentException("WAV ファイルではありません")
    }

    var hasFormat = false
    while true {
        let header = try input.readExactly(CHUNK_HEADER_BYTES)
        let size = Int64(UInt32(littleEndian: header, SIZE_OFFSET))
        switch id(header, 0) {
        case "fmt ":
            guard size >= FMT_CHUNK_MIN_BYTES else { throw IllegalArgumentException("fmt チャンクが短すぎます") }
            try checkFormat(try input.readExactly(Int(size)), expectedSampleRateHz)
            hasFormat = true
        case "data":
            guard hasFormat else { throw IllegalArgumentException("data チャンクの前に fmt チャンクがありません") }
            return size
        default:
            try input.skip(size)
        }
        // チャンクは偶数バイトに揃えられている
        if size % 2 != 0 { try input.skip(1) }
    }
}

private func checkFormat(_ chunk: [UInt8], _ expectedSampleRateHz: Int) throws {
    guard Int(Int16(littleEndian: chunk, 0)) == FORMAT_PCM else { throw IllegalArgumentException("PCM ではありません") }
    guard Int16(littleEndian: chunk, CHANNELS_OFFSET) == 1 else { throw IllegalArgumentException("モノラルではありません") }
    guard Int(Int16(littleEndian: chunk, BITS_PER_SAMPLE_OFFSET)) == BITS_PER_SAMPLE else {
        throw IllegalArgumentException("16 ビットではありません")
    }
    let sampleRateHz = Int(Int32(bitPattern: UInt32(littleEndian: chunk, SAMPLE_RATE_OFFSET)))
    guard sampleRateHz == expectedSampleRateHz else {
        throw IllegalArgumentException("サンプリング周波数が \(sampleRateHz) Hz です")
    }
}

private func id(_ bytes: [UInt8], _ offset: Int) -> String {
    String(decoding: bytes[offset..<offset + ID_BYTES], as: Unicode.ASCII.self)
}

extension UInt32 {
    fileprivate init(littleEndian bytes: [UInt8], _ offset: Int) {
        self = UInt32(bytes[offset]) | UInt32(bytes[offset + 1]) << 8 | UInt32(bytes[offset + 2]) << 16 |
            UInt32(bytes[offset + 3]) << 24
    }
}

extension Int16 {
    fileprivate init(littleEndian bytes: [UInt8], _ offset: Int) {
        self = Int16(bitPattern: UInt16(bytes[offset]) | UInt16(bytes[offset + 1]) << 8)
    }
}
