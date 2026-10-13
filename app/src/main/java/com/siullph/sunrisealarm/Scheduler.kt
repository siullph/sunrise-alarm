package com.siullph.sunrisealarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.time.ZonedDateTime

/** The phone's next alarm clock, as reported by Android. */
data class PhoneAlarm(val wakeAt: Long, val appLabel: String?)

object Scheduler {
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_WAKE_AT = "wake_at"
    const val EXTRA_SNOOZE = "snooze"

    /** How long this app's own alarm screen stays away after Snooze. */
    const val SNOOZE_MS = 10 * 60_000L

    /** Request codes for snoozes are offset so they never replace an alarm's own sunrise. */
    private const val SNOOZE_REQUEST_BASE = 1_000_000

    /**
     * A phone alarm this soon after the one a sunrise just ran for is treated as a snooze
     * (or a backup alarm) and gets no sunrise of its own: the light is already up.
     */
    private const val SNOOZE_WINDOW_MS = 60 * 60_000L

    /** Request code / alarm id used for the sunrise tied to the phone's next alarm. */
    const val LINKED_ID = 0

    /**
     * Android exposes only the *next* alarm clock across all apps, never the full list.
     * Our own sunrises are scheduled with plain exact alarms (not setAlarmClock), so they
     * never hide the Clock app's alarm from this call.
     */
    fun nextPhoneAlarm(context: Context): PhoneAlarm? {
        val am = context.getSystemService(AlarmManager::class.java)
        val info = am.nextAlarmClock ?: return null
        val creator = info.showIntent?.creatorPackage
        if (creator == context.packageName) return null
        val label = creator?.let {
            try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
        return PhoneAlarm(info.triggerTime, label)
    }

    /** Next wake-up time for [alarm] strictly after now and after its last sunrise. */
    fun nextWake(alarm: SunriseAlarm, now: ZonedDateTime = ZonedDateTime.now()): Long? {
        if (!alarm.enabled) return null
        val nowMillis = now.toInstant().toEpochMilli()
        for (offset in 0..7) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            if (alarm.days.isNotEmpty() && date.dayOfWeek.value !in alarm.days) continue
            val candidate = date.atTime(alarm.hour, alarm.minute)
                .atZone(now.zone).toInstant().toEpochMilli()
            if (candidate > nowMillis && candidate > alarm.lastFiredWake) return candidate
        }
        return null
    }

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /** Re-plans every sunrise. Safe to call any time; it is idempotent. */
    fun rescheduleAll(context: Context) {
        val store = AlarmStore(context)
        for (alarm in store.alarms()) {
            val wake = nextWake(alarm)
            if (wake == null) cancel(context, alarm.id)
            else schedule(context, alarm.id, wake, alarm.fadeMinutes)
        }
        scheduleLinked(context, store)
    }

    private fun scheduleLinked(context: Context, store: AlarmStore) {
        val phoneAlarm = nextPhoneAlarm(context)
        if (!store.linkedEnabled || phoneAlarm == null ||
            phoneAlarm.wakeAt <= System.currentTimeMillis() ||
            phoneAlarm.wakeAt == store.linkedLastFiredWake ||
            isSnooze(phoneAlarm.wakeAt, store.linkedLastFiredWake)
        ) {
            cancel(context, LINKED_ID)
            return
        }
        schedule(context, LINKED_ID, phoneAlarm.wakeAt, store.linkedFadeMinutes)
    }

    /**
     * Android doesn't say whether the next alarm is a snooze, so this infers it. A snoozed
     * alarm comes back minutes after the alarm it snoozed, which a sunrise just ran for.
     */
    fun isSnooze(wakeAt: Long, lastSunriseWake: Long): Boolean =
        lastSunriseWake > 0L && wakeAt - lastSunriseWake in 1..SNOOZE_WINDOW_MS

    /** Brings this app's own alarm screen back (dark, no sunrise) after a snooze. */
    fun scheduleSnooze(context: Context, alarmId: Int) {
        val at = System.currentTimeMillis() + SNOOZE_MS
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(EXTRA_ALARM_ID, alarmId)
            .putExtra(EXTRA_WAKE_AT, at)
            .putExtra(EXTRA_SNOOZE, true)
        val pi = PendingIntent.getBroadcast(
            context, SNOOZE_REQUEST_BASE + alarmId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(AlarmManager::class.java)
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelSnooze(context: Context, alarmId: Int) = cancel(context, SNOOZE_REQUEST_BASE + alarmId)

    private fun schedule(context: Context, id: Int, wakeAt: Long, fadeMinutes: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        // If we are already inside the fade window, start right away with a shorter fade.
        val startAt = maxOf(wakeAt - fadeMinutes * 60_000L, System.currentTimeMillis() + 1_000L)
        val pi = pendingIntent(context, id, wakeAt)
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startAt, pi)
        }
    }

    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    private fun pendingIntent(context: Context, id: Int, wakeAt: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(EXTRA_ALARM_ID, id)
            .putExtra(EXTRA_WAKE_AT, wakeAt)
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
