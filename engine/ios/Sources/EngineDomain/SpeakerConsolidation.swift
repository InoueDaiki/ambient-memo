import Foundation

/**
 * 増えすぎた仮の話者をまとめ直す。聞き取りを始める前に呼ぶ。
 *
 * 上限以下なら何もしないので、何度呼んでも結果は変わらない。上限を超えていたら、近い順に統合して上限まで減らす。
 * 手で名付けた話者、推定の名前の話者、自分は対象にしない。発話の話者と特徴量は 1 つのトランザクションで付け替える。
 * 仮の話者には自分の声が入っていることもあるが、自分へは自動で統合しない。手で直す画面に任せる。
 */
public final class SpeakerConsolidation: Sendable {
    private let speakers: any SpeakerRepository
    private let modelVersion: String
    private let clock: any Clock
    private let config: SpeakerConfig

    public init(speakers: any SpeakerRepository, modelVersion: String, clock: any Clock, config: SpeakerConfig = .default) {
        self.speakers = speakers
        self.modelVersion = modelVersion
        self.clock = clock
        self.config = config
    }

    /// まとめ直した仮の話者の数を返す。
    public func run() async throws -> Int {
        let embeddings = try await speakers.listEmbeddings(modelVersion: modelVersion, limit: Int(Int32.max))
        let merges = try planConsolidation(embeddings, config, modelVersion)
        if merges.isEmpty { return 0 }
        try await speakers.mergeSpeakers(merges, nowMillis: clock.nowMillis(), renumberProvisional: true)
        return merges.count
    }
}

private struct Cluster {
    let members: [StoredSpeakerEmbedding]
    let vector: [Float]

    var voicedMillis: Int64 { members.reduce(0) { $0 + $1.voicedMillis } }

    /// 古い話者の id と名前を残す。
    var survivor: StoredSpeakerEmbedding { members.kotlinMinBy(\.speakerId)! }
}

/**
 * 統合の計画を作る。上限を超えているときだけ、最も近い 2 つを統合することを上限に収まるまで繰り返す。
 * 収まった後も、[SpeakerConfig.consolidationThreshold] 以上に近い組は統合する。
 */
func planConsolidation(
    _ embeddings: [StoredSpeakerEmbedding],
    _ config: SpeakerConfig,
    _ modelVersion: String
) throws -> [SpeakerMerge] {
    let provisional = embeddings.filter { !$0.isSelf && $0.nameSource == .PROVISIONAL }
    if provisional.count <= config.maxProvisionalSpeakers { return [] }

    var clusters = provisional.map { Cluster(members: [$0], vector: $0.vector) }
    var pair = try mergeablePair(clusters, config)
    while case let (first, second)? = pair {
        // 2 つが完全に打ち消し合うときだけ nil になる。そこで止める
        if let merged = try merge(clusters[first], clusters[second]) {
            clusters.remove(at: second)
            clusters.remove(at: first)
            clusters.append(merged)
            pair = try mergeablePair(clusters, config)
        } else {
            pair = nil
        }
    }
    return clusters.filter { $0.members.count > 1 }.flatMap { mergesOf($0, modelVersion) }
}

/// 次に統合する組を返す。上限を超えている間は最も近い組、収まったらしきい値以上の組だけ。
private func mergeablePair(_ clusters: [Cluster], _ config: SpeakerConfig) throws -> (Int, Int)? {
    var best: (Int, Int)?
    var bestScore = -Float.infinity
    for first in clusters.indices {
        for second in (first + 1)..<max(clusters.count, first + 1) {
            let score = try similarity(clusters[first].vector, clusters[second].vector)
            if score > bestScore {
                best = (first, second)
                bestScore = score
            }
        }
    }
    let isOver = clusters.count > config.maxProvisionalSpeakers
    return isOver || bestScore >= config.consolidationThreshold ? best : nil
}

private func merge(_ first: Cluster, _ second: Cluster) throws -> Cluster? {
    // 長さのない古い特徴量でも 0 で割らないように、最低の重みを付ける
    let firstMillis = max(first.voicedMillis, 1)
    let secondMillis = max(second.voicedMillis, 1)
    guard let vector = try weightedMean(first.vector, firstMillis, second.vector, secondMillis) else { return nil }
    return Cluster(members: first.members + second.members, vector: vector)
}

private func mergesOf(_ cluster: Cluster, _ modelVersion: String) -> [SpeakerMerge] {
    let into = cluster.survivor
    let merged = MergedEmbedding(
        SpeakerEmbedding(cluster.vector, modelVersion),
        cluster.members.reduce(0) { $0 + $1.sampleCount },
        cluster.voicedMillis
    )
    return cluster.members.filter { $0.speakerId != into.speakerId }
        .map { SpeakerMerge($0.speakerId, into.speakerId, merged) }
}
