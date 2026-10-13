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

        if (intent.getBooleanExtra(Scheduler.EXTRA_SNOOZE, false)) {
            // A snoozed alarm comes back as its alarm screen only, never as a sunrise.
            val alarm = store.alarm(id) ?: return
            try {
                SunriseService.startSnoozed(context, alarm.id, alarm.color)
            } catch (e: Exception) {
                Log.e("SunriseAlarm", "Could not show the snoozed alarm", e)
            }
            return
        }

        val source: LightSource
        val color: Int
        val boost: Boolean

        if (id == Scheduler.LINKED_ID) {
            if (!store.linkedEnabled) return
            store.linkedLastFiredWake = wakeAt
            source = store.linkedSource
            color = store.linkedColor
            boost = store.linkedBoost
        } else {
            val alarm = store.alarm(id) ?: return
            if (!alarm.enabled) return
            // One-time alarms switch themselves off once they have run.
            store.save(alarm.copy(lastFiredWake = wakeAt, enabled = alarm.days.isNotEmpty()))
            source = alarm.source
            color = alarm.color
            boost = alarm.boost
        }

        try {
            SunriseService.start(
                context,
                wakeAt,
                source = source,
                color = color,
                boost = boost,
                // Only this app's own alarms have a slider of their own; a linked sunrise
                // leaves the Clock app's alarm screen beneath it.
                alarmId = if (id == Scheduler.LINKED_ID) ScreenSunrise.NO_ALARM else id,
            )
        } catch (e: Exception) {
            // Can happen if Android refuses a background start (e.g. exact alarms denied).
            Log.e("SunriseAlarm", "Could not start sunrise", e)
        }
        Scheduler.rescheduleAll(context)
    }
}
