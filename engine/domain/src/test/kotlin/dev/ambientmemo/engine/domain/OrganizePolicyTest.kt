@file:Suppress("FunctionNaming") // テスト名は日本語の文にする

package dev.ambientmemo.engine.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OrganizePolicyTest {
    private fun conditions(stopRequested: Boolean = false, isCharging: Boolean = true, isTooHot: Boolean = false) =
        OrganizeConditions(stopRequested, isCharging, isTooHot)

    @Test
    fun `定期実行は充電が切れると止める`() {
        assertEquals(
            OrganizeStopReason.NOT_CHARGING,
            OrganizeTrigger.SCHEDULED.stopReason(conditions(isCharging = false)),
        )
    }

    @Test
    fun `定期実行は充電中で熱くなければ続ける`() {
        assertNull(OrganizeTrigger.SCHEDULED.stopReason(conditions()))
    }

    @Test
    fun `手動の実行は充電が切れても続ける`() {
        assertNull(OrganizeTrigger.MANUAL.stopReason(conditions(isCharging = false)))
    }

    @Test
    fun `手動の実行でも熱いと止める`() {
        assertEquals(
            OrganizeStopReason.TOO_HOT,
            OrganizeTrigger.MANUAL.stopReason(conditions(isCharging = false, isTooHot = true)),
        )
    }

    @Test
    fun `定期実行も熱いと止める`() {
        assertEquals(OrganizeStopReason.TOO_HOT, OrganizeTrigger.SCHEDULED.stopReason(conditions(isTooHot = true)))
    }

    @Test
    fun `止める依頼はどちらの実行でも止め、理由は依頼になる`() {
        val requested = conditions(stopRequested = true, isCharging = false, isTooHot = true)

        assertEquals(OrganizeStopReason.REQUESTED, OrganizeTrigger.MANUAL.stopReason(requested))
        assertEquals(OrganizeStopReason.REQUESTED, OrganizeTrigger.SCHEDULED.stopReason(requested))
    }
}
