import EngineDomain
import Foundation

#if DEBUG
private let INPUT_FILE = "debug-input.wav"
private let FRAME_MILLIS: Int64 = 100
private let MAX_LAG_MILLIS: Int64 = 1_000
private let RIFF_HEADER_BYTES = 12
private let CHUNK_HEADER_BYTES = 8

/**
 * アプリの Documents に `debug-input.wav`(16 kHz、16 ビット、モノラル)があれば、マイクの代わりに実時間で流す。
 *
 * マイクの音が入らないシミュレーターで、本物のモデルを通して確かめるためのもの。Android の DevelopmentAudioSource と同じ。
 * 音声を書き込むコードはなく、開発者が置いたファイルを読むだけ。Release にはこのコードが入らない。
 */
func developmentAudioSource() -> (any AudioSource)? {
    guard let documents = try? FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: false)
    else { return nil }
    let file = documents.appendingPathComponent(INPUT_FILE)
    guard FileManager.default.fileExists(atPath: file.path) else { return nil }
    return DevelopmentAudioSource(file: file)
}

/// ファイルの終わりの後は無音を流し続ける。マイクと同じく、止めるまで終わらない。
private final class DevelopmentAudioSource: AudioSource {
    let sampleRateHz = SherpaSettings.SAMPLE_RATE_HZ
    private let file: URL

    init(file: URL) {
        self.file = file
    }

    func frames() -> AsyncThrowingStream<AudioFrame, any Error> {
        let state = DevelopmentReading(file: file, sampleRateHz: sampleRateHz)
        return AsyncThrowingStream(unfolding: { try await state.next() })
    }
}

private final class DevelopmentReading: @unchecked Sendable {
    private let file: URL
    private let sampleRateHz: Int
    private var samples: [Int16]?
    private var offset = 0
    private var position: SamplePosition?

    init(file: URL, sampleRateHz: Int) {
        self.file = file
        self.sampleRateHz = sampleRateHz
    }

    func next() async throws -> AudioFrame? {
        if samples == nil { samples = try Self.read(file) }
        let count = Int(Int64(sampleRateHz) * FRAME_MILLIS / 1_000)
        try await Task.sleep(nanoseconds: UInt64(FRAME_MILLIS) * 1_000_000)
        var frame = [Int16](repeating: 0, count: count)
        if let samples, offset < samples.count {
            let size = min(count, samples.count - offset)
            frame.replaceSubrange(0..<size, with: samples[offset..<offset + size])
            offset += size
        }
        let next = SamplePosition.advance(position, count: count, sampleRateHz: sampleRateHz, maxLagMillis: MAX_LAG_MILLIS)
        position = next
        return AudioFrame(startedAtMillis: next.frameStartMillis, samples: frame)
    }

    /// data チャンクまで読み進めて、サンプルを返す。形式は確かめない(開発者が置くファイルなので)。
    private static func read(_ file: URL) throws -> [Int16] {
        let bytes = [UInt8](try Data(contentsOf: file))
        var index = RIFF_HEADER_BYTES
        while index + CHUNK_HEADER_BYTES <= bytes.count {
            let id = String(decoding: bytes[index..<index + 4], as: Unicode.ASCII.self)
            let size = Int(UInt32(bytes[index + 4]) | UInt32(bytes[index + 5]) << 8 | UInt32(bytes[index + 6]) << 16 | UInt32(bytes[index + 7]) << 24)
            index += CHUNK_HEADER_BYTES
            if id == "data" {
                let end = min(index + size, bytes.count)
                return stride(from: index, to: end - 1, by: 2).map {
                    Int16(bitPattern: UInt16(bytes[$0]) | UInt16(bytes[$0 + 1]) << 8)
                }
            }
            index += size + size % 2
        }
        return []
    }
}
#else
/// Release は常にマイクを使う。
func developmentAudioSource() -> (any AudioSource)? { nil }
#endif
