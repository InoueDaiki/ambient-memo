package dev.ambientmemo.engine.domain

/** 整理を始めたきっかけ。 */
enum class OrganizeTrigger {
    /** 充電中の定期実行。 */
    SCHEDULED,

    /** 利用者が「今すぐ整理する」を選んだ。 */
    MANUAL,
}

/** まとまりの間で確かめる端末の状態と、止める依頼。 */
data class OrganizeConditions(
    val stopRequested: Boolean,
    val isCharging: Boolean,
    val isTooHot: Boolean,
)

/** 整理を止める理由。 */
enum class OrganizeStopReason { REQUESTED, TOO_HOT, NOT_CHARGING }

/**
 * 整理を続けられないときの理由を返す。続けてよければ null。
 *
 * 手動の実行は利用者が電池を使うと選んだので、充電切れでは止めない。電池残量の下限もない。
 * 熱はどちらでも止める。端末を守るためで、利用者の選択で外すものではない。
 */
fun OrganizeTrigger.stopReason(conditions: OrganizeConditions): OrganizeStopReason? = when {
    conditions.stopRequested -> OrganizeStopReason.REQUESTED
    conditions.isTooHot -> OrganizeStopReason.TOO_HOT
    this == OrganizeTrigger.SCHEDULED && !conditions.isCharging -> OrganizeStopReason.NOT_CHARGING
    else -> null
}
