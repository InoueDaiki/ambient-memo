import Foundation

/// 整理を始めたきっかけ。
public enum OrganizeTrigger: String, Sendable, Hashable, CaseIterable {
    /// 充電中の定期実行。
    case SCHEDULED

    /// 利用者が「今すぐ整理する」を選んだ。
    case MANUAL

    public var name: String { rawValue }
}

/// まとまりの間で確かめる端末の状態と、止める依頼。
public struct OrganizeConditions: Sendable, Hashable {
    public let stopRequested: Bool
    public let isCharging: Bool
    public let isTooHot: Bool

    public init(stopRequested: Bool, isCharging: Bool, isTooHot: Bool) {
        self.stopRequested = stopRequested
        self.isCharging = isCharging
        self.isTooHot = isTooHot
    }
}

/// 整理を止める理由。
public enum OrganizeStopReason: String, Sendable, Hashable, CaseIterable {
    case REQUESTED, TOO_HOT, NOT_CHARGING

    public var name: String { rawValue }
}

extension OrganizeTrigger {
    /**
     * 整理を続けられないときの理由を返す。続けてよければ nil。
     *
     * 手動の実行は利用者が電池を使うと選んだので、充電切れでは止めない。電池残量の下限もない。
     * 熱はどちらでも止める。端末を守るためで、利用者の選択で外すものではない。
     */
    public func stopReason(_ conditions: OrganizeConditions) -> OrganizeStopReason? {
        if conditions.stopRequested { return .REQUESTED }
        if conditions.isTooHot { return .TOO_HOT }
        if self == .SCHEDULED, !conditions.isCharging { return .NOT_CHARGING }
        return nil
    }
}
