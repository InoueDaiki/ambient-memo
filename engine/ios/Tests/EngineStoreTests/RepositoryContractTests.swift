import EngineDomain
import EngineDomainTesting
import EngineStore
import Foundation
import Testing

// engine/domain/src/testFixtures の RepositoryContracts.kt と、PortContracts.kt の OrganizeAttemptsContract を Swift にしたもの。
// どのテストも Fake と本物の両方に流す。Kotlin の契約を変えたら、ここも同じように変える。

private func utterance(_ startedAtMillis: Int64, _ endedAtMillis: Int64, _ text: String = "本文") throws -> NewUtterance {
    try NewUtterance(
        startedAtMillis: startedAtMillis,
        endedAtMillis: endedAtMillis,
        text: text,
        confidence: nil,
        createdAtMillis: endedAtMillis
    )
}

/// UtteranceRepository の契約。
@Suite final class UtteranceRepositoryContract {
    private let fixture = StoreFixture()

    @Test(arguments: Implementation.allCases)
    func 保存した項目をそのまま読み出せる(_ implementation: Implementation) async throws {
        let repository = try fixture.utterances(implementation)
        let new = try NewUtterance(
            startedAtMillis: 1_000,
            endedAtMillis: 2_500,
            text: "今日は晴れ 🌤 \"引用\" 'です'",
            confidence: 0.75,
            createdAtMillis: 3_000
        )

        let saved = try await repository.insert(new)

        #expect(saved.startedAtMillis == new.startedAtMillis)
        #expect(saved.endedAtMillis == new.endedAtMillis)
        #expect(saved.text == new.text)
        #expect(saved.confidence == new.confidence)
        #expect(saved.createdAtMillis == new.createdAtMillis)
        #expect(try await repository.listOverlapping(fromMillis: 0, toMillis: 10_000) == [saved])
    }

    @Test(arguments: Implementation.allCases)
    func 発話の話者を直すと類似度は消え_ない発話は_false_を返す(_ implementation: Implementation) async throws {
        let repository = try fixture.utterances(implementation)
        let saved = try await repository.insert(
            try NewUtterance(startedAtMillis: 0, endedAtMillis: 100, text: "本文", confidence: nil, createdAtMillis: 100, speakerId: 7, speakerConfidence: 0.625)
        )

        #expect(try await repository.setSpeaker(id: saved.id, speakerId: 8))
        #expect(try await repository.find(id: saved.id)?.speakerId == 8)
        #expect(try await repository.find(id: saved.id)?.speakerConfidence == nil)

        #expect(try await repository.setSpeaker(id: saved.id, speakerId: nil))
        #expect(try await repository.find(id: saved.id)?.speakerId == nil)
        #expect(try await !repository.setSpeaker(id: saved.id + 1, speakerId: 8))
        #expect(try await repository.find(id: saved.id + 1) == nil)
    }

    @Test(arguments: Implementation.allCases)
    func 話者ごとの発話の数を返す(_ implementation: Implementation) async throws {
        let repository = try fixture.utterances(implementation)
        for speakerId in [7, 7, 8, nil] as [Int64?] {
            _ = try await repository.insert(
                try NewUtterance(startedAtMillis: 0, endedAtMillis: 100, text: "本文", confidence: nil, createdAtMillis: 100, speakerId: speakerId)
            )
        }

        #expect(try await repository.countBySpeaker() == [7: 2, 8: 1])
    }

    @Test(arguments: Implementation.allCases)
    func 話者と話者の信頼度を保存して読み出せる(_ implementation: Implementation) async throws {
        let repository = try fixture.utterances(implementation)
        let new = try NewUtterance(startedAtMillis: 0, endedAtMillis: 100, text: "本文", confidence: nil, createdAtMillis: 100, speakerId: 7, speakerConfidence: 0.625)

        let saved = try await repository.insert(new)

        #expect(saved.speakerId == 7)
        #expect(saved.speakerConfidence == 0.625)
        #expect(try await repository.listRecent(limit: 1) == [saved])
    }

    @Test(arguments: Implementation.allCases)
    func まとめて保存すると渡した順に_id_が付き_開始時刻の順に読み出せる(_ implementation: Implementation) async throws {
        let repository = try fixture.utterances(implementation)

        let saved = try await repository.insertAll([try utterance(0, 100, "前"), try utterance(100, 200, "後")])

        #expect(saved.map(\.text) == ["前", "後"])
        #expect(saved[0].id < saved[1].id)
        #expect(try await repository.listOverlapping(fromMillis: 0, toMillis: 1_000) == saved)
    }

