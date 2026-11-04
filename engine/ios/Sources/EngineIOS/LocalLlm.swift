import EngineDomain
import Foundation

#if canImport(LiteRTLM)
import LiteRTLM
#endif

/// LiteRT-LM が文脈長を超えた入力に返す文言の一部。型では見分けられないので、版を上げたら確かめ直す。
private let INPUT_TOO_LONG = "Input token ids are too long"

/// この端末やこの版では LLM を動かせない。
public struct LlmUnavailableError: Error, CustomStringConvertible {
    public let description: String
}

/**
 * ローカルの LLM。モデルのメモリを持つのはこのクラスだけ。Android の `LocalLlm` と `LiteRtLlmLoader` に当たる。
 *
 * 数 GB を使うので常駐させない。[load] で読み込み、[close] で必ず解放する。依頼ごとに会話を作り直し、履歴を持ち越さない。
 * 設定は検証と同じ(文脈長 4096、top_k 1、温度 0)。制約デコードと thinking は使わない。
 */
public final class LocalLlm: Llm, @unchecked Sendable {
    public let modelVersion: String
    private let modelPath: String
    private let cacheDirectory: String
    private let onBackendChosen: @Sendable (LlmBackend) -> Void

    #if canImport(LiteRTLM)
    private let lock = NSLock()
    private var engine: LiteRTLM.Engine?
    private var running: Conversation?
    #endif

    public init(
        modelVersion: String,
        modelPath: String,
        cacheDirectory: String,
        onBackendChosen: @escaping @Sendable (LlmBackend) -> Void = { _ in }
    ) {
        self.modelVersion = modelVersion
        self.modelPath = modelPath
        self.cacheDirectory = cacheDirectory
        self.onBackendChosen = onBackendChosen
    }

    /// この版に LLM の実行基盤が入っているか。
    public static var isSupported: Bool {
        #if canImport(LiteRTLM)
        true
        #else
        false
        #endif
    }

    /// GPU を先に試し、だめなら CPU で動かす。GPU は読み込みが通っても最初の生成で失敗することがあるので、1 語だけ生成して確かめる。
    public func load() async throws {
        #if canImport(LiteRTLM)
        if lock.withLock({ engine != nil }) { return }
        var loaded: LiteRTLM.Engine
        do {
            let gpu = try await open(.gpu)
            try await confirmUsable(gpu)
            loaded = gpu
            onBackendChosen(.GPU)
        } catch {
            loaded = try await open(.cpu(threadCount: LlmSettings.CPU_THREADS))
            onBackendChosen(.CPU)
        }
        lock.withLock { engine = loaded }
        #else
        throw LlmUnavailableError(description: "この版には LLM の実行基盤がありません")
        #endif
    }

    public func generate(_ request: LlmRequest) async throws -> String {
        #if canImport(LiteRTLM)
        guard let engine = lock.withLock({ engine }) else { throw IllegalStateException("モデルを読み込んでいません") }
        let sampler = try SamplerConfig(
            topK: LlmSettings.TOP_K,
            topP: LlmSettings.TOP_P,
            temperature: LlmSettings.TEMPERATURE,
            seed: LlmSettings.SEED
        )
        let config = ConversationConfig(systemMessage: Message(request.system, role: .system), samplerConfig: sampler)
        // 履歴を持ち越すと文脈長を使い切るので、1回ごとに作り直す
        let conversation = try await engine.createConversation(with: config)
        lock.withLock { running = conversation }
        defer { lock.withLock { running = nil } }
        do {
            return try await withTaskCancellationHandler {
                let reply = try await conversation.sendMessage(Message(request.user), maxOutputTokens: request.maxOutputTokens)
                // Android と同じく、文字の部分を区切らずにつなぐ
                return reply.contents.contents.compactMap { content -> String? in
                    if case let .text(text) = content { return text }
                    return nil
                }.joined()
            } onCancel: {
                try? conversation.cancel()
            }
        } catch {
            if String(describing: error).contains(INPUT_TOO_LONG) { throw LlmInputTooLongException() }
            throw error
        }
        #else
        throw LlmUnavailableError(description: "この版には LLM の実行基盤がありません")
        #endif
    }

    /// 動いている処理を止めてから解放する。何度呼んでもよい。
    public func close() {
        #if canImport(LiteRTLM)
        lock.withLock {
            try? running?.cancel()
            engine = nil
        }
        #endif
    }

    #if canImport(LiteRTLM)
    private func open(_ backend: Backend) async throws -> LiteRTLM.Engine {
        let config = try EngineConfig(
            modelPath: modelPath,
            backend: backend,
            maxNumTokens: LlmSettings.MAX_NUM_TOKENS,
            cacheDir: cacheDirectory
        )
        let engine = LiteRTLM.Engine(engineConfig: config)
        try await engine.initialize()
        return engine
    }

    private func confirmUsable(_ engine: LiteRTLM.Engine) async throws {
        let conversation = try await engine.createConversation(with: ConversationConfig())
        _ = try await conversation.sendMessage(Message("はい"), maxOutputTokens: 1)
    }
    #endif
}
