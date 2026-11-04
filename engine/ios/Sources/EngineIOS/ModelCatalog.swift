import CryptoKit
import Foundation

/// sherpa-onnx の配布物の置き場所。
private let SHERPA_RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

/// Gemma の配布物の置き場所。Apache-2.0 で、ログインも利用規約の同意も要らない。
private let LITERT_COMMUNITY = "https://huggingface.co/litert-community"

/// 話者のモデルの置き場所。配布元のタグの綴りは recongition のまま。
private let SPEAKER_RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models"

private let VERIFIED_STAMP = ".verified"
private let REVISION_CHARS = 8
private let HASH_BUFFER_BYTES = 1 << 20

public struct ModelFile: Sendable, Hashable {
    public let name: String
    public let sizeBytes: Int64
    public let sha256: String

    public init(_ name: String, _ sizeBytes: Int64, _ sha256: String) {
        self.name = name
        self.sizeBytes = sizeBytes
        self.sha256 = sha256
    }
}

/// モデルの配布物。アプリが通信する先はカタログのこの URL だけ。Android の `ModelSource` と同じ。
public enum ModelSource: Sendable, Hashable {
    /// 配布物がモデルのファイルそのもの。
    case singleFile(url: String, file: ModelFile)

    /// 使わないファイルも含む tar.bz2。[directory] の下から、パッケージのファイルだけを取り出す。
    case tarBz2(url: String, sizeBytes: Int64, sha256: String, directory: String)

    public var url: String {
        switch self {
        case let .singleFile(url, _), let .tarBz2(url, _, _, _): url
        }
    }

    public var sizeBytes: Int64 {
        switch self {
        case let .singleFile(_, file): file.sizeBytes
        case let .tarBz2(_, sizeBytes, _, _): sizeBytes
        }
    }

    public var sha256: String {
        switch self {
        case let .singleFile(_, file): file.sha256
        case let .tarBz2(_, _, sha256, _): sha256
        }
    }
}

/// 1つのディレクトリに置くモデルのファイルの組。ディレクトリの名前は [id]。
public struct ModelPackage: Sendable, Hashable {
    public let id: String
    public let files: [ModelFile]
    public let source: ModelSource?

    public init(_ id: String, _ files: [ModelFile], _ source: ModelSource? = nil) {
        self.id = id
        self.files = files
        self.source = source
    }
}

public struct VadModel: Sendable {
    public let modelPackage: ModelPackage
    public let model: ModelFile
}

/// 文字起こしのモデル。Android の `AsrModel` と同じ種類を持つ。
public enum AsrModel: Sendable {
    case transducer(modelPackage: ModelPackage, encoder: ModelFile, decoder: ModelFile, joiner: ModelFile, tokens: ModelFile)
    case senseVoice(modelPackage: ModelPackage, model: ModelFile, tokens: ModelFile)

    public var modelPackage: ModelPackage {
        switch self {
        case let .transducer(modelPackage, _, _, _, _), let .senseVoice(modelPackage, _, _): modelPackage
        }
    }
}

/// 整理に使う LLM のモデル。[version] はメモに残す版。
public struct LlmModel: Sendable {
    public let modelPackage: ModelPackage
    public let model: ModelFile
    public let version: String
}

/// 話者の特徴量のモデル。なくても文字起こしは動く。
public struct SpeakerModel: Sendable {
    public let modelPackage: ModelPackage
    public let model: ModelFile

    /// 特徴量に記録する版。モデルを変えると、保存済みの特徴量は比べられなくなる。
    public var version: String { modelPackage.id }
}

public struct SpeechModels: Sendable {
    public let vad: VadModel
    public let asr: AsrModel

    public var packages: [ModelPackage] { [vad.modelPackage, asr.modelPackage] }
}

/**
 * 使えるモデルの一覧。値は Android の `ModelCatalog`(engine/adapters-sherpa の Models.kt)と同じにする。
 * 差し替えるときは両方と `scripts/fetch-models.sh` を合わせる。
 */
public enum ModelCatalog {
    public static let SILERO_VAD: VadModel = {
        let model = ModelFile(
            "silero_vad.onnx",
            643_854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"
        )
        let source = ModelSource.singleFile(url: "\(SHERPA_RELEASES)/silero_vad.onnx", file: model)
        return VadModel(modelPackage: ModelPackage("silero-vad", [model], source), model: model)
    }()

