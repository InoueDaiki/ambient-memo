import Foundation
import Testing

/// 共通テストの 1 ケース。テストの名前に area とケースの名前を出し、どのケースで落ちたかがわかるようにする。
struct SpecCase: Sendable, CustomTestStringConvertible {
    let area: String
    let name: String
    let input: SpecObject
    let expected: SpecJSON

    var testDescription: String { "\(area): \(name)" }
}

/// `engine/spec/` の 1 ファイル。
struct SpecFile: Sendable {
    let fileName: String
    let spec: SpecObject

    var area: String {
        get throws { try spec.str("area") }
    }

    var cases: [SpecCase] {
        get throws {
            let area = try area
            return try spec.objects("cases").map { item in
                SpecCase(area: area, name: try item.str("name"), input: try item.obj("input"), expected: try item.value("expected"))
            }
        }
    }

    /// JSON は Package.swift のリソースにせず、このファイルの場所から相対で `engine/spec` を読む。
    static let directory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("spec")

    static func load() throws -> [SpecFile] {
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path)
            .filter { $0.hasSuffix(".json") }
            .sorted()
        return try names.map { name in
            let data = try Data(contentsOf: directory.appendingPathComponent(name))
            return SpecFile(fileName: name, spec: try parseSpecJSON(String(decoding: data, as: UTF8.self)).objectValue)
        }
    }

    /// テストの引数。読めなければ、テストを見つける段階で止める。
    static let allCases: [SpecCase] = {
        do {
            return try load().flatMap { try $0.cases }
        } catch {
            fatalError("engine/spec を読めません: \(error)")
        }
    }()
}

/**
 * `engine/spec/` の共通テストを流す。ケースごとに名前の付いたテストにする。
 * 実行役は `input` から Swift の処理を呼び、結果を JSON にして `expected` と比べる。
 */
struct SpecTests {
    @Test(arguments: SpecFile.allCases)
    func ケース(_ specCase: SpecCase) async throws {
        let handler = try #require(SpecAreas.handlers[specCase.area], "\(specCase.area) の実行役がありません")
        let actual = try await handler(specCase.input)
        let problems = jsonMismatches(specCase.expected, actual)
        #expect(problems.isEmpty, "\(problems.joined(separator: "\n"))\n実際の値: \(actual)")
    }

    @Test func ファイル名とareaが揃いケースの名前が重ならずどのareaにも実行役がある() throws {
        let files = try SpecFile.load()
        #expect(!files.isEmpty)
        for file in files {
            let area = try file.area
            #expect(file.fileName == "\(area).json")
            #expect(SpecAreas.handlers[area] != nil, "\(area) の実行役がありません")
            for key in ["description", "inputSchema", "expectedSchema"] {
                #expect(file.spec[key] != nil, "\(area) に \(key) がありません")
            }
            let names = try file.cases.map(\.name)
            #expect(names.count == Set(names).count, "\(area) のケースの名前が重なっています")
        }
        let areas = Set(files.map { String($0.fileName.dropLast(".json".count)) })
        #expect(Set(SpecAreas.handlers.keys) == areas)
    }
}

typealias SpecHandler = @Sendable (SpecObject) async throws -> SpecJSON

/// area の名前と実行役。area は `engine/spec/<area>.json` のファイル名と同じ。
enum SpecAreas {
    static let handlers: [String: SpecHandler] = [
        "ask-parsers": askParsers,
        "ask-prompts": askPrompts,
        "calendar-date": calendarDate,
        "capture-pipeline": capturePipeline,
        "diagnostics": diagnostics,
        "engine-status": engineStatus,
        "llm-output-parser": llmOutputParser,
        "llm-title-parser": llmTitleParser,
        "memo-question": memoQuestion,
        "model-validation": modelValidation,
        "noise-text-filter": noiseTextFilter,
        "organize-job": organizeJob,
        "organize-policy": organizePolicy,
        "organize-prompts": organizePrompts,
        "quiet-gate": quietGate,
        "segment-backlog": segmentBacklog,
        "self-enrollment": selfEnrollment,
        "speaker-consolidation": speakerWorld,
        "speaker-corrections": speakerWorld,
        "speaker-identifier": speakerWorld,
        "speaker-long-conversation": speakerWorld,
        "speaker-turns": capturePipeline,
        "utterance-chunk": utteranceChunk,
        "utterance-segmenter": utteranceSegmenter,
    ]
}
