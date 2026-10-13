package com.siullph.sunrisealarm

import android.content.Context
import org.json.JSONArray

/** Everything the app persists, kept in SharedPreferences. */
class AlarmStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("sunrise_alarm", Context.MODE_PRIVATE)

    init {
        migrateColors()
    }

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

    var linkedBoost: Boolean
        get() = prefs.getBoolean(KEY_LINKED_BOOST, true)
        set(value) = prefs.edit().putBoolean(KEY_LINKED_BOOST, value).apply()

    /** Wake-up time of the phone alarm the last linked sunrise ran for. */
    var linkedLastFiredWake: Long
        get() = prefs.getLong(KEY_LINKED_LAST_FIRED, 0L)
        set(value) = prefs.edit().putLong(KEY_LINKED_LAST_FIRED, value).apply()

    /** The user's saved colors, in the order they were added. White is built in, not a slot. */
    fun colorSlots(): List<ColorSlot> {
        val raw = prefs.getString(KEY_COLOR_SLOTS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { ColorSlot.fromJson(array.getJSONObject(it)) }
    }

    fun saveColorSlots(slots: List<ColorSlot>) {
        val array = JSONArray()
        slots.take(ColorSlot.MAX).forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_COLOR_SLOTS, array.toString()).apply()
    }

    fun newColorSlotId(): Int {
        val id = prefs.getInt(KEY_NEXT_COLOR_ID, 1)
        prefs.edit().putInt(KEY_NEXT_COLOR_ID, id + 1).apply()
        return id
    }

    /** Points every alarm that used [old] at [new], after a color slot was changed. */
    fun replaceColor(old: Int, new: Int) {
        if (old == new) return
        val all = alarms()
        if (all.any { it.color == old }) writeAlarms(all.map { if (it.color == old) it.copy(color = new) else it })
        if (prefs.contains(KEY_LINKED_COLOR) && linkedColor == old) linkedColor = new
    }

    /**
     * v0.2 had Sunrise and Blue presets and one unnamed custom color. A fresh install now
     * starts with white only; an upgraded one keeps any color its alarms use, as named slots.
     */
    private fun migrateColors() {
        if (prefs.getBoolean(KEY_COLORS_MIGRATED, false)) return
        val upgrading = prefs.contains(KEY_ALARMS) || prefs.contains(KEY_LINKED_SOURCE)
        if (upgrading) {
            // Before v0.3 an unset linked color meant blue.
            if (!prefs.contains(KEY_LINKED_COLOR) && linkedSource == LightSource.SCREEN) {
                linkedColor = ScreenColors.OLD_BLUE
            }
            val used = buildList {
                if (prefs.contains(KEY_LINKED_COLOR)) add(linkedColor)
                alarms().filter { it.source == LightSource.SCREEN }.forEach { add(it.color) }
            }.distinct().filter { it != ScreenColors.WHITE }.take(ColorSlot.MAX)
            var custom = 0
            val slots = used.map { color ->
                val name = when (color) {
                    ScreenColors.OLD_SUNRISE -> "Sunrise"
                    ScreenColors.OLD_BLUE -> "Blue"
                    else -> "Custom ${++custom}"
                }
                ColorSlot(newColorSlotId(), name, color)
            }
            saveColorSlots(slots)
        }
        prefs.edit().putBoolean(KEY_COLORS_MIGRATED, true).apply()
    }

    private companion object {
        const val KEY_ALARMS = "alarms"
        const val KEY_NEXT_ID = "next_id"
        const val KEY_LINKED_ENABLED = "linked_enabled"
        const val KEY_LINKED_FADE = "linked_fade_minutes"
        const val KEY_LINKED_SOURCE = "linked_source"
        const val KEY_LINKED_COLOR = "linked_color"
        const val KEY_LINKED_LAST_FIRED = "linked_last_fired_wake"
        const val KEY_LINKED_BOOST = "linked_boost"
        const val KEY_COLOR_SLOTS = "color_slots"
        const val KEY_NEXT_COLOR_ID = "next_color_id"
        const val KEY_COLORS_MIGRATED = "colors_migrated_v3"
    }
}
