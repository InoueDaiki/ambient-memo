import Foundation

/// 話者の名前の由来。推定は、自分と手動の名前を上書きしない。
public enum SpeakerNameSource: String, Sendable, Hashable, CaseIterable {
    /// 自分の声の登録で付けた。
    case SELF

    /// 特徴量のまとまりに付けた仮の名前。
    case PROVISIONAL

    /// 会話から推定した。
    case INFERRED

    /// 人が手で付けた。
    case MANUAL

    public var name: String { rawValue }
}

public struct Speaker: Sendable, Hashable {
    public var id: Int64
    public var displayName: String
    public var nameSource: SpeakerNameSource
    public var isSelf: Bool
    public var createdAtMillis: Int64

    public init(_ id: Int64, _ displayName: String, _ nameSource: SpeakerNameSource, _ isSelf: Bool, _ createdAtMillis: Int64) {
        self.id = id
        self.displayName = displayName
        self.nameSource = nameSource
        self.isSelf = isSelf
        self.createdAtMillis = createdAtMillis
    }
}

/// 保存済みの話者の特徴量。[vector] は長さ 1 に正規化してある。
public struct StoredSpeakerEmbedding: Sendable {
    public let speakerId: Int64
    public let isSelf: Bool
    public let vector: [Float]
    public let sampleCount: Int
    /// 重心に入れた発話の長さの合計。
    public let voicedMillis: Int64
    public let nameSource: SpeakerNameSource
    public let updatedAtMillis: Int64

    public init(
        _ speakerId: Int64,
        _ isSelf: Bool,
        _ vector: [Float],
        _ sampleCount: Int,
        _ voicedMillis: Int64,
        _ nameSource: SpeakerNameSource,
        _ updatedAtMillis: Int64
    ) {
        self.speakerId = speakerId
        self.isSelf = isSelf
        self.vector = vector
        self.sampleCount = sampleCount
        self.voicedMillis = voicedMillis
        self.nameSource = nameSource
        self.updatedAtMillis = updatedAtMillis
    }
}

/**
 * 聞き取りの音声で育てた自分の声。登録の特徴量は上書きせず、別に持つ。
 * 端末の置き場所や雑音で下がった類似度を補う。[vector] は長さ 1 に正規化してある。
 */
public struct SelfAdaptation: Sendable {
    public let vector: [Float]
    public let voicedMillis: Int64
    public let modelVersion: String

    public init(_ vector: [Float], _ voicedMillis: Int64, _ modelVersion: String) {
        self.vector = vector
        self.voicedMillis = voicedMillis
        self.modelVersion = modelVersion
    }
}

/**
 * 話者の統合を 1 件表す。[fromId] の発話を [intoId] へ移し、[fromId] を消す。
 * [merged] は統合後の [intoId] の特徴量。nil なら [intoId] の特徴量を変えない。
 */
public struct SpeakerMerge: Sendable {
    public let fromId: Int64
    public let intoId: Int64
    public let merged: MergedEmbedding?

    public init(_ fromId: Int64, _ intoId: Int64, _ merged: MergedEmbedding?) {
        self.fromId = fromId
        self.intoId = intoId
        self.merged = merged
    }
}

public struct MergedEmbedding: Sendable {
    public let embedding: SpeakerEmbedding
    public let sampleCount: Int
    public let voicedMillis: Int64

    public init(_ embedding: SpeakerEmbedding, _ sampleCount: Int, _ voicedMillis: Int64) {
        self.embedding = embedding
        self.sampleCount = sampleCount
        self.voicedMillis = voicedMillis
    }
}

/// 発話の話者の判定結果。[confidence] は最も近い話者とのコサイン類似度。新しい仮の話者は比べる相手がなく nil。
public struct SpeakerAssignment: Sendable, Hashable {
    public let speakerId: Int64
    public let confidence: Double?

    public init(_ speakerId: Int64, confidence: Double?) throws {
        try requireConfidence(confidence)
        self.speakerId = speakerId
        self.confidence = confidence
    }
}