    @Test(arguments: Implementation.allCases)
    func 信頼度がなくても保存できる(_ implementation: Implementation) async throws {
        let repository = try fixture.utterances(implementation)

        _ = try await repository.insert(try utterance(0, 100))

        let rows = try await repository.listOverlapping(fromMillis: 0, toMillis: 100)
        #expect(rows.count == 1)
        #expect(rows.first?.confidence == nil)
    }

    @Test(arguments: Implementation.allCases)
    func 保存のたびに別の_id_が付く(_ implementation: Implementation) async throws {
        let repository = try fixture.utterances(implementation)

        let first = try await repository.insert(try utterance(0, 100))
        let second = try await repository.insert(try utterance(0, 100))

        #expect(first.id != second.id)
    }

    @Test(arguments: Implementation.allCases)
    func 開始時刻の順に返し_同時刻は保存した順に返す(_ implementation: Implementation) async throws {
        let repository = try fixture.utterances(implementation)
        _ = try await repository.insert(try utterance(300, 400, "c"))
        _ = try await repository.insert(try utterance(100, 200, "a"))
        _ = try await repository.insert(try utterance(100, 150, "b"))

        let texts = try await repository.listOverlapping(fromMillis: 0, toMillis: 1_000).map(\.text)

        #expect(texts == ["a", "b", "c"])
    }

    @Test(arguments: Implementation.allCases)
    func 範囲と少しでも重なる発話を返し_端が接するだけのものは返さない(_ implementation: Implementation) async throws {
        let repository = try fixture.utterances(implementation)
        _ = try await repository.insert(try utterance(0, 100, "前に接する"))
        _ = try await repository.insert(try utterance(50, 101, "前から重なる"))
        _ = try await repository.insert(try utterance(120, 130, "内側"))
        _ = try await repository.insert(try utterance(199, 300, "後ろへ重なる"))
        _ = try await repository.insert(try utterance(200, 300, "後ろに接する"))
        _ = try await repository.insert(try utterance(0, 1_000, "範囲を含む"))

        let texts = try await repository.listOverlapping(fromMillis: 100, toMillis: 200).map(\.text)

        #expect(texts == ["範囲を含む", "前から重なる", "内側", "後ろへ重なる"])
    }

    @Test(arguments: Implementation.allCases)
    func 該当がなければ空を返す(_ implementation: Implementation) async throws {
        #expect(try await fixture.utterances(implementation).listOverlapping(fromMillis: 0, toMillis: 1_000).isEmpty)
    }

    @Test(arguments: Implementation.allCases)
    func 最近の発話を新しい順に上限まで返し_同時刻は後の保存を先にする(_ implementation: Implementation) async throws {
        let repository = try fixture.utterances(implementation)
        _ = try await repository.insert(try utterance(100, 200, "古い"))
        _ = try await repository.insert(try utterance(300, 400, "新しい"))
        _ = try await repository.insert(try utterance(200, 300, "同時刻の先"))
        _ = try await repository.insert(try utterance(200, 300, "同時刻の後"))

        let texts = try await repository.listRecent(limit: 3).map(\.text)

        #expect(texts == ["新しい", "同時刻の後", "同時刻の先"])
    }

    @Test(arguments: Implementation.allCases)
    func 最近の発話がなければ空を返す(_ implementation: Implementation) async throws {
        #expect(try await fixture.utterances(implementation).listRecent(limit: 10).isEmpty)
    }
}

/// SpeakerDecisionLog の契約。
@Suite final class SpeakerDecisionLogContract {
    private let fixture = StoreFixture()
    private let day: Int64 = 86_400_000

    @Test(arguments: Implementation.allCases)
    func 判定と帯ごとに回数と長さを足す(_ implementation: Implementation) async throws {
        let log = try fixture.decisionLog(implementation)

        try await log.record(
            [
                SpeakerDecisionSample(.SELF, 0.62, 1_000),
                SpeakerDecisionSample(.SELF, 0.64, 2_000),
                SpeakerDecisionSample(.OTHER, 0.1, 500),
            ],
            nowMillis: 10 * day
        )
        try await log.record([SpeakerDecisionSample(.SELF, 0.61, 3_000)], nowMillis: 11 * day)

        #expect(
            try await log.summarize(fromMillis: 10 * day + 5) == [
                SpeakerDecisionBucket(.OTHER, 2, 1, 500),
                SpeakerDecisionBucket(.SELF, 12, 3, 6_000),
            ]
        )
    }

    @Test(arguments: Implementation.allCases)
    func 範囲より前の日は数えず_消せる(_ implementation: Implementation) async throws {
        let log = try fixture.decisionLog(implementation)
        try await log.record([SpeakerDecisionSample(.UNKNOWN, 0.45, 1_000)], nowMillis: 3 * day)
        try await log.record([SpeakerDecisionSample(.UNKNOWN, 0.45, 2_000)], nowMillis: 9 * day)

        #expect(try await log.summarize(fromMillis: 5 * day).map(\.runs) == [1])

        try await log.prune(beforeMillis: 5 * day)

        #expect(try await log.summarize(fromMillis: 0).map(\.runs) == [1])
    }

    @Test(arguments: Implementation.allCases)
    func 範囲の外の類似度も帯に収める(_ implementation: Implementation) async throws {
        let log = try fixture.decisionLog(implementation)

        try await log.record([SpeakerDecisionSample(.OTHER, -0.3, 1), SpeakerDecisionSample(.SELF, 1, 1)], nowMillis: day)

        #expect(try await log.summarize(fromMillis: 0).map(\.bucket) == [0, 19])
    }
}

