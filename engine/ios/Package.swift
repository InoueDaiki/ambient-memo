// swift-tools-version: 6.0
// エンジンの domain を Swift で書いたもの。Kotlin の engine/domain と同じ挙動にし、engine/spec の共通のテストで揃える。
import PackageDescription

let package = Package(
    name: "AmbientMemoEngine",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "EngineDomain", targets: ["EngineDomain"]),
        .library(name: "EngineDomainTesting", targets: ["EngineDomainTesting"]),
    ],
    targets: [
        // Foundation だけに依存する。UIKit や AVFoundation を使わない
        .target(
            name: "EngineDomain",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        // Fake と、WAV ファイルの音声源。テストからだけ使う
        .target(
            name: "EngineDomainTesting",
            dependencies: ["EngineDomain"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "EngineDomainTests",
            dependencies: ["EngineDomain", "EngineDomainTesting"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
