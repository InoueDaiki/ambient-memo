package dev.ambientmemo.engine.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 再起動後に、聞き取りの再開を促す通知を出す。
 *
 * マイクの Foreground Service はここから開始できない。利用者が通知からアプリを開き、前面で開始する。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        EngineNotifications(context).showRebootReminder()
    }
}