/// SpeakerRepository の契約。
@Suite final class SpeakerRepositoryContract {
    private let fixture = StoreFixture()
    private let model = "model-1"
    private let first = SpeakerEmbedding([0.6, 0.8], "model-1")
    private let second = SpeakerEmbedding([1, 0], "model-1")

    @Test(arguments: Implementation.allCases)
    func 最初は誰もいない(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)

        #expect(try await repository.listSpeakers().isEmpty)
        #expect(try await repository.listEmbeddings(modelVersion: model, limit: 10).isEmpty)
    }

    @Test(arguments: Implementation.allCases)
    func 自分の特徴量を読み出せる(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)

        let selfSpeaker = try await repository.saveSelfEmbedding(first, sampleCount: 3, nowMillis: 100)

        #expect(selfSpeaker.isSelf)
        #expect(selfSpeaker.displayName == "自分")
        #expect(selfSpeaker.nameSource == .SELF)
        #expect(try await repository.listSpeakers() == [selfSpeaker])
        let stored = try await repository.listEmbeddings(modelVersion: model, limit: 10)
        try #require(stored.count == 1)
        #expect(stored[0].speakerId == selfSpeaker.id)
        #expect(stored[0].isSelf)
        #expect(stored[0].sampleCount == 3)
        #expect(stored[0].vector == first.vector)
    }

    @Test(arguments: Implementation.allCases)
    func 登録し直すと自分は増えず_特徴量を置き換える(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        let before = try await repository.saveSelfEmbedding(first, sampleCount: 3, nowMillis: 100)

        let after = try await repository.saveSelfEmbedding(second, sampleCount: 4, nowMillis: 200)

        #expect(before.id == after.id)
        #expect(try await repository.listSpeakers().count == 1)
        #expect(try await repository.listEmbeddings(modelVersion: model, limit: 10).map(\.vector) == [second.vector])
    }

    @Test(arguments: Implementation.allCases)
    func 育てた声は版ごとに読めて_登録し直すと消える(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        _ = try await repository.saveSelfEmbedding(first, sampleCount: 3, nowMillis: 100)

        try await repository.saveSelfAdaptation(SelfAdaptation(second.vector, 5_000, model), nowMillis: 200)

        let saved = try #require(try await repository.findSelfAdaptation(modelVersion: model))
        #expect(saved.vector == second.vector)
        #expect(saved.voicedMillis == 5_000)
        #expect(try await repository.findSelfAdaptation(modelVersion: "model-2") == nil)

        _ = try await repository.saveSelfEmbedding(second, sampleCount: 3, nowMillis: 300)

        #expect(try await repository.findSelfAdaptation(modelVersion: model) == nil)
    }

    @Test(arguments: Implementation.allCases)
    func 仮の話者の名前は使われていない最小の番号にする(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        let firstSpeaker = try await repository.createProvisionalSpeaker(first, nowMillis: 100)
        let secondSpeaker = try await repository.createProvisionalSpeaker(first, nowMillis: 100)
        _ = try await repository.createProvisionalSpeaker(first, nowMillis: 100)

        try await repository.mergeSpeakers([SpeakerMerge(secondSpeaker.id, firstSpeaker.id, nil)], nowMillis: 200)

        #expect(try await repository.createProvisionalSpeaker(first, nowMillis: 300).displayName == "話者2")
    }

    @Test(arguments: Implementation.allCases)
    func 特徴量のない仮の話者を作れる(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)

        let speaker = try await repository.createProvisionalSpeakerWithoutVoice(nowMillis: 100)

        #expect(speaker.displayName == "話者1")
        #expect(try await repository.listEmbeddings(modelVersion: model, limit: 10).isEmpty)
    }

    @Test(arguments: Implementation.allCases)
    func 統合すると統合元と特徴量が消え_統合先の特徴量が変わる(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        let into = try await repository.createProvisionalSpeaker(first, nowMillis: 100, voicedMillis: 1_000)
        let from = try await repository.createProvisionalSpeaker(second, nowMillis: 100, voicedMillis: 2_000)
        let merged = MergedEmbedding(SpeakerEmbedding(second.vector, model), 2, 3_000)

        try await repository.mergeSpeakers([SpeakerMerge(from.id, into.id, merged)], nowMillis: 200)

        #expect(try await repository.listSpeakers().map(\.id) == [into.id])
        let stored = try await repository.listEmbeddings(modelVersion: model, limit: 10)
        try #require(stored.count == 1)
        #expect(stored[0].speakerId == into.id)
        #expect(stored[0].sampleCount == 2)
        #expect(stored[0].voicedMillis == 3_000)
        #expect(stored[0].updatedAtMillis == 200)
    }

    @Test(arguments: Implementation.allCases)
    func 自分は統合元にならず_いない統合元は飛ばす(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        let selfSpeaker = try await repository.saveSelfEmbedding(first, sampleCount: 3, nowMillis: 100)
        let other = try await repository.createProvisionalSpeaker(second, nowMillis: 100)

        try await repository.mergeSpeakers(
            [SpeakerMerge(selfSpeaker.id, other.id, nil), SpeakerMerge(selfSpeaker.id + 99, other.id, nil)],
            nowMillis: 200
        )

        #expect(try await repository.listSpeakers().count == 2)
    }

    @Test(arguments: Implementation.allCases)
    func リセットすると自分も含めて誰もいなくなり_次の話者は話者1から始まる(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        _ = try await repository.saveSelfEmbedding(SpeakerEmbedding([1, 0], "v1"), sampleCount: 3, nowMillis: 100)
        try await repository.saveSelfAdaptation(SelfAdaptation([1, 0], 1_000, "v1"), nowMillis: 100)
        _ = try await repository.createProvisionalSpeaker(SpeakerEmbedding([0, 1], "v1"), nowMillis: 100)

        try await repository.resetSpeakers()

        #expect(try await repository.listSpeakers().isEmpty)
        #expect(try await repository.listEmbeddings(modelVersion: "v1", limit: 10).isEmpty)
        #expect(try await repository.findSelfAdaptation(modelVersion: "v1") == nil)
        let next = try await repository.createProvisionalSpeaker(SpeakerEmbedding([0, 1], "v1"), nowMillis: 200)
        #expect(next.displayName == "話者1")
    }

    @Test(arguments: Implementation.allCases)
    func 振り直すと仮の話者だけを_id_の順に番号を付ける(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        var speakers: [Speaker] = []
        for _ in 0..<4 { speakers.append(try await repository.createProvisionalSpeaker(first, nowMillis: 100)) }
        _ = try await repository.setManualName(speakerId: speakers[1].id, name: "話者1")

        try await repository.mergeSpeakers([SpeakerMerge(speakers[2].id, speakers[0].id, nil)], nowMillis: 200, renumberProvisional: true)

        #expect(try await repository.listSpeakers().map(\.displayName) == ["話者2", "話者1", "話者3"])
    }

    @Test(arguments: Implementation.allCases)
    func 特徴量に長さと名前の由来と更新時刻が付く(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        let speaker = try await repository.createProvisionalSpeaker(first, nowMillis: 100, voicedMillis: 2_500)

        let stored = try await repository.listEmbeddings(modelVersion: model, limit: 10)

        try #require(stored.count == 1)
        #expect(stored[0].speakerId == speaker.id)
        #expect(stored[0].voicedMillis == 2_500)
        #expect(stored[0].nameSource == .PROVISIONAL)
        #expect(stored[0].updatedAtMillis == 100)
    }

    @Test(arguments: Implementation.allCases)
    func 仮の話者に順番の名前を付ける(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        _ = try await repository.saveSelfEmbedding(first, sampleCount: 3, nowMillis: 100)

        var names: [Speaker] = []
        for index in 0..<2 { names.append(try await repository.createProvisionalSpeaker(second, nowMillis: 200 + Int64(index))) }

        #expect(names.map(\.displayName) == ["話者1", "話者2"])
        #expect(names.allSatisfy { !$0.isSelf })
        #expect(names.first?.nameSource == .PROVISIONAL)
    }

    @Test(arguments: Implementation.allCases)
    func 別の版の特徴量は返さない(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        _ = try await repository.saveSelfEmbedding(first, sampleCount: 3, nowMillis: 100)

        #expect(try await repository.listEmbeddings(modelVersion: "model-2", limit: 10).isEmpty)
    }

    @Test(arguments: Implementation.allCases)
    func 別の版で登録し直しても前の版は残る(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        _ = try await repository.saveSelfEmbedding(first, sampleCount: 3, nowMillis: 100)

        _ = try await repository.saveSelfEmbedding(SpeakerEmbedding(second.vector, "model-2"), sampleCount: 3, nowMillis: 200)

        #expect(try await repository.listEmbeddings(modelVersion: model, limit: 10).map(\.vector) == [first.vector])
    }

    @Test(arguments: Implementation.allCases)
    func 上限があっても自分を残し_更新の新しい話者を優先する(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        let old = try await repository.createProvisionalSpeaker(first, nowMillis: 100)
        let recent = try await repository.createProvisionalSpeaker(first, nowMillis: 200)
        let selfSpeaker = try await repository.saveSelfEmbedding(first, sampleCount: 3, nowMillis: 50)

        try await repository.updateEmbedding(speakerId: old.id, second, sampleCount: 2, nowMillis: 300)
        let ids = try await repository.listEmbeddings(modelVersion: model, limit: 2).map(\.speakerId)
        try await repository.updateEmbedding(speakerId: recent.id, second, sampleCount: 2, nowMillis: 400)
        let after = try await repository.listEmbeddings(modelVersion: model, limit: 2).map(\.speakerId)

        #expect(ids == [selfSpeaker.id, old.id])
        #expect(after == [selfSpeaker.id, recent.id])
    }

    @Test(arguments: Implementation.allCases)
    func 特徴量を更新すると標本の数も変わる(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        let speaker = try await repository.createProvisionalSpeaker(first, nowMillis: 100)

        try await repository.updateEmbedding(speakerId: speaker.id, second, sampleCount: 2, nowMillis: 200)

        let stored = try await repository.listEmbeddings(modelVersion: model, limit: 10)
        try #require(stored.count == 1)
        #expect(stored[0].sampleCount == 2)
        #expect(stored[0].vector == second.vector)
    }

    @Test(arguments: Implementation.allCases)
    func 推定の名前は仮の名前と推定の名前を置き換える(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        let speaker = try await repository.createProvisionalSpeaker(first, nowMillis: 100)

        #expect(try await repository.setInferredName(speakerId: speaker.id, name: "田中"))
        #expect(try await repository.setInferredName(speakerId: speaker.id, name: "佐藤"))

        let renamed = try await repository.listSpeakers()
        #expect(renamed.map(\.displayName) == ["佐藤"])
        #expect(renamed.map(\.nameSource) == [.INFERRED])
    }

    @Test(arguments: Implementation.allCases)
    func 推定の名前で手動の名前を上書きしない(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        let speaker = try await repository.createProvisionalSpeaker(first, nowMillis: 100)
        #expect(try await repository.setManualName(speakerId: speaker.id, name: "母"))

        #expect(try await !repository.setInferredName(speakerId: speaker.id, name: "田中"))

        let kept = try await repository.listSpeakers()
        #expect(kept.map(\.displayName) == ["母"])
        #expect(kept.map(\.nameSource) == [.MANUAL])
    }

    @Test(arguments: Implementation.allCases)
    func 推定の名前で自分を変えない(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        let selfSpeaker = try await repository.saveSelfEmbedding(first, sampleCount: 3, nowMillis: 100)

        #expect(try await !repository.setInferredName(speakerId: selfSpeaker.id, name: "田中"))

        #expect(try await repository.listSpeakers().map(\.displayName) == ["自分"])
    }

    @Test(arguments: Implementation.allCases)
    func 手動の名前は推定の名前を置き換え_自分にも付けられる(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)
        let selfSpeaker = try await repository.saveSelfEmbedding(first, sampleCount: 3, nowMillis: 100)
        let speaker = try await repository.createProvisionalSpeaker(first, nowMillis: 100)
        _ = try await repository.setInferredName(speakerId: speaker.id, name: "田中")

        #expect(try await repository.setManualName(speakerId: speaker.id, name: "母"))
        #expect(try await repository.setManualName(speakerId: selfSpeaker.id, name: "陽菜"))

        #expect(try await repository.listSpeakers().map(\.displayName) == ["陽菜", "母"])
        #expect(try await repository.listSpeakers().first?.isSelf == true)
    }

    @Test(arguments: Implementation.allCases)
    func いない話者の名前は変えられない(_ implementation: Implementation) async throws {
        let repository = try fixture.speakers(implementation)

        #expect(try await !repository.setManualName(speakerId: 404, name: "母"))
        #expect(try await !repository.setInferredName(speakerId: 404, name: "田中"))
    }
}