    public static let ZIPFORMER_REAZON: AsrModel = {
        let encoder = ModelFile(
            "encoder-epoch-99-avg-1.int8.onnx",
            154_670_139,
            "2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d"
        )
        let decoder = ModelFile(
            "decoder-epoch-99-avg-1.int8.onnx",
            2_959_337,
            "8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485"
        )
        let joiner = ModelFile(
            "joiner-epoch-99-avg-1.int8.onnx",
            2_696_970,
            "49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de"
        )
        let tokens = ModelFile(
            "tokens.txt",
            45_754,
            "2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def"
        )
        let id = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01"
        return .transducer(
            modelPackage: ModelPackage(
                id,
                [encoder, decoder, joiner, tokens],
                .tarBz2(
                    url: "\(SHERPA_RELEASES)/\(id).tar.bz2",
                    sizeBytes: 713_097_333,
                    sha256: "e0981d0d5d7b446d41010831b59091ebf57d2aa7b79980f67ca37af460b5842d",
                    directory: id
                )
            ),
            encoder: encoder,
            decoder: decoder,
            joiner: joiner,
            tokens: tokens
        )
    }()

    /// 次点。ReazonSpeech 由来のモデルを避けるときに使う。
    public static let SENSE_VOICE_2024: AsrModel = {
        let model = ModelFile(
            "model.int8.onnx",
            239_233_841,
            "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
        )
        let tokens = ModelFile(
            "tokens.txt",
            315_894,
            "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
        )
        let id = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        return .senseVoice(
            modelPackage: ModelPackage(
                id,
                [model, tokens],
                .tarBz2(
                    url: "\(SHERPA_RELEASES)/\(id).tar.bz2",
                    sizeBytes: 163_002_883,
                    sha256: "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e",
                    directory: id
                )
            ),
            model: model,
            tokens: tokens
        )
    }()

    /// 検証の第一候補。リビジョンは検証(`tools/spike-llm/results/models.json`)と同じ。
    public static let GEMMA_4_E4B: LlmModel = gemma(
        repository: "gemma-4-E4B-it-litert-lm",
        revision: "e6a3892a8c22cf87ff99583996149cf3cb32452b",
        file: ModelFile(
            "gemma-4-E4B-it.litertlm",
            3_659_530_240,
            "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
        )
    )

    /// 次善。品質は落ちるが、小さく速い。
    public static let GEMMA_4_E2B: LlmModel = gemma(
        repository: "gemma-4-E2B-it-litert-lm",
        revision: "1caa527c1e88b55f046fcd8d2b4265606680663d",
        file: ModelFile(
            "gemma-4-E2B-it.litertlm",
            2_588_147_712,
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        )
    )

    public static let CAMPPLUS_ADVANCED: SpeakerModel = {
        let model = ModelFile(
            "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
            28_281_164,
            "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2"
        )
        let source = ModelSource.singleFile(url: "\(SPEAKER_RELEASES)/\(model.name)", file: model)
        let id = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced"
        return SpeakerModel(modelPackage: ModelPackage(id, [model], source), model: model)
    }()

    /// アプリが使うモデル。
    public static let ACTIVE = SpeechModels(vad: SILERO_VAD, asr: ZIPFORMER_REAZON)

    /// 整理に使うモデル。数 GB あるので [ACTIVE] とは分けて、利用者が別に取得する。
    public static let ACTIVE_LLM: LlmModel = GEMMA_4_E4B

    public static let ACTIVE_SPEAKER: SpeakerModel = CAMPPLUS_ADVANCED

    /// アプリが取得するパッケージ。すべてに source が要る。
    public static let DOWNLOADS: [ModelPackage] = ACTIVE.packages + [ACTIVE_SPEAKER.modelPackage]

    /// 整理のモデル。[DOWNLOADS] とは分けて、利用者が別に選ぶ。
    public static let ORGANIZER_DOWNLOADS: [ModelPackage] = [ACTIVE_LLM.modelPackage]

    private static func gemma(repository: String, revision: String, file: ModelFile) -> LlmModel {
        let base = file.name.hasSuffix(".litertlm") ? String(file.name.dropLast(".litertlm".count)) : file.name
        let id = "\(base)-\(revision.prefix(REVISION_CHARS))"
        let url = "\(LITERT_COMMUNITY)/\(repository)/resolve/\(revision)/\(file.name)"
        return LlmModel(
            modelPackage: ModelPackage(id, [file], .singleFile(url: url, file: file)),
            model: file,
            version: id
        )
    }
}

