import Foundation

/**
 * 最近の発話の声をメモリに持つ。手で直したときに、その声を以後の判定へ反映するために使う。
 *
 * DB には書かない。発話ごとの特徴量を残し続けないため。プロセスが終わると消え、古い発話を直しても話者の付け替えだけになる。
 * Kotlin の LinkedHashMap(挿入順)と同じく、同じ発話を入れ直しても順は変えず、上限を超えたら最も古いものを捨てる。
 */
public final class RecentVoices: Sendable {
    private struct Entries {
        var order: [Int64] = []
        var voices: [Int64: TurnVoice] = [:]
    }

    private let capacity: Int
    private let state = LockedState(Entries())

    public init(capacity: Int = RecentVoices.DEFAULT_CAPACITY) {
        self.capacity = capacity
    }

    public func remember(_ utteranceId: Int64, _ voice: TurnVoice) {
        state.withLock { entries in
            if entries.voices.updateValue(voice, forKey: utteranceId) == nil {
                entries.order.append(utteranceId)
            }
            if entries.voices.count > capacity {
                let eldest = entries.order.removeFirst()
                entries.voices[eldest] = nil
            }
        }
    }

    /// 声は 1 度だけ使う。同じ発話を何度か直しても、重心に二重に入れない。
    public func take(_ utteranceId: Int64) -> TurnVoice? {
        state.withLock { entries in
            guard let voice = entries.voices.removeValue(forKey: utteranceId) else { return nil }
            entries.order.removeAll { $0 == utteranceId }
            return voice
        }
    }

    public func clear() {
        state.withLock { $0 = Entries() }
    }

    public static let DEFAULT_CAPACITY = 256
}

/// 発話を手で直す先。Swift の `Self` は予約語なので、自分は `Myself` にした。
public enum SpeakerTarget: Sendable, Hashable {
    case Myself

    case Existing(speakerId: Int64)

    /// 新しい仮の話者。上限に達していれば直せない。
    case NewOther

    case Unknown
}

/// 話者を手で直す。直した結果は以後の判定に反映する。
public final class SpeakerCorrections: Sendable {
    private let speakers: any SpeakerRepository
    private let utterances: any UtteranceRepository
    private let voices: RecentVoices
    private let modelVersion: String
    private let clock: any Clock
    private let config: SpeakerConfig

    public init(
        speakers: any SpeakerRepository,
        utterances: any UtteranceRepository,
        voices: RecentVoices,
        modelVersion: String,
        clock: any Clock,
        config: SpeakerConfig = .default
    ) {
        self.speakers = speakers
        self.utterances = utterances
        self.voices = voices
        self.modelVersion = modelVersion
        self.clock = clock
        self.config = config
    }

    /// 発話の話者を直す。声がメモリにあれば、直した先の重心へ足す。発話や話者がなければ false を返す。
    public func reassign(_ utteranceId: Int64, _ target: SpeakerTarget) async throws -> Bool {
        guard try await utterances.find(id: utteranceId) != nil else { return false }
        switch target {
        case .Unknown:
            return try await utterances.setSpeaker(id: utteranceId, speakerId: nil)
        case .NewOther:
            guard let speakerId = try await createOther(utteranceId) else { return false }
            return try await utterances.setSpeaker(id: utteranceId, speakerId: speakerId)
        case .Myself:
            return try await moveTo(utteranceId, try await embeddings().first(where: \.isSelf)?.speakerId)
        case let .Existing(speakerId):
            return try await moveTo(utteranceId, try await exists(speakerId) ? speakerId : nil)
        }
    }

    private func moveTo(_ utteranceId: Int64, _ speakerId: Int64?) async throws -> Bool {
        guard let speakerId else { return false }
        _ = try await utterances.setSpeaker(id: utteranceId, speakerId: speakerId)
        if let voice = voices.take(utteranceId) { try await learn(speakerId, voice) }
        return true
    }

    private func exists(_ speakerId: Int64) async throws -> Bool {
        try await speakers.listSpeakers().contains { $0.id == speakerId }
    }

    private func embeddings() async throws -> [StoredSpeakerEmbedding] {
        try await speakers.listEmbeddings(modelVersion: modelVersion, limit: Int(Int32.max))
    }

