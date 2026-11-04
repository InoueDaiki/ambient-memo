import EngineDomain
import Foundation

private let MILLIS_PER_SECOND = 1_000
private let TONE_HZ = 440.0
private let TONE_AMPLITUDE = 8_000.0
private let BYTES_PER_SAMPLE = 2
private let BITS_PER_SAMPLE = 16
private let PCM_FORMAT: Int16 = 1

/// テスト用の合成音。個人の音声も、コミットする音声ファイルも使わない。
public enum SyntheticAudio {
    /// sin は JVM と Darwin の libm で最後の桁が違うことがあり、まれに 1 だけ違うサンプルができる。
    public static func tone(_ millis: Int, sampleRateHz: Int = TEST_SAMPLE_RATE_HZ) -> [Int16] {
        (0..<(millis * sampleRateHz / MILLIS_PER_SECOND)).map { index in
            // Kotlin と同じく、左から順に Double で計算して切り捨てる
            let value = sin(2 * Double.pi * TONE_HZ * Double(index) / Double(sampleRateHz)) * TONE_AMPLITUDE
            return Int16(truncatingIfNeeded: Int(value))
        }
    }

    public static func silence(_ millis: Int, sampleRateHz: Int = TEST_SAMPLE_RATE_HZ) -> [Int16] {
        Array(repeating: 0, count: millis * sampleRateHz / MILLIS_PER_SECOND)
    }

    /// 16 ビットのモノラル PCM の WAV を作る。[extraChunk] は fmt と data の間に入れる。
    public static func wav(
        _ samples: [Int16],
        sampleRateHz: Int = TEST_SAMPLE_RATE_HZ,
        channels: Int16 = 1,
        extraChunk: Data? = nil
    ) -> Data {
        var body = Data("WAVE".utf8)
        body.append(chunk("fmt ", formatChunk(sampleRateHz, channels)))
        if let extraChunk { body.append(chunk("LIST", extraChunk)) }
        body.append(chunk("data", pcmBytes(samples)))
        return chunk("RIFF", body)
    }

    private static func formatChunk(_ sampleRateHz: Int, _ channels: Int16) -> Data {
        var data = Data()
        appendLittleEndian(&data, PCM_FORMAT)
        appendLittleEndian(&data, channels)
        appendLittleEndian(&data, Int32(sampleRateHz))
        appendLittleEndian(&data, Int32(sampleRateHz * Int(channels) * BYTES_PER_SAMPLE))
        appendLittleEndian(&data, Int16(Int(channels) * BYTES_PER_SAMPLE))
        appendLittleEndian(&data, Int16(BITS_PER_SAMPLE))
        return data
    }

    private static func pcmBytes(_ samples: [Int16]) -> Data {
        var data = Data(capacity: samples.count * BYTES_PER_SAMPLE)
        for sample in samples { appendLittleEndian(&data, sample) }
        return data
    }

    private static func chunk(_ id: String, _ payload: Data) -> Data {
        var data = Data(id.utf8.prefix(4))
        appendLittleEndian(&data, Int32(payload.count))
        data.append(payload)
        if payload.count % 2 != 0 { data.append(0) }
        return data
    }

    private static func appendLittleEndian<T: FixedWidthInteger>(_ data: inout Data, _ value: T) {
        withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
    }
}
