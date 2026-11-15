import EngineDomain
import Foundation

/**
 * 調整の値をファイルに残す。Android の `FileTuningStore` と同じ形(`Tuning.encode`)。数だけで、発話や声は書かない。
 *
 * 書き込みは一時ファイルからの名前の変更(`Data.WritingOptions.atomic`)にして、書きかけのファイルを読ませない。
 * スキーマを増やさないので、値を消しても端末のデータは変わらない。読めなければ既定値を使う。
 * ファイルは [FileOrganizeAttempts] と同じく DB の隣に置く。
 */
public final class FileTuningStore: @unchecked Sendable {
    // ファイルの読み書きは lock の中でだけ行う
    private let lock = NSLock()
    private let url: URL

    public init(url: URL) {
        self.url = url
    }

    public func load() -> Tuning {
        lock.withLock {
            guard let data = FileManager.default.contents(atPath: url.path), let text = String(data: data, encoding: .utf8) else {
                return Tuning.DEFAULT
            }
            return Tuning.decode(text)
        }
    }

    public func save(_ tuning: Tuning) throws {
        try lock.withLock {
            if tuning == Tuning.DEFAULT {
                if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
                return
            }
            var options: Data.WritingOptions = [.atomic]
            #if os(iOS)
            options.insert(.completeFileProtectionUntilFirstUserAuthentication)
            #endif
            try Data(tuning.encode().utf8).write(to: url, options: options)
        }
    }
}