/// CaptureGapRepository の契約。
@Suite final class CaptureGapRepositoryContract {
    private let fixture = StoreFixture()

    @Test(arguments: Implementation.allCases)
    func すべての理由を保存して読み出せる(_ implementation: Implementation) async throws {
        let repository = try fixture.captureGaps(implementation)
        let gaps = try CaptureGapReason.allCases.enumerated().map { index, reason in
            try CaptureGap(Int64(index) * 100, Int64(index) * 100 + 50, reason)
        }

        for gap in gaps { try await repository.insert(gap) }

        #expect(try await repository.listOverlapping(fromMillis: 0, toMillis: 10_000) == gaps)
    }

    @Test(arguments: Implementation.allCases)
    func 開始時刻の順に返す(_ implementation: Implementation) async throws {
        let repository = try fixture.captureGaps(implementation)
        let later = try CaptureGap(300, 400, .PROCESSING_FAILED)
        let earlier = try CaptureGap(100, 200, .PROCESSING_BACKLOG)
        try await repository.insert(later)
        try await repository.insert(earlier)

        #expect(try await repository.listOverlapping(fromMillis: 0, toMillis: 1_000) == [earlier, later])
    }

    @Test(arguments: Implementation.allCases)
    func 範囲と少しでも重なる欠落を返し_端が接するだけのものは返さない(_ implementation: Implementation) async throws {
        let repository = try fixture.captureGaps(implementation)
        try await repository.insert(try CaptureGap(0, 100, .PROCESSING_BACKLOG))
        let overlapping = try CaptureGap(99, 200, .PROCESSING_BACKLOG)
        try await repository.insert(overlapping)
        try await repository.insert(try CaptureGap(200, 300, .PROCESSING_BACKLOG))

        #expect(try await repository.listOverlapping(fromMillis: 100, toMillis: 200) == [overlapping])
    }