/// 話者の照合の既定値。すべて仮で、端末で自分の声に合わせて調整する前提。Swift の既定値は [default] にある。
public struct SpeakerConfig: Sendable, Hashable {
    /**
     * 自分に入るコサイン類似度。不明の帯の上端も兼ねる。docs/検証-話者区間.md の実機に寄せた測定で決めた仮の値。
     * 0.05 の違いで誤受入も再現率も大きく動き、別の話者の集団で決めた値は持ち越しにくいと分かっている。
     */
    public let selfThreshold: Float
    /// 自分から出るコサイン類似度。これ以上なら自分のまま続く。
    public let selfExitThreshold: Float
    /// 不明の帯の下端。同じ判定が続くまとまりの平均類似度が、これ以上 [selfThreshold] 未満なら不明にする。
    public let unknownFloor: Float
    /// 既存の話者と判定するコサイン類似度。短い窓の特徴量は同じ人でもばらつくので、自分より緩くしてよい。
    public let provisionalThreshold: Float
    /// 仮の話者の上限。達したら増やさず、最も近い仮の話者に入れる。手で名付けた話者は数えない。
    public let maxProvisionalSpeakers: Int
    /// 新しい仮の話者を作るのに要るまとまりの長さ。短い窓のばらつきで話者が増えないようにする。
    public let minNewSpeakerRunMillis: Int64
    /// 上限に達していて、これだけ更新のない仮の話者は、新しい声に席を譲る。
    public let staleProvisionalMillis: Int64
    /// まとめ直しで、仮の話者の数が上限以下でも統合する類似度。
    public let consolidationThreshold: Float
    /// これ以上自分に近いまとまりは、他人と比べずに自分の判定のままにする。
    public let strongSelfThreshold: Float
    /// 不明の帯を、知っている他人と比べて決めるのに要る類似度の差。
    public let rivalMargin: Float
    /// 弱い自分の判定を、知っている他人に変えるのに要る類似度の差。自分を他人としにくいように大きくする。
    public let selfOverrideMargin: Float
    /// 自分の声に足すのに要る、自分との類似度。
    public let adaptMinScore: Float
    /// 自分の声に足すのに要る、登録の特徴量との類似度。更新が登録から離れていかないようにする。
    public let adaptMinEnrolledScore: Float
    /// 自分の声に足すのに要る、知っている他人との類似度の差。
    public let adaptRivalMargin: Float
    /// 自分の声に足すのに要るまとまりの長さ。
    public let adaptMinRunMillis: Int64
    /**
     * 育てた自分の声で類似度を上げられる幅。登録との類似度にこれを足した値までしか上げない。
     * 育てた声は部屋や端末の癖も学ぶので、同じ場所にいる他人の類似度も上がる。誤受入を抑える。
     */
    public let adaptMaxBoost: Float
    /// 自分の声の重みの下限。最初の発話で大きく動かないように、登録の重みとして扱う。
    public let adaptBaseMillis: Int64
    /// 自分の声の重みの上限。古い発話の影響を残しすぎず、1 つの発話で動く量も抑える。
    public let adaptMaxMillis: Int64
    /// 判定する窓の長さ。区間の発話の部分がこれ以下なら、全体で 1 つの窓にする。
    public let windowMillis: Int64
    /// 窓をずらす幅。
    public let hopMillis: Int64
    /// 区間の後ろにある区切りの無音の長さ。窓に入れない。アプリの VAD の区切りの無音と揃える。
    public let trailingSilenceMillis: Int64
    /// 無音を除いた長さがこれより短いときは、無音を除かない。特徴量を作れる下限。
    public let minWindowMillis: Int64
    /// これより短い区間は既存の話者との照合だけに使い、仮の話者を作らず、平均にも入れない。
    public let minReliableMillis: Int64
    /// 長い区間の中でも、これより短いまとまりは [minReliableMillis] と同じ扱いにする。無音を含まないので短い。
    public let minReliableRunMillis: Int64
    /// 照合する話者の上限。周りの声で仮の話者が増えても、発話ごとの処理を増やし続けない。
    public let maxComparedSpeakers: Int

