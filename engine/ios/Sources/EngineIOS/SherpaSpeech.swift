import EngineDomain
import Foundation
import SherpaOnnxC

// sherpa-onnx の C API でポートを実装する。設定と挙動は Android の NativeSpeech.kt と AndroidSpeech.kt と同じにする。
// 音声の作業用の配列は使い終わったら 0 で塗りつぶし、ディスクには書かない。

private let FULL_SCALE: Float = 32_768

/// C の文字列を設定の作成が終わるまで持つ。sherpa-onnx は作成の中で値を写すので、その後は解放してよい。
private final class CStrings {
    private var pointers: [UnsafeMutablePointer<CChar>] = []

    func make(_ value: String) -> UnsafePointer<CChar> {
        let pointer = strdup(value)!
        pointers.append(pointer)
        return UnsafePointer(pointer)
    }

    deinit {
        for pointer in pointers { free(pointer) }
    }
}

/// ネイティブを作れなかった。モデルのファイルが読めないときなど。
public struct SherpaInitializationError: Error, CustomStringConvertible {
    public let description: String
}

/// 発話か無音かを、sherpa-onnx の silero VAD の判定のままにする。呼び出しは時系列で 1 つずつ行う。
public final class SherpaVoiceActivityDetector: VoiceActivityDetector, @unchecked Sendable {
    private let lock = NSLock()
    private var vad: OpaquePointer?

    public init(modelPath: String) throws {
        let strings = CStrings()
        var config = SherpaOnnxVadModelConfig()
        config.silero_vad.model = strings.make(modelPath)
        config.silero_vad.threshold = SherpaSettings.VAD_THRESHOLD
        config.silero_vad.min_silence_duration = SherpaSettings.VAD_MIN_SILENCE_SECONDS
        config.silero_vad.min_speech_duration = SherpaSettings.VAD_MIN_SPEECH_SECONDS
        config.silero_vad.window_size = SherpaSettings.VAD_WINDOW_SAMPLES
        config.silero_vad.max_speech_duration = SherpaSettings.VAD_MAX_SPEECH_SECONDS
        config.sample_rate = Int32(SherpaSettings.SAMPLE_RATE_HZ)
        config.num_threads = SherpaSettings.VAD_THREADS
        config.provider = strings.make("cpu")
        guard let created = SherpaOnnxCreateVoiceActivityDetector(&config, SherpaSettings.VAD_BUFFER_SECONDS) else {
            throw SherpaInitializationError(description: "VAD を作れません")
        }
        vad = created
    }

    public func isSpeech(_ frame: AudioFrame) throws -> Bool {
        var samples = frame.samples.map { Float($0) / FULL_SCALE }
        defer { zeroFill(&samples) }
        return try lock.withLock {
            guard let vad else { throw IllegalStateException("解放済みです") }
            samples.withUnsafeBufferPointer {
                SherpaOnnxVoiceActivityDetectorAcceptWaveform(vad, $0.baseAddress, Int32($0.count))
            }
            // 区切りは domain で決めるので、切り出された区間は捨てる。捨てないとネイティブ側に音声が溜まる
            while SherpaOnnxVoiceActivityDetectorEmpty(vad) == 0 { SherpaOnnxVoiceActivityDetectorPop(vad) }
            return SherpaOnnxVoiceActivityDetectorDetected(vad) != 0
        }
    }

    public func reset() {
        lock.withLock {
            if let vad { SherpaOnnxVoiceActivityDetectorReset(vad) }
        }
    }

    public func close() {
        lock.withLock {
            if let vad { SherpaOnnxDestroyVoiceActivityDetector(vad) }
            vad = nil
        }
    }

    deinit { close() }
}

/// 認識の結果。[timestampSeconds] は渡した音声の先頭からの、[tokens] の開始時刻。
struct NativeRecognition {
    let text: String
    let tokens: [String]
    let timestampSeconds: [Float]
}

/// オフラインの認識器。Android の AndroidRecognizer と同じ設定にする。
final class SherpaRecognizer {
    private let recognizer: OpaquePointer