    private func createOther(_ utteranceId: Int64) async throws -> Int64? {
        let provisional = try await speakers.listSpeakers().count { $0.nameSource == .PROVISIONAL }
        if provisional >= config.maxProvisionalSpeakers { return nil }
        let voice = voices.take(utteranceId).flatMap { $0.vector.isEmpty ? nil : $0 }
        let nowMillis = clock.nowMillis()
        let speaker: Speaker
        if let voice {
            let embedding = SpeakerEmbedding(voice.vector, modelVersion)
            speaker = try await speakers.createProvisionalSpeaker(embedding, nowMillis: nowMillis, voicedMillis: voice.voicedMillis)
        } else {
            speaker = try await speakers.createProvisionalSpeakerWithoutVoice(nowMillis: nowMillis)
        }
        return speaker.id
    }

    private func learn(_ speakerId: Int64, _ voice: TurnVoice) async throws {
        let stored = try await embeddings().first { $0.speakerId == speakerId }
        guard let stored else {
            try await speakers.updateEmbedding(
                speakerId: speakerId,
                SpeakerEmbedding(voice.vector, modelVersion),
                sampleCount: 1,
                nowMillis: clock.nowMillis(),
                voicedMillis: voice.voicedMillis
            )
            return
        }
        if stored.vector.count != voice.vector.count { return }
        if stored.isSelf {
            try await learnSelf(stored, voice)
            return
        }
        if let mean = try weightedMean(stored.vector, stored.voicedMillis, voice.vector, voice.voicedMillis) {
            try await speakers.updateEmbedding(
                speakerId: speakerId,
                SpeakerEmbedding(mean, modelVersion),
                sampleCount: stored.sampleCount + 1,
                nowMillis: clock.nowMillis(),
                voicedMillis: stored.voicedMillis + voice.voicedMillis
            )
        }
    }

    private func learnSelf(_ selfEmbedding: StoredSpeakerEmbedding, _ voice: TurnVoice) async throws {
        let adaptation = try await speakers.findSelfAdaptation(modelVersion: modelVersion)
            .flatMap { $0.vector.count == voice.vector.count ? $0 : nil }
        if let learned = try learnedSelfVoice(selfEmbedding.vector, adaptation, voice, modelVersion, config) {
            try await speakers.saveSelfAdaptation(learned, nowMillis: clock.nowMillis())
        }
    }

    /**
     * [fromId] を [intoId] へ統合する。手の操作なので、手で名付けた話者も統合する。自分は統合元にできない。
     * 自分へ統合すると発話は「自分」になり、統合元の重心は育てた自分の声へ足す。登録の特徴量は変えない。
     */
    public func merge(_ fromId: Int64, _ intoId: Int64) async throws -> Bool {
        let all = try await speakers.listSpeakers()
        let from = all.first { $0.id == fromId }
        let into = all.first { $0.id == intoId }
        guard let from, let into, !from.isSelf, fromId != intoId else { return false }
        try await apply(fromId, intoId, into.isSelf)
        return true
    }

    private func apply(_ fromId: Int64, _ intoId: Int64, _ intoSelf: Bool) async throws {
        var stored: [Int64: StoredSpeakerEmbedding] = [:]
        // Kotlin の associateBy と同じく、同じ id は後のものを使う
        for embedding in try await embeddings() { stored[embedding.speakerId] = embedding }
        let from = stored[fromId]
        let into = stored[intoId]
        let merged = intoSelf ? nil : try mergedEmbedding(from, into)
        try await speakers.mergeSpeakers([SpeakerMerge(fromId, intoId, merged)], nowMillis: clock.nowMillis())
        if intoSelf, let from, let into, from.vector.count == into.vector.count {
            try await learnSelf(into, TurnVoice(from.vector, max(from.voicedMillis, 1)))
        }
    }

    private func mergedEmbedding(_ from: StoredSpeakerEmbedding?, _ into: StoredSpeakerEmbedding?) throws -> MergedEmbedding? {
        guard let from else { return nil }
        guard let into, into.vector.count == from.vector.count else {
            return MergedEmbedding(SpeakerEmbedding(from.vector, modelVersion), from.sampleCount, from.voicedMillis)
        }
        let mean = try weightedMean(into.vector, max(into.voicedMillis, 1), from.vector, max(from.voicedMillis, 1))
        return mean.map {
            MergedEmbedding(
                SpeakerEmbedding($0, modelVersion),
                into.sampleCount + from.sampleCount,
                into.voicedMillis + from.voicedMillis
            )
        }
    }

    /// 名前を手で付ける。空の名前は受け付けない。
    public func rename(_ speakerId: Int64, _ name: String) async throws -> Bool {
        let trimmed = name.kotlinTrim()
        guard !trimmed.isEmpty else { return false }
        return try await speakers.setManualName(speakerId: speakerId, name: trimmed)
    }
}