    public init(
        selfThreshold: Float = DEFAULT_SELF_THRESHOLD,
        selfExitThreshold: Float = DEFAULT_SELF_EXIT_THRESHOLD,
        unknownFloor: Float = DEFAULT_UNKNOWN_FLOOR,
        provisionalThreshold: Float = DEFAULT_PROVISIONAL_THRESHOLD,
        maxProvisionalSpeakers: Int = DEFAULT_MAX_PROVISIONAL_SPEAKERS,
        minNewSpeakerRunMillis: Int64 = DEFAULT_MIN_NEW_SPEAKER_RUN_MILLIS,
        staleProvisionalMillis: Int64 = DEFAULT_STALE_PROVISIONAL_MILLIS,
        consolidationThreshold: Float = DEFAULT_PROVISIONAL_THRESHOLD,
        strongSelfThreshold: Float = DEFAULT_STRONG_SELF_THRESHOLD,
        rivalMargin: Float = DEFAULT_RIVAL_MARGIN,
        selfOverrideMargin: Float = DEFAULT_SELF_OVERRIDE_MARGIN,
        adaptMinScore: Float = DEFAULT_ADAPT_MIN_SCORE,
        adaptMinEnrolledScore: Float = DEFAULT_ADAPT_MIN_ENROLLED_SCORE,
        adaptRivalMargin: Float = DEFAULT_ADAPT_RIVAL_MARGIN,
        adaptMinRunMillis: Int64 = DEFAULT_ADAPT_MIN_RUN_MILLIS,
        adaptMaxBoost: Float = DEFAULT_ADAPT_MAX_BOOST,
        adaptBaseMillis: Int64 = DEFAULT_ADAPT_BASE_MILLIS,
        adaptMaxMillis: Int64 = DEFAULT_ADAPT_MAX_MILLIS,
        windowMillis: Int64 = DEFAULT_WINDOW_MILLIS,
        hopMillis: Int64 = DEFAULT_HOP_MILLIS,
        trailingSilenceMillis: Int64 = DEFAULT_TRAILING_SILENCE_MILLIS,
        minWindowMillis: Int64 = DEFAULT_MIN_WINDOW_MILLIS,
        minReliableMillis: Int64 = DEFAULT_MIN_RELIABLE_MILLIS,
        minReliableRunMillis: Int64 = DEFAULT_MIN_RELIABLE_RUN_MILLIS,
        maxComparedSpeakers: Int = DEFAULT_MAX_COMPARED_SPEAKERS
    ) throws {
        try require(isIn(selfThreshold, 0, 1), "selfThreshold は 0 から 1 の範囲です")
        try require(isIn(selfExitThreshold, 0, selfThreshold), "selfExitThreshold は 0 から selfThreshold の範囲です")
        try require(isIn(unknownFloor, 0, selfExitThreshold), "unknownFloor は 0 から selfExitThreshold の範囲です")
        try require(isIn(provisionalThreshold, 0, 1), "provisionalThreshold は 0 から 1 の範囲です")
        try require(maxProvisionalSpeakers > 0, "maxProvisionalSpeakers は正の値です")
        try require(minNewSpeakerRunMillis >= 0, "minNewSpeakerRunMillis は 0 以上です")
        try require(staleProvisionalMillis > 0, "staleProvisionalMillis は正の値です")
        try require(isIn(consolidationThreshold, 0, 1), "consolidationThreshold は 0 から 1 の範囲です")
        try require(isIn(strongSelfThreshold, selfThreshold, 1), "strongSelfThreshold は selfThreshold から 1 の範囲です")
        try require(rivalMargin >= 0, "rivalMargin は 0 以上です")
        try require(selfOverrideMargin >= 0, "selfOverrideMargin は 0 以上です")
        try require(isIn(adaptMinScore, 0, 1), "adaptMinScore は 0 から 1 の範囲です")
        try require(isIn(adaptMinEnrolledScore, 0, 1), "adaptMinEnrolledScore は 0 から 1 の範囲です")
        try require(adaptRivalMargin >= 0, "adaptRivalMargin は 0 以上です")
        try require(adaptMinRunMillis >= 0, "adaptMinRunMillis は 0 以上です")
        try require(adaptMaxBoost >= 0, "adaptMaxBoost は 0 以上です")
        try require(adaptBaseMillis > 0, "adaptBaseMillis は正の値です")
        try require(adaptMaxMillis >= adaptBaseMillis, "adaptMaxMillis は adaptBaseMillis 以上です")
        try require(windowMillis > 0, "windowMillis は正の値です")
        try require(hopMillis > 0, "hopMillis は正の値です")
        try require(trailingSilenceMillis >= 0, "trailingSilenceMillis は 0 以上です")
        try require(minWindowMillis >= 0, "minWindowMillis は 0 以上です")
        try require(minReliableMillis >= 0, "minReliableMillis は 0 以上です")
        try require(minReliableRunMillis >= 0, "minReliableRunMillis は 0 以上です")
        try require(maxComparedSpeakers > 0, "maxComparedSpeakers は正の値です")
        self.selfThreshold = selfThreshold
        self.selfExitThreshold = selfExitThreshold
        self.unknownFloor = unknownFloor
        self.provisionalThreshold = provisionalThreshold
        self.maxProvisionalSpeakers = maxProvisionalSpeakers
        self.minNewSpeakerRunMillis = minNewSpeakerRunMillis
        self.staleProvisionalMillis = staleProvisionalMillis
        self.consolidationThreshold = consolidationThreshold
        self.strongSelfThreshold = strongSelfThreshold
        self.rivalMargin = rivalMargin
        self.selfOverrideMargin = selfOverrideMargin
        self.adaptMinScore = adaptMinScore
        self.adaptMinEnrolledScore = adaptMinEnrolledScore
        self.adaptRivalMargin = adaptRivalMargin
        self.adaptMinRunMillis = adaptMinRunMillis
        self.adaptMaxBoost = adaptMaxBoost
        self.adaptBaseMillis = adaptBaseMillis
        self.adaptMaxMillis = adaptMaxMillis
        self.windowMillis = windowMillis
        self.hopMillis = hopMillis
        self.trailingSilenceMillis = trailingSilenceMillis
        self.minWindowMillis = minWindowMillis
        self.minReliableMillis = minReliableMillis
        self.minReliableRunMillis = minReliableRunMillis
        self.maxComparedSpeakers = maxComparedSpeakers
    }

