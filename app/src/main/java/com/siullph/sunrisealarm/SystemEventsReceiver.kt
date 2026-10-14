package com.siullph.sunrisealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-plans sunrises after a reboot, an app update, a clock or time zone change,
 * and whenever the phone's next alarm changes (so a linked sunrise follows it).
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.rescheduleAll(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) DisplayFilters.forgetSaturation(context)
        // After a reboot, put back any Night Light or grayscale a sunrise switched off.
        DisplayFilters.restoreIfIdle(context)
    }
}
