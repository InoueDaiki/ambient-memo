import Foundation

/// DB のファイルの置き場所の用意。
public enum EngineStoreFiles {
    /**
     * [url] に DB のファイルを用意する。ディレクトリとファイルがなければ作る。[EngineDatabase] で開く前に呼ぶ。
     *
     * - DB を置くディレクトリ(`url` の親)と DB のファイルを iCloud のバックアップから外す(`isExcludedFromBackup`)。
     *   Android の `allowBackup=false` と同じ理由。SQLite が作っては消す -wal、-shm と、
     *   [FileOrganizeAttempts] のファイルも外れるように、ディレクトリごと外す。
     *   そのため、親は DB のための専用のディレクトリにする(例: Application Support の下の `EngineStore`)。
     * - iOS では、ディレクトリとファイルの保護を completeUntilFirstUserAuthentication にする。
     *   画面のロック中にも聞き取りを書くので、complete にはしない。ディレクトリに付けると、後から作るファイルにも効く。
     *
     * 何度呼んでもよい。起動のたびに呼べば、外れた印も付け直す。macOS ではバックアップの印だけが効く(Time Machine)。
     */
    public static func prepareDatabaseFile(at url: URL) throws {
        let fileManager = FileManager.default
        var directory = url.deletingLastPathComponent()
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: protection)
        if !fileManager.fileExists(atPath: url.path) {
            guard fileManager.createFile(atPath: url.path, contents: nil, attributes: protection) else {
                throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: url.path])
            }
        }
        #if os(iOS)
        // 前の版で作ったファイルにも付け直す
        for path in [directory.path, url.path] + sidecarPaths(url) where fileManager.fileExists(atPath: path) {
            try fileManager.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: path)
        }
        #endif
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        var file = url
        try file.setResourceValues(values)
    }

    /// SQLite が DB の隣に作るファイル。
    static func sidecarPaths(_ url: URL) -> [String] {
        ["-wal", "-shm", "-journal"].map { url.path + $0 }
    }

    private static var protection: [FileAttributeKey: Any]? {
        #if os(iOS)
        [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        #else
        nil
        #endif
    }
}