    /// Kotlin の `SpeakerConfig()`。
    public static let `default` = try! SpeakerConfig()

    @usableFromInline static let DEFAULT_SELF_THRESHOLD: Float = 0.5
    @usableFromInline static let DEFAULT_SELF_EXIT_THRESHOLD: Float = 0.45
    @usableFromInline static let DEFAULT_UNKNOWN_FLOOR: Float = 0.4
    @usableFromInline static let DEFAULT_WINDOW_MILLIS: Int64 = 1_500
    @usableFromInline static let DEFAULT_HOP_MILLIS: Int64 = 750
    @usableFromInline static let DEFAULT_TRAILING_SILENCE_MILLIS: Int64 = 800
    @usableFromInline static let DEFAULT_MIN_WINDOW_MILLIS: Int64 = 400

    // 検証で、無音を含まないまとまりに使った値
    @usableFromInline static let DEFAULT_MIN_RELIABLE_RUN_MILLIS: Int64 = 1_000

    // アプリの区間は前後に約 1.4 秒の無音を含むので、実音声は約 1.1 秒。検証の 1.5 秒の EER は 1.4%
    @usableFromInline static let DEFAULT_MIN_RELIABLE_MILLIS: Int64 = 2_500
    @usableFromInline static let DEFAULT_MAX_COMPARED_SPEAKERS = 64
    @usableFromInline static let DEFAULT_PROVISIONAL_THRESHOLD: Float = 0.45
    @usableFromInline static let DEFAULT_MAX_PROVISIONAL_SPEAKERS = 3
    @usableFromInline static let DEFAULT_MIN_NEW_SPEAKER_RUN_MILLIS: Int64 = 2_000
    @usableFromInline static let DEFAULT_STALE_PROVISIONAL_MILLIS: Int64 = 7 * 24 * 60 * 60 * 1_000
    @usableFromInline static let DEFAULT_STRONG_SELF_THRESHOLD: Float = 0.6
    @usableFromInline static let DEFAULT_RIVAL_MARGIN: Float = 0.05
    @usableFromInline static let DEFAULT_SELF_OVERRIDE_MARGIN: Float = 0.1
    @usableFromInline static let DEFAULT_ADAPT_MIN_SCORE: Float = 0.55
    @usableFromInline static let DEFAULT_ADAPT_MIN_ENROLLED_SCORE: Float = 0.4
    @usableFromInline static let DEFAULT_ADAPT_RIVAL_MARGIN: Float = 0.1
    @usableFromInline static let DEFAULT_ADAPT_MIN_RUN_MILLIS: Int64 = 2_000
    @usableFromInline static let DEFAULT_ADAPT_MAX_BOOST: Float = 0.05
    @usableFromInline static let DEFAULT_ADAPT_BASE_MILLIS: Int64 = 120_000
    @usableFromInline static let DEFAULT_ADAPT_MAX_MILLIS: Int64 = 600_000
}