    @Test(arguments: Implementation.allCases)
    func 同じ内容の欠落を重ねて保存できる(_ implementation: Implementation) async throws {
        let repository = try fixture.captureGaps(implementation)
        let gap = try CaptureGap(0, 100, .PIPELINE_STOPPED)

        try await repository.insert(gap)
        try await repository.insert(gap)

        #expect(try await repository.listOverlapping(fromMillis: 0, toMillis: 100) == [gap, gap])
    }
}

/// MemoRepository の契約。発話は同じ保存先の UtteranceRepository から入れる。
@Suite final class MemoRepositoryContract {
    private let fixture = StoreFixture()

    private typealias Stores = (utterances: any UtteranceRepository, memos: any MemoRepository)

    private func utterance(_ stores: Stores, _ startedAtMillis: Int64, _ text: String = "本文") async throws -> Int64 {
        try await stores.utterances.insert(
            try NewUtterance(
                startedAtMillis: startedAtMillis,
                endedAtMillis: startedAtMillis + 10,
                text: text,
                confidence: nil,
                createdAtMillis: startedAtMillis + 10
            )
        ).id
    }

    private func memo(
        _ utteranceIds: Int64...,
        startedAtMillis: Int64 = 100,
        endedAtMillis: Int64 = 200,
        title: String? = "題名",
        summary: String? = "- 要約",
        createdAtMillis: Int64 = 1_000
    ) throws -> NewMemo {
        try NewMemo(
            startedAtMillis: startedAtMillis,
            endedAtMillis: endedAtMillis,
            title: title,
            summary: summary,
            modelVersion: "fake-1",
            createdAtMillis: createdAtMillis,
            utteranceIds: utteranceIds
        )
    }