    init(store: ModelStore, model: AsrModel) throws {
        let strings = CStrings()
        var config = SherpaOnnxOfflineRecognizerConfig()
        config.feat_config.sample_rate = Int32(SherpaSettings.SAMPLE_RATE_HZ)
        config.feat_config.feature_dim = SherpaSettings.ASR_FEATURE_DIM
        config.decoding_method = strings.make(SherpaSettings.ASR_DECODING_METHOD)
        config.max_active_paths = SherpaSettings.ASR_MAX_ACTIVE_PATHS
        config.hotwords_score = SherpaSettings.ASR_HOTWORDS_SCORE
        config.model_config.num_threads = SherpaSettings.ASR_THREADS
        config.model_config.provider = strings.make("cpu")
        let package = model.modelPackage
        switch model {
        case let .transducer(_, encoder, decoder, joiner, tokens):
            config.model_config.tokens = strings.make(store.pathOf(package, tokens))
            config.model_config.transducer.encoder = strings.make(store.pathOf(package, encoder))
            config.model_config.transducer.decoder = strings.make(store.pathOf(package, decoder))
            config.model_config.transducer.joiner = strings.make(store.pathOf(package, joiner))
        case let .senseVoice(_, file, tokens):
            config.model_config.tokens = strings.make(store.pathOf(package, tokens))
            config.model_config.sense_voice.model = strings.make(store.pathOf(package, file))
            config.model_config.sense_voice.language = strings.make(SherpaSettings.ASR_LANGUAGE)
            config.model_config.sense_voice.use_itn = 1
        }
        guard let created = SherpaOnnxCreateOfflineRecognizer(&config) else {
            throw SherpaInitializationError(description: "認識器を作れません")
        }
        recognizer = created
    }

    func decode(_ samples: [Float], sampleRateHz: Int) throws -> NativeRecognition {
        guard let stream = SherpaOnnxCreateOfflineStream(recognizer) else {
            throw SherpaInitializationError(description: "ストリームを作れません")
        }
        defer { SherpaOnnxDestroyOfflineStream(stream) }
        samples.withUnsafeBufferPointer {
            SherpaOnnxAcceptWaveformOffline(stream, Int32(sampleRateHz), $0.baseAddress, Int32($0.count))
        }
        SherpaOnnxDecodeOfflineStream(recognizer, stream)
        guard let result = SherpaOnnxGetOfflineStreamResult(stream) else {
            throw SherpaInitializationError(description: "結果を読めません")
        }
        defer { SherpaOnnxDestroyOfflineRecognizerResult(result) }
        let value = result.pointee
        let text = value.text.map { String(cString: $0) } ?? ""
        let count = Int(value.count)
        var tokens: [String] = []
        if let array = value.tokens_arr {
            tokens = (0..<count).compactMap { array[$0].map { String(cString: $0) } }
        }
        var timestamps: [Float] = []
        if let pointer = value.timestamps {
            timestamps = Array(UnsafeBufferPointer(start: pointer, count: count))
        }
        return NativeRecognition(text: text, tokens: tokens, timestampSeconds: timestamps)
    }

    deinit {
        SherpaOnnxDestroyOfflineRecognizer(recognizer)
    }
}

/**
 * 発話の前後に無音を足して文字にする。Android の SherpaTranscriber と同じ。
 *
 * モデルは大きいので、聞き取りの開始ではなく最初の発話で読み込む。ネイティブの処理は止められないので、
 * 取り消しても途中では戻らない。
 */
public final class SherpaTranscriber: Transcriber, @unchecked Sendable {
    private let paddingMillis: Int64
    private let createRecognizer: () throws -> SherpaRecognizer
    private let lock = NSLock()
    private var recognizer: SherpaRecognizer?
    private var closed = false

    init(paddingMillis: Int64 = SherpaSettings.PADDING_MILLIS, createRecognizer: @escaping () throws -> SherpaRecognizer) {
        self.paddingMillis = paddingMillis
        self.createRecognizer = createRecognizer
    }

    public convenience init(store: ModelStore, models: SpeechModels) {
        self.init { try SherpaRecognizer(store: store, model: models.asr) }
    }

    public func transcribe(_ segment: SpeechSegment) async throws -> TimedTranscription {
        let padding = Int(Int64(segment.sampleRateHz) * paddingMillis / 1_000)
        var samples = [Float](repeating: 0, count: padding + segment.sampleCount + padding)
        defer { zeroFill(&samples) }
        try segment.withSamples { source in
            for index in 0..<source.count { samples[padding + index] = Float(source[index]) / FULL_SCALE }
        }
        let recognition = try lock.withLock { try loaded().decode(samples, sampleRateHz: segment.sampleRateHz) }
        let trimmed = recognition.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return TimedTranscription(try Transcription(trimmed), tokensOf(recognition))
    }

    /// 時刻は足した無音のぶんを引いて、区間の先頭からにする。数が揃わなければ時刻がないものとして扱う。
    private func tokensOf(_ recognition: NativeRecognition) -> [TranscriptToken] {
        guard recognition.tokens.count == recognition.timestampSeconds.count else { return [] }
        return zip(recognition.tokens, recognition.timestampSeconds).map { text, seconds in
            TranscriptToken(text, max(Int64(seconds * 1_000) - paddingMillis, 0))
        }
    }

