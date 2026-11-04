import Foundation

/**
 * 発話区間の中で話者を判定し、話者の交代で本文を分ける。
 *
 * 区間の内側を窓に分けて自分との類似度を取り、ヒステリシスで自分かそれ以外に分ける。それ以外のまとまりは、
 * 既存の話者に照合し、近い話者がいなければ上限まで仮の話者を作る。手順と値の根拠は docs/検証-話者区間.md にある。
 *
 * 自分の声が登録されるまでは何もしない。「自分」のラベルがないと ToDo の抽出が自分の発話を見分けられず、
 * 特徴量を作る電池も無駄になる。そのため自分の登録より前の発話の話者は nil のまま残る。
 *
 * 登録の特徴量は変えない。確信の高い発話だけを別の [SelfAdaptation] にゆっくり足し、類似度を少しだけ上げるのに使う。
 * 他人の声を誤って学んでも効く幅は小さく、登録し直せば消える。仮の話者の特徴量は、十分な長さの発話で更新する。
 */
public final class SpeakerIdentifier: Sendable {
    private let embedder: any SpeakerEmbedder
    private let speakers: any SpeakerRepository
    private let clock: any Clock
    private let config: SpeakerConfig
    private let decisionLog: (any SpeakerDecisionLog)?

    public init(
        embedder: any SpeakerEmbedder,
        speakers: any SpeakerRepository,
        clock: any Clock,
        config: SpeakerConfig = .default,
        decisionLog: (any SpeakerDecisionLog)? = nil
    ) {
        self.embedder = embedder
        self.speakers = speakers
        self.clock = clock
        self.config = config
        self.decisionLog = decisionLog
    }

    /**
     * 話者の続く範囲を時刻の順に返す。範囲の時刻は隙間なく続き、隣は違う話者になる。
     * 話者を判定できなければ、区間全体を不明の 1 行にして返す。[segment] の解放は呼び出し側に任せる。
     */
    public func identify(_ segment: SpeechSegment, _ transcription: TimedTranscription) async throws -> [SpeakerTurn] {
        let embeddings = try await speakers.listEmbeddings(
            modelVersion: embedder.modelVersion,
            limit: config.maxComparedSpeakers
        )
        let selfEmbedding = embeddings.first(where: \.isSelf)
        var adaptation: SelfAdaptation?
        var windows: [ScoredWindow] = []
        if let selfEmbedding {
            adaptation = try await speakers.findSelfAdaptation(modelVersion: embedder.modelVersion)
                .flatMap { $0.vector.count == selfEmbedding.vector.count ? $0 : nil }
            windows = try await scoredWindows(segment, selfEmbedding, adaptation)
        }
        guard let selfEmbedding, !windows.isEmpty else {
            return [SpeakerTurn(segment.startedAtMillis, segment.endedAtMillis, transcription.text, speaker: nil)]
        }
        return try await turnsOf(segment, transcription, selfEmbedding, windows)
    }

    private func scoredWindows(
        _ segment: SpeechSegment,
        _ selfEmbedding: StoredSpeakerEmbedding,
        _ adaptation: SelfAdaptation?
    ) async throws -> [ScoredWindow] {
        let windows = speakerWindows(segment.endedAtMillis - segment.startedAtMillis, config)
        var scored: [ScoredWindow] = []
        for window in windows {
            try Task.checkCancellation()
            guard let vector = try await vectorOf(segment, window), vector.count == selfEmbedding.vector.count else {
                continue
            }
            scored.append(ScoredWindow(window, vector, try selfScore(vector, selfEmbedding, adaptation)))
        }
        return scored
    }

    /// 登録と比べる。育てた声のほうが近ければ、[SpeakerConfig.adaptMaxBoost] の分まで上げる。
    private func selfScore(
        _ vector: [Float],
        _ selfEmbedding: StoredSpeakerEmbedding,
        _ adaptation: SelfAdaptation?
    ) throws -> Float {
        let enrolled = try similarity(vector, selfEmbedding.vector)
        guard let adaptation else { return enrolled }
        let adapted = try similarity(vector, adaptation.vector)
        return kotlinMax(enrolled, kotlinMin(adapted, enrolled + config.adaptMaxBoost))
    }

    /// 窓の音声はコピーなので、例外やキャンセルでも `use` で 0 に塗りつぶす。
    private func vectorOf(_ segment: SpeechSegment, _ window: SpeakerWindow) async throws -> [Float]? {
        let embedding = try await slice(segment, window).use { slice in try await embedder.embed(slice) }
        return embedding.flatMap { normalizedOrNull($0.vector) }
    }

