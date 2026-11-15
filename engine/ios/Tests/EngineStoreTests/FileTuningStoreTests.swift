import EngineDomain
@testable import EngineStore
import Foundation
import Testing

struct FileTuningStoreTests {
    private let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("tuning-\(UUID().uuidString).txt")

    @Test func ファイルがなければ既定値() {
        #expect(FileTuningStore(url: url).load() == Tuning.DEFAULT)
    }

    @Test func 保存した値を別の読み手が読める() throws {
        defer { try? FileManager.default.removeItem(at: url) }
        let tuning = try Tuning.of(["maxProvisionalSpeakers": 6, "quietGateDbfs": -60])

        try FileTuningStore(url: url).save(tuning)

        let loaded = FileTuningStore(url: url).load()
        #expect(loaded == tuning)
        #expect(loaded.speakerConfig().maxProvisionalSpeakers == 6)
        #expect(loaded[.QUIET_GATE] == -60)
    }

    @Test func 既定値に戻すとファイルを消す() throws {
        let store = FileTuningStore(url: url)
        try store.save(try Tuning.of(["minTopicLines": 2]))

        try store.save(Tuning.DEFAULT)

        #expect(!FileManager.default.fileExists(atPath: url.path))
        #expect(store.load() == Tuning.DEFAULT)
    }

    @Test func 壊れたファイルは既定値として読む() throws {
        defer { try? FileManager.default.removeItem(at: url) }
        try Data([0xFF, 0xFE, 0x00]).write(to: url)

        #expect(FileTuningStore(url: url).load() == Tuning.DEFAULT)
    }
}
