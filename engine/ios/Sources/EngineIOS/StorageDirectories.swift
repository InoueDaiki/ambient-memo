import Foundation

/**
 * エンジンが書くファイルの場所。すべて Application Support の下に置き、iCloud のバックアップから外す。
 *
 * 画面のロック中にも書くので、ファイルの保護は `completeUntilFirstUserAuthentication` にする(docs/設計メモ.md の「iOS」)。
 * 音声はここにも書かない。置くのはモデル、DB、動作の記録、整理の回数だけ。
 */
public enum StorageDirectories {
    /// Application Support。なければ作る。
    public static func applicationSupport() throws -> URL {
        let url = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        try excludeFromBackup(url)
        return url
    }

    public static func models() throws -> URL { try subdirectory("models") }

    /// 取得の作業の場所。[models] と同じファイルシステムに置き、名前の変更で移す。
    public static func modelDownloads(_ name: String) throws -> URL { try subdirectory(name) }

    public static func diagnostics() throws -> URL { try subdirectory("diagnostics") }

    public static func database() throws -> URL { try subdirectory("database") }

    /// LiteRT-LM が読み込みの結果を置く場所。
    public static func llmCache() throws -> URL { try subdirectory("llm") }

    static func subdirectory(_ name: String) throws -> URL {
        let url = try applicationSupport().appendingPathComponent(name, isDirectory: true)
        try prepare(url)
        return url
    }

    /// ディレクトリを作り、バックアップから外し、ロック中も書けるようにする。
    static func prepare(_ url: URL) throws {
        try FileManager.default.createDirectory(
            at: url,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        try excludeFromBackup(url)
    }

    static func excludeFromBackup(_ url: URL) throws {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var target = url
        try target.setResourceValues(values)
    }
}