    private func slice(_ segment: SpeechSegment, _ window: SpeakerWindow) throws -> SpeechSegment {
        let from = sampleIndex(segment, window.startMillis)
        let to = sampleIndex(segment, window.endMillis)
        return try segment.withSamples { samples in
            // Kotlin の copyOfRange は from > to で IllegalArgumentException を投げる
            try require(from <= to, "\(from) > \(to)")
            return SpeechSegment(
                startedAtMillis: segment.startedAtMillis + window.startMillis,
                endedAtMillis: segment.startedAtMillis + window.endMillis,
                sampleRateHz: segment.sampleRateHz,
                copying: UnsafeBufferPointer(rebasing: samples[from..<to])
            )
        }
    }

    private func sampleIndex(_ segment: SpeechSegment, _ offsetMillis: Int64) -> Int {
        Int(min(offsetMillis * Int64(segment.sampleRateHz) / Self.MILLIS_PER_SECOND, Int64(segment.sampleCount)))
    }

    private func turnsOf(
        _ segment: SpeechSegment,
        _ transcription: TimedTranscription,
        _ selfEmbedding: StoredSpeakerEmbedding,
        _ windows: [ScoredWindow]
    ) async throws -> [SpeakerTurn] {
        let durationMillis = segment.endedAtMillis - segment.startedAtMillis
        let runs = try voiceRuns(windows, config)
        let parts = assignText(runs, timedTokens(transcription, durationMillis), durationMillis)
        let isLongSegment = durationMillis >= config.minReliableMillis
        var decisions: [SpeakerDecisionSample] = []
        var turns: [SpeakerTurn] = []
        for part in parts {
            let speaker = try await assignmentOf(part.run, selfEmbedding, isLongSegment, &decisions)
            turns.append(
                SpeakerTurn(
                    segment.startedAtMillis + part.startMillis,
                    segment.startedAtMillis + part.endMillis,
                    part.text,
                    speaker: speaker,
                    voice: part.run.vector.map { TurnVoice($0, part.run.millis) }
                )
            )
        }
        try await decisionLog?.record(decisions, nowMillis: clock.nowMillis())
        return try mergeAdjacent(turns)
    }

    private func assignmentOf(
        _ run: VoiceRun,
        _ selfEmbedding: StoredSpeakerEmbedding,
        _ isLongSegment: Bool,
        _ decisions: inout [SpeakerDecisionSample]
    ) async throws -> SpeakerAssignment? {
        var others: [StoredSpeakerEmbedding] = []
        if let vector = run.vector { others = try await othersFor(vector) }
        var rival: Float?
        if let vector = run.vector {
            // Kotlin の maxOfOrNull は Math.max で畳む
            for other in others {
                let score = try similarity(vector, other.vector)
                rival = rival.map { kotlinMax($0, score) } ?? score
            }
        }
        let assignment: SpeakerAssignment?
        switch settledVoice(run, rival, config) {
        case .SELF:
            try await learnSelf(run, selfEmbedding, rival)
            assignment = try SpeakerAssignment(selfEmbedding.speakerId, confidence: coerceIn(Double(run.score), 0, 1))
        case .UNKNOWN:
            assignment = nil
        case .OTHER:
            if let vector = run.vector {
                assignment = try await assignOther(vector, others, isLongSegment, run.millis)
            } else {
                assignment = nil
            }
        }
        let decision: SpeakerDecision = switch assignment?.speakerId {
        case nil: .UNKNOWN
        case selfEmbedding.speakerId: .SELF
        default: .OTHER
        }
        decisions.append(SpeakerDecisionSample(decision, run.score, run.millis))
        return assignment
    }

    /// 同じ区間の前のまとまりで作った話者とも比べるので、まとまりごとに読み直す。
    private func othersFor(_ vector: [Float]) async throws -> [StoredSpeakerEmbedding] {
        try await speakers.listEmbeddings(modelVersion: embedder.modelVersion, limit: config.maxComparedSpeakers)
            .filter { !$0.isSelf && $0.vector.count == vector.count }
    }

