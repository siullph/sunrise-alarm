package com.siullph.sunrisealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Fires at the start of a fade and hands the ramp to [SunriseService]. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Scheduler.EXTRA_ALARM_ID, -1)
        val wakeAt = intent.getLongExtra(Scheduler.EXTRA_WAKE_AT, 0L)
        if (id < 0 || wakeAt <= 0L) return
        val store = AlarmStore(context)

        if (id == Scheduler.LINKED_ID) {
            if (!store.linkedEnabled) return
            store.linkedLastFiredWake = wakeAt
        } else {
            val alarm = store.alarm(id) ?: return
            if (!alarm.enabled) return
            // One-time alarms switch themselves off once they have run.
            store.save(alarm.copy(lastFiredWake = wakeAt, enabled = alarm.days.isNotEmpty()))
        }

        try {
            SunriseService.start(context, wakeAt)
        } catch (e: Exception) {
            // Can happen if Android refuses a background start (e.g. exact alarms denied).
            Log.e("SunriseAlarm", "Could not start sunrise", e)
        }
        Scheduler.rescheduleAll(context)
    }
}
