import Foundation

public struct EngineStatus: Sendable, Hashable {
    public let engineName: String
    public let apiVersion: Int

    public init(engineName: String, apiVersion: Int) {
        self.engineName = engineName
        self.apiVersion = apiVersion
    }
}

public func currentEngineStatus() -> EngineStatus {
    EngineStatus(engineName: "ambient-memo-engine", apiVersion: 1)
}