/**
 * 整理ジョブの文字起こしに付ける話者のラベル。
 *
 * プロンプトは「自分」を端末の持ち主として説明しているので、名前を変えても「自分」のままにする。
 * 仮の話者も含め、話者が分かる発話だけにラベルを付ける。
 */
struct SpeakerLabels: Sendable {
    private let labels: [Int64: String]

    init(_ speakers: [Speaker]) {
        var labels: [Int64: String] = [:]
        // Kotlin の associate と同じく、同じ id は後のものを使う
        for speaker in speakers {
            labels[speaker.id] = speaker.isSelf ? Self.SELF_LABEL : Self.toLabel(speaker.displayName)
        }
        self.labels = labels
    }

    func of(_ utterance: Utterance) -> String? {
        guard let speakerId = utterance.speakerId, let label = labels[speakerId], !label.isEmpty else { return nil }
        return label
    }

    /// 手で付けた名前で、文字起こしの行の形を崩さない。
    ///
    /// Kotlin の `replace(Regex("[\\s:：]+"), " ").trim()`。`\s` は ASCII の空白だけ。
    private static func toLabel(_ name: String) -> String {
        var units: [UInt16] = []
        var inRun = false
        for unit in name.utf16 {
            if isRegexSpace(unit) || LINE_BREAKING_COLONS.contains(unit) {
                if !inRun { units.append(0x20) }
                inRun = true
            } else {
                units.append(unit)
                inRun = false
            }
        }
        return stringFromUTF16(units).kotlinTrim()
    }

    static let NONE = SpeakerLabels([])
    private static let SELF_LABEL = "自分"
    private static let LINE_BREAKING_COLONS = Set(":：".utf16)
}

/// 長さ 1 に正規化する。無音などで長さ 0 か、数でない値が入っていると比べられないので nil を返す。
func normalizedOrNull(_ vector: [Float]) -> [Float]? {
    // Kotlin の sumOf と同じく、Double で前から順に足す
    var sum = 0.0
    for value in vector { sum += Double(value) * Double(value) }
    let length = Float(sum.squareRoot())
    let isUsable = length > 0 && length.isFinite
    return isUsable ? vector.map { $0 / length } : nil
}

/// 長さ 1 のベクトルのコサイン類似度。
func similarity(_ first: [Float], _ second: [Float]) throws -> Float {
    try require(first.count == second.count, "特徴量の次元が違います")
    var sum: Float = 0
    for index in first.indices { sum = sum + first[index] * second[index] }
    return sum
}

/// 長さ 1 のベクトルの重み付き平均を、長さ 1 に戻す。重みは発話の長さ。
func weightedMean(_ mean: [Float], _ meanMillis: Int64, _ added: [Float], _ addedMillis: Int64) throws -> [Float]? {
    try require(mean.count == added.count, "特徴量の次元が違います")
    // Kotlin の Float * Long は、Long を Float にしてから掛ける
    let meanWeight = Float(meanMillis)
    let addedWeight = Float(addedMillis)
    return normalizedOrNull(mean.indices.map { mean[$0] * meanWeight + added[$0] * addedWeight })
}

/**
 * 自分の声に発話を足す。[stored] がなければ登録の特徴量から始める。登録の特徴量は変えない。
 * 重みに下限があるので最初の発話で大きく動かず、上限があるので今の端末の条件に追いつける。
 */
func learnedSelfVoice(
    _ enrolled: [Float],
    _ stored: SelfAdaptation?,
    _ voice: TurnVoice,
    _ modelVersion: String,
    _ config: SpeakerConfig
) throws -> SelfAdaptation? {
    let base = stored?.vector ?? enrolled
    let weight = min(max(stored?.voicedMillis ?? 0, config.adaptBaseMillis), config.adaptMaxMillis)
    guard let mean = try weightedMean(base, weight, voice.vector, voice.voicedMillis) else { return nil }
    let total = min((stored?.voicedMillis ?? 0) + voice.voicedMillis, config.adaptMaxMillis)
    return SelfAdaptation(mean, total, modelVersion)
}

/// 長さ 1 のベクトルの平均を、長さ 1 に戻す。
func meanOf(_ vectors: [[Float]]) throws -> [Float]? {
    guard let first = vectors.first else { return nil }
    try require(vectors.allSatisfy { $0.count == first.count }, "特徴量の次元が違います")
    return normalizedOrNull(first.indices.map { index in
        var sum = 0.0
        for vector in vectors { sum += Double(vector[index]) }
        return Float(sum)
    })
}