    private func unorganizedIds(_ stores: Stores) async throws -> [Int64] {
        try await stores.memos.listUnorganizedUtterances().map(\.id)
    }

    @Test(arguments: Implementation.allCases)
    func 未処理の発話を開始時刻の順に返す(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let later = try await utterance(stores, 200)
        let earlier = try await utterance(stores, 100)

        #expect(try await unorganizedIds(stores) == [earlier, later])
    }

    @Test(arguments: Implementation.allCases)
    func 記録したメモと根拠をそのまま読み出せる(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let first = try await utterance(stores, 100)
        let second = try await utterance(stores, 200)

        let saved = try #require(try await stores.memos.recordMemo(try memo(second, first)))

        #expect(try await stores.memos.listMemos() == [saved])
        #expect(try await stores.memos.listUtteranceIds(memoId: saved.id) == [first, second])
    }

    @Test(arguments: Implementation.allCases)
    func 要約に失敗したメモも読み出せる(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let failed = try memo(try await utterance(stores, 100), title: nil, summary: nil)

        _ = try await stores.memos.recordMemo(failed)

        let read = try await stores.memos.listMemos()
        try #require(read.count == 1)
        #expect(read[0].title == nil)
        #expect(read[0].summary == nil)
    }

    @Test(arguments: Implementation.allCases)
    func 記録した発話は未処理でなくなる(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let recorded = try await utterance(stores, 100)
        let rest = try await utterance(stores, 200)

        _ = try await stores.memos.recordMemo(try memo(recorded))

        #expect(try await unorganizedIds(stores) == [rest])
    }