    private func loaded() throws -> SherpaRecognizer {
        if closed { throw IllegalStateException("解放済みです") }
        if let recognizer { return recognizer }
        let created = try createRecognizer()
        recognizer = created
        return created
    }

    /// 処理中の発話があれば、終わるまで待ってから解放する。
    public func close() {
        lock.withLock {
            closed = true
            recognizer = nil
        }
    }
}

/// 話者の特徴量の抽出器。
final class SherpaSpeakerExtractor {
    private let extractor: OpaquePointer

    init(modelPath: String) throws {
        let strings = CStrings()
        var config = SherpaOnnxSpeakerEmbeddingExtractorConfig()
        config.model = strings.make(modelPath)
        config.num_threads = SherpaSettings.SPEAKER_THREADS
        config.provider = strings.make("cpu")
        guard let created = SherpaOnnxCreateSpeakerEmbeddingExtractor(&config) else {
            throw SherpaInitializationError(description: "特徴量の抽出器を作れません")
        }
        extractor = created
    }

    /// 特徴量を返す。音声が短すぎて計算できないときは nil を返す。ストリームは内側で解放する。
    func compute(_ samples: [Float], sampleRateHz: Int) -> [Float]? {
        guard let stream = SherpaOnnxSpeakerEmbeddingExtractorCreateStream(extractor) else { return nil }
        defer { SherpaOnnxDestroyOnlineStream(stream) }
        samples.withUnsafeBufferPointer {
            SherpaOnnxOnlineStreamAcceptWaveform(stream, Int32(sampleRateHz), $0.baseAddress, Int32($0.count))
        }
        SherpaOnnxOnlineStreamInputFinished(stream)
        guard SherpaOnnxSpeakerEmbeddingExtractorIsReady(extractor, stream) != 0,
              let vector = SherpaOnnxSpeakerEmbeddingExtractorComputeEmbedding(extractor, stream)
        else { return nil }
        defer { SherpaOnnxSpeakerEmbeddingExtractorDestroyEmbedding(vector) }
        let dimension = Int(SherpaOnnxSpeakerEmbeddingExtractorDim(extractor))
        return Array(UnsafeBufferPointer(start: vector, count: dimension))
    }

    deinit {
        SherpaOnnxDestroySpeakerEmbeddingExtractor(extractor)
    }
}

/**
 * 発話区間全体から1つの特徴量を作る。Android の SherpaSpeakerEmbedder と同じ。
 *
 * [minMillis] より短い音声はネイティブへ渡さず nil を返す。数でない値を含む結果も捨てる。
 */
public final class SherpaSpeakerEmbedder: SpeakerEmbedder, @unchecked Sendable {
    public let modelVersion: String
    private let minMillis: Int64
    private let createExtractor: () throws -> SherpaSpeakerExtractor
    private let lock = NSLock()
    private var extractor: SherpaSpeakerExtractor?
    private var closed = false

    init(modelVersion: String, minMillis: Int64 = SherpaSettings.MIN_SPEAKER_MILLIS, createExtractor: @escaping () throws -> SherpaSpeakerExtractor) {
        self.modelVersion = modelVersion
        self.minMillis = minMillis
        self.createExtractor = createExtractor
    }

    public convenience init(store: ModelStore, model: SpeakerModel) {
        let path = store.pathOf(model.modelPackage, model.model)
        self.init(modelVersion: model.version) { try SherpaSpeakerExtractor(modelPath: path) }
    }

    public func embed(_ segment: SpeechSegment) async throws -> SpeakerEmbedding? {
        if Int64(segment.sampleCount) * 1_000 < minMillis * Int64(segment.sampleRateHz) { return nil }
        var samples = try segment.withSamples { source in source.map { Float($0) / FULL_SCALE } }
        defer { zeroFill(&samples) }
        let vector = try lock.withLock { try loaded().compute(samples, sampleRateHz: segment.sampleRateHz) }
        guard let vector, vector.allSatisfy(\.isFinite) else { return nil }
        return SpeakerEmbedding(vector, modelVersion)
    }

    private func loaded() throws -> SherpaSpeakerExtractor {
        if closed { throw IllegalStateException("解放済みです") }
        if let extractor { return extractor }
        let created = try createExtractor()
        extractor = created
        return created
    }

    public func close() {
        lock.withLock {
            closed = true
            extractor = nil
        }
    }
}

func zeroFill<T: Numeric>(_ values: inout [T]) {
    for index in values.indices { values[index] = 0 }
}