/// [root] の下にモデルを置く。Android の `ModelStore` と同じ決まりで確かめる。
public final class ModelStore: Sendable {
    let root: URL

    public init(root: URL) {
        self.root = root
    }

    public func pathOf(_ modelPackage: ModelPackage, _ file: ModelFile) -> String {
        directoryOf(modelPackage).appendingPathComponent(file.name).path
    }

    func directoryOf(_ modelPackage: ModelPackage) -> URL {
        root.appendingPathComponent(modelPackage.id, isDirectory: true)
    }

    /// ファイルが揃っているかを大きさだけで見る。中身まで確かめるのは [verify]。
    public func isInstalled(_ models: SpeechModels) -> Bool {
        models.packages.allSatisfy { isInstalled($0) }
    }

    public func isInstalled(_ modelPackage: ModelPackage) -> Bool {
        modelPackage.files.allSatisfy { fileSize(pathOf(modelPackage, $0)) == $0.sizeBytes }
    }

    public func isInstalled(_ model: SpeakerModel) -> Bool {
        fileSize(pathOf(model.modelPackage, model.model)) == model.model.sizeBytes
    }

    /// SHA-256 を確かめる。合わないファイルは消す。一度通ったパッケージは印を残して計算を省く。
    public func verify(_ models: SpeechModels) -> Bool {
        models.packages.map { verify($0) }.allSatisfy { $0 }
    }

    public func verify(_ model: SpeakerModel) -> Bool { verify(model.modelPackage) }

    public func verify(_ modelPackage: ModelPackage) -> Bool {
        let directory = directoryOf(modelPackage)
        let stamp = directory.appendingPathComponent(VERIFIED_STAMP)
        let expected = stampOf(modelPackage)
        let hasStamp = (try? String(contentsOf: stamp, encoding: .utf8)) == expected
        if hasStamp, isInstalled(modelPackage) { return true }

        try? FileManager.default.removeItem(at: stamp)
        let broken = modelPackage.files.filter { !hasExpectedHash(directory.appendingPathComponent($0.name), $0) }
        for file in broken { try? FileManager.default.removeItem(at: directory.appendingPathComponent(file.name)) }
        let isValid = broken.isEmpty
        if isValid { try? expected.write(to: stamp, atomically: true, encoding: .utf8) }
        return isValid
    }

    /**
     * [staged] の下の、確かめ終えたファイルを移す。開始の判定は大きさを見るので、書きかけのファイルをここへ置かない。
     * 名前の変更で移すので、[staged] は同じファイルシステムに置く。
     */
    func install(_ modelPackage: ModelPackage, staged: URL) throws {
        let directory = directoryOf(modelPackage)
        try StorageDirectories.prepare(directory)
        for file in modelPackage.files {
            let target = directory.appendingPathComponent(file.name)
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: staged.appendingPathComponent(file.name), to: target)
        }
        try stampOf(modelPackage).write(to: directory.appendingPathComponent(VERIFIED_STAMP), atomically: true, encoding: .utf8)
    }

    private func stampOf(_ modelPackage: ModelPackage) -> String {
        modelPackage.files.map { "\($0.name) \($0.sizeBytes) \($0.sha256)" }.joined(separator: "\n")
    }

    private func hasExpectedHash(_ url: URL, _ expected: ModelFile) -> Bool {
        fileSize(url.path) == expected.sizeBytes && (try? sha256Of(url)) == expected.sha256
    }
}

func fileSize(_ path: String) -> Int64 {
    guard let attributes = try? FileManager.default.attributesOfItem(atPath: path) else { return -1 }
    return (attributes[.size] as? NSNumber)?.int64Value ?? -1
}

/// [onRead] は読んだバイト数の累計を受け取る。中断するときは例外を投げる。
func sha256Of(_ url: URL, onRead: (Int64) throws -> Void = { _ in }) throws -> String {
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var hasher = SHA256()
    var total: Int64 = 0
    while true {
        let chunk = try autoreleasepool { try handle.read(upToCount: HASH_BUFFER_BYTES) ?? Data() }
        if chunk.isEmpty { break }
        hasher.update(data: chunk)
        total += Int64(chunk.count)
        try onRead(total)
    }
    return hexString(hasher.finalize())
}

func hexString<D: Sequence>(_ digest: D) -> String where D.Element == UInt8 {
    digest.map { String(format: "%02x", $0) }.joined()
}