    @Test(arguments: Implementation.allCases)
    func 処理済みの発話を含むメモは何も書かない(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let recorded = try await utterance(stores, 100)
        let rest = try await utterance(stores, 200)
        _ = try await stores.memos.recordMemo(try memo(recorded))

        let result = try await stores.memos.recordMemo(try memo(rest, recorded))

        #expect(result == nil)
        #expect(try await stores.memos.listMemos().count == 1)
        #expect(try await unorganizedIds(stores) == [rest])
    }

    @Test(arguments: Implementation.allCases)
    func 存在しない発話を含むメモは何も書かない(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let utterance = try await utterance(stores, 100)

        #expect(try await stores.memos.recordMemo(try memo(utterance, utterance + 1)) == nil)

        #expect(try await stores.memos.listMemos().isEmpty)
        #expect(try await unorganizedIds(stores) == [utterance])
    }

    @Test(arguments: Implementation.allCases)
    func メモを作らずに処理済みにできて_繰り返しても変わらない(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let skipped = try await utterance(stores, 100)
        let rest = try await utterance(stores, 200)

        try await stores.memos.markOrganized([skipped], organizedAtMillis: 1_000)
        try await stores.memos.markOrganized([skipped], organizedAtMillis: 2_000)

        #expect(try await unorganizedIds(stores) == [rest])
        #expect(try await stores.memos.listMemos().isEmpty)
    }

    @Test(arguments: Implementation.allCases)
    func メモを開始時刻の順に返す(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        _ = try await stores.memos.recordMemo(try memo(try await utterance(stores, 300), startedAtMillis: 300, endedAtMillis: 400))
        _ = try await stores.memos.recordMemo(try memo(try await utterance(stores, 100), startedAtMillis: 100, endedAtMillis: 200))

        #expect(try await stores.memos.listMemos().map(\.startedAtMillis) == [100, 300])
    }

    @Test(arguments: Implementation.allCases)
    func 他のメモの根拠は混ざらない(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let first = try await utterance(stores, 100)
        let second = try await utterance(stores, 200)
        let saved = try #require(try await stores.memos.recordMemo(try memo(first)))
        let other = try #require(try await stores.memos.recordMemo(try memo(second)))

        #expect(try await stores.memos.listUtteranceIds(memoId: saved.id) == [first])
        #expect(try await stores.memos.listUtteranceIds(memoId: other.id) == [second])
    }

    @Test(arguments: Implementation.allCases)
    func 最近のメモは新しい順に_limit_件まで返し_同時刻は後から記録したものを先にする(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let oldest = try #require(try await stores.memos.recordMemo(try memo(try await utterance(stores, 1), startedAtMillis: 100, endedAtMillis: 110)))
        let first = try #require(try await stores.memos.recordMemo(try memo(try await utterance(stores, 2), startedAtMillis: 200, endedAtMillis: 210)))
        let second = try #require(try await stores.memos.recordMemo(try memo(try await utterance(stores, 3), startedAtMillis: 200, endedAtMillis: 210)))

        #expect(try await stores.memos.listRecentMemos(limit: 2) == [second, first])
        #expect(try await stores.memos.listRecentMemos(limit: 10) == [second, first, oldest])
    }

    @Test(arguments: Implementation.allCases)
    func メモを_id_で引ける(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let saved = try #require(try await stores.memos.recordMemo(try memo(try await utterance(stores, 100))))

        #expect(try await stores.memos.findMemo(memoId: saved.id) == saved)
        #expect(try await stores.memos.findMemo(memoId: saved.id + 1) == nil)
    }