    /**
     * 高い確信の発話だけを自分の声に足す。他人の声で崩さないように、登録に近いことと、
     * 知っている他人より十分に自分に近いことを確かめる。
     */
    private func learnSelf(_ run: VoiceRun, _ selfEmbedding: StoredSpeakerEmbedding, _ rival: Float?) async throws {
        guard let vector = run.vector else { return }
        // Kotlin の && と同じく、前の条件が偽なら後ろの類似度を求めない
        var isConfident = run.score >= config.adaptMinScore && run.millis >= config.adaptMinRunMillis
        if isConfident { isConfident = try similarity(vector, selfEmbedding.vector) >= config.adaptMinEnrolledScore }
        if isConfident { isConfident = rival.map { run.score - $0 >= config.adaptRivalMargin } ?? true }
        if !isConfident { return }
        let stored = try await speakers.findSelfAdaptation(modelVersion: embedder.modelVersion)
            .flatMap { $0.vector.count == vector.count ? $0 : nil }
        let voice = TurnVoice(vector, run.millis)
        if let learned = try learnedSelfVoice(selfEmbedding.vector, stored, voice, embedder.modelVersion, config) {
            try await speakers.saveSelfAdaptation(learned, nowMillis: clock.nowMillis())
        }
    }

    private func assignOther(
        _ vector: [Float],
        _ others: [StoredSpeakerEmbedding],
        _ isLongSegment: Bool,
        _ runMillis: Int64
    ) async throws -> SpeakerAssignment? {
        let isReliable = isLongSegment && runMillis >= config.minReliableRunMillis
        let match = try others.map { ($0, try similarity(vector, $0.vector)) }.kotlinMaxByFloat(\.1)
        let provisional = others.filter { $0.nameSource == .PROVISIONAL }
        let canCreate = isLongSegment && runMillis >= config.minNewSpeakerRunMillis
        let isFull = provisional.count >= config.maxProvisionalSpeakers
        if case let (speaker, score)? = match, score >= config.provisionalThreshold {
            if isReliable { try await absorb(speaker, vector, runMillis) }
            return try SpeakerAssignment(speaker.speakerId, confidence: coerceIn(Double(score), 0, 1))
        }
        if !isFull, canCreate { return try SpeakerAssignment(try await create(vector, runMillis).id, confidence: nil) }
        if isFull { return try await reuseOrNearest(vector, provisional, canCreate, runMillis) }
        return nil
    }

    /**
     * 上限に達したら話者を増やさない。長く使っていない仮の話者がいれば、その席を新しい声に譲る。
     * いなければ最も近い仮の話者に入れる。遠い声なので、重心は動かさない。
     */
    private func reuseOrNearest(
        _ vector: [Float],
        _ provisional: [StoredSpeakerEmbedding],
        _ canCreate: Bool,
        _ runMillis: Int64
    ) async throws -> SpeakerAssignment? {
        let nowMillis = clock.nowMillis()
        let stale = provisional.filter { nowMillis - $0.updatedAtMillis >= config.staleProvisionalMillis }
            .kotlinMinBy(\.updatedAtMillis)
        if let stale, canCreate {
            let embedding = SpeakerEmbedding(vector, embedder.modelVersion)
            try await speakers.updateEmbedding(
                speakerId: stale.speakerId,
                embedding,
                sampleCount: 1,
                nowMillis: nowMillis,
                voicedMillis: runMillis
            )
            return try SpeakerAssignment(stale.speakerId, confidence: nil)
        }
        let nearest = try provisional.map { ($0, try similarity(vector, $0.vector)) }.kotlinMaxByFloat(\.1)
        guard case let (speaker, score)? = nearest else { return nil }
        return try SpeakerAssignment(speaker.speakerId, confidence: coerceIn(Double(score), 0, 1))
    }

    private func create(_ vector: [Float], _ runMillis: Int64) async throws -> Speaker {
        try await speakers.createProvisionalSpeaker(
            SpeakerEmbedding(vector, embedder.modelVersion),
            nowMillis: clock.nowMillis(),
            voicedMillis: runMillis
        )
    }

    private func absorb(_ speaker: StoredSpeakerEmbedding, _ vector: [Float], _ runMillis: Int64) async throws {
        guard let mean = try weightedMean(speaker.vector, max(speaker.voicedMillis, 1), vector, runMillis) else { return }
        try await speakers.updateEmbedding(
            speakerId: speaker.speakerId,
            SpeakerEmbedding(mean, embedder.modelVersion),
            sampleCount: speaker.sampleCount + 1,
            nowMillis: clock.nowMillis(),
            voicedMillis: speaker.voicedMillis + runMillis
        )
    }

    private static let MILLIS_PER_SECOND: Int64 = 1_000
}
