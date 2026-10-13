package com.siullph.sunrisealarm

import android.content.Context
import org.json.JSONArray

/** Everything the app persists, kept in SharedPreferences. */
class AlarmStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("sunrise_alarm", Context.MODE_PRIVATE)

    fun alarms(): List<SunriseAlarm> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length())
            .map { SunriseAlarm.fromJson(array.getJSONObject(it)) }
            .sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun alarm(id: Int): SunriseAlarm? = alarms().firstOrNull { it.id == id }

    fun save(alarm: SunriseAlarm) {
        val others = alarms().filterNot { it.id == alarm.id }
        writeAlarms(others + alarm)
    }

    fun delete(id: Int) = writeAlarms(alarms().filterNot { it.id == id })

    fun newId(): Int {
        val id = prefs.getInt(KEY_NEXT_ID, 1)
        prefs.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        return id
    }

    private fun writeAlarms(list: List<SunriseAlarm>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_ALARMS, array.toString()).apply()
    }

    /** Whether a sunrise runs before the phone's next alarm (set in the Clock app). */
    var linkedEnabled: Boolean
        get() = prefs.getBoolean(KEY_LINKED_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LINKED_ENABLED, value).apply()

    var linkedFadeMinutes: Int
        get() = prefs.getInt(KEY_LINKED_FADE, 30)
        set(value) = prefs.edit().putInt(KEY_LINKED_FADE, value).apply()

    var linkedSource: LightSource
        get() = LightSource.parse(prefs.getString(KEY_LINKED_SOURCE, null))
        set(value) = prefs.edit().putString(KEY_LINKED_SOURCE, value.name).apply()

    var linkedColor: Int
        get() = prefs.getInt(KEY_LINKED_COLOR, ScreenColors.DEFAULT)
        set(value) = prefs.edit().putInt(KEY_LINKED_COLOR, value).apply()

    /** Wake-up time of the phone alarm the last linked sunrise ran for. */
    var linkedLastFiredWake: Long
        get() = prefs.getLong(KEY_LINKED_LAST_FIRED, 0L)
        set(value) = prefs.edit().putLong(KEY_LINKED_LAST_FIRED, value).apply()

    private companion object {
        const val KEY_ALARMS = "alarms"
        const val KEY_NEXT_ID = "next_id"
        const val KEY_LINKED_ENABLED = "linked_enabled"
        const val KEY_LINKED_FADE = "linked_fade_minutes"
        const val KEY_LINKED_SOURCE = "linked_source"
        const val KEY_LINKED_COLOR = "linked_color"
        const val KEY_LINKED_LAST_FIRED = "linked_last_fired_wake"
    }
}