    @Test(arguments: Implementation.allCases)
    func メモの根拠の発話を開始時刻の順に返す(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let later = try await utterance(stores, 200)
        let earlier = try await utterance(stores, 100)
        let other = try await utterance(stores, 300)
        let saved = try #require(try await stores.memos.recordMemo(try memo(later, earlier)))
        _ = try await stores.memos.recordMemo(try memo(other))

        #expect(try await stores.memos.listMemoUtterances(memoId: saved.id).map(\.id) == [earlier, later])
    }

    @Test(arguments: Implementation.allCases)
    func 検索はタイトル_要約_根拠の発話の本文のどれかに語を含むメモを新しい順に返す(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let byTitle = try #require(try await stores.memos.recordMemo(
            try memo(try await utterance(stores, 1), startedAtMillis: 100, endedAtMillis: 200, title: "歯医者の予約", summary: "- 要約")
        ))
        let bySummary = try #require(try await stores.memos.recordMemo(
            try memo(try await utterance(stores, 2), startedAtMillis: 200, endedAtMillis: 300, title: "題名", summary: "- 歯医者は十時")
        ))
        let byUtterance = try #require(try await stores.memos.recordMemo(
            try memo(try await utterance(stores, 3, "明日は歯医者だね"), startedAtMillis: 300, endedAtMillis: 400, title: nil, summary: nil)
        ))
        _ = try await stores.memos.recordMemo(
            try memo(try await utterance(stores, 4, "週末の予定"), startedAtMillis: 400, endedAtMillis: 500)
        )

        #expect(
            try await stores.memos.searchMemos(term: "歯医者", fromMillis: Int64.min, toMillis: Int64.max, limit: 10)
                == [byUtterance, bySummary, byTitle]
        )
        #expect(try await stores.memos.searchMemos(term: "歯医者", fromMillis: Int64.min, toMillis: Int64.max, limit: 1) == [byUtterance])
    }

    @Test(arguments: Implementation.allCases)
    func 検索は英字の大文字と小文字を区別せず_ほかの文字は区別する(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let saved = try #require(try await stores.memos.recordMemo(try memo(try await utterance(stores, 1, "PDF を送る"), title: "ｶﾀｶﾅ")))

        #expect(try await stores.memos.searchMemos(term: "pdf", fromMillis: Int64.min, toMillis: Int64.max, limit: 10) == [saved])
        #expect(try await stores.memos.searchMemos(term: "カタカナ", fromMillis: Int64.min, toMillis: Int64.max, limit: 10).isEmpty)
    }

    @Test(arguments: Implementation.allCases)
    func 検索は期間に重なるメモだけを返し_空の語はすべてに当たる(_ implementation: Implementation) async throws {
        let stores = try fixture.memoStores(implementation)
        let before = try #require(try await stores.memos.recordMemo(
            try memo(try await utterance(stores, 1, "a"), startedAtMillis: 0, endedAtMillis: 99)
        ))
        let edge = try #require(try await stores.memos.recordMemo(
            try memo(try await utterance(stores, 2, "b"), startedAtMillis: 50, endedAtMillis: 100)
        ))
        let inside = try #require(try await stores.memos.recordMemo(
            try memo(try await utterance(stores, 3, "c"), startedAtMillis: 150, endedAtMillis: 160)
        ))
        _ = try await stores.memos.recordMemo(try memo(try await utterance(stores, 4, "d"), startedAtMillis: 200, endedAtMillis: 250))

        #expect(try await stores.memos.searchMemos(term: "", fromMillis: 100, toMillis: 200, limit: 10) == [inside, edge])
        #expect(try await stores.memos.searchMemos(term: "", fromMillis: 0, toMillis: 50, limit: 10) == [before])
    }
}

/// OrganizeAttempts の契約(PortContracts.kt の OrganizeAttemptsContract)。
@Suite final class OrganizeAttemptsContract {
    private let fixture = StoreFixture()

    @Test(arguments: Implementation.allCases)
    func 記録がなければ_0_回(_ implementation: Implementation) throws {
        #expect(try fixture.organizeAttempts(implementation).count(firstUtteranceId: 1) == 0)
    }

    @Test(arguments: Implementation.allCases)
    func まとまりごとに回数を残し_消したまとまりだけ_0_回に戻る(_ implementation: Implementation) throws {
        let attempts = fixture.organizeAttempts(implementation)

        try attempts.set(firstUtteranceId: 1, count: 2)
        try attempts.set(firstUtteranceId: 2, count: 1)
        try attempts.set(firstUtteranceId: 1, count: 3)
        try attempts.clear(firstUtteranceId: 2)
        try attempts.clear(firstUtteranceId: 99)

        #expect(try attempts.count(firstUtteranceId: 1) == 3)
        #expect(try attempts.count(firstUtteranceId: 2) == 0)
    }
}
