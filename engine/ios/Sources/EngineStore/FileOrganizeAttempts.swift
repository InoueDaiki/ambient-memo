import EngineDomain
import Foundation

/**
 * 回数をファイルに残す。Android の `FileOrganizeAttempts` と同じ。発話の id と回数だけで、発話の中身は書かない。
 *
 * スキーマを増やすほどの情報ではなく、失っても数え直すだけなので、DB に入れない。
 * 書き込みは一時ファイルからの名前の変更(`Data.WritingOptions.atomic`)にして、書きかけのファイルを残さない。
 * 中身は Android の Properties ではなく、id から回数への JSON にした。端末をまたいで移さないので形式は揃えない。
 * 同じインスタンスへの同時の呼び出しはロックで順にする。同じファイルを複数のインスタンスやプロセスから同時に書かない。
 *
 * ファイルは [EngineStoreFiles.prepareDatabaseFile] で用意したディレクトリ(DB の隣)に置く。
 * ディレクトリごとバックアップから外れ、iOS では書くたびに completeUntilFirstUserAuthentication で保護する。
 */
public final class FileOrganizeAttempts: OrganizeAttempts, @unchecked Sendable {
    // ファイルの読み書きは lock の中でだけ行う
    private let lock = NSLock()
    private let url: URL

    public init(url: URL) {
        self.url = url
    }

    public func count(firstUtteranceId: Int64) throws -> Int {
        try lock.withLock { try read()[String(firstUtteranceId)] ?? 0 }
    }

    public func set(firstUtteranceId: Int64, count: Int) throws {
        try lock.withLock {
            var counts = try read()
            counts[String(firstUtteranceId)] = count
            try write(counts)
        }
    }

    public func clear(firstUtteranceId: Int64) throws {
        try lock.withLock {
            var counts = try read()
            if counts.removeValue(forKey: String(firstUtteranceId)) != nil { try write(counts) }
        }
    }

    private func read() throws -> [String: Int] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [:] }
        return try JSONDecoder().decode([String: Int].self, from: Data(contentsOf: url))
    }

    private func write(_ counts: [String: Int]) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        var options: Data.WritingOptions = [.atomic]
        #if os(iOS)
        options.insert(.completeFileProtectionUntilFirstUserAuthentication)
        #endif
        try encoder.encode(counts).write(to: url, options: options)
    }
}
