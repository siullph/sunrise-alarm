package com.siullph.sunrisealarm

import org.json.JSONArray
import org.json.JSONObject

/**
 * A sunrise-only alarm created in this app.
 *
 * @param days repeat days as java.time.DayOfWeek values (1 = Monday .. 7 = Sunday).
 *   Empty means the alarm rings once and then switches itself off.
 * @param lastFiredWake wake-up time (epoch millis) of the most recent sunrise this
 *   alarm started, so rescheduling never picks the same morning twice.
 * @param color ARGB color the screen fades up to when [source] is the screen.
 * @param boost let the screen go past its normal maximum toward the display's peak.
 */
data class SunriseAlarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val fadeMinutes: Int,
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val lastFiredWake: Long = 0L,
    val source: LightSource = LightSource.FLASH,
    val color: Int = ScreenColors.DEFAULT,
    val boost: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hour", hour)
        .put("minute", minute)
        .put("fadeMinutes", fadeMinutes)
        .put("days", JSONArray(days.sorted()))
        .put("enabled", enabled)
        .put("lastFiredWake", lastFiredWake)
        .put("source", source.name)
        .put("color", color)
        .put("boost", boost)

    companion object {
        fun fromJson(o: JSONObject): SunriseAlarm {
            val daysJson = o.optJSONArray("days") ?: JSONArray()
            return SunriseAlarm(
                id = o.getInt("id"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                fadeMinutes = o.getInt("fadeMinutes"),
                days = (0 until daysJson.length()).map { daysJson.getInt(it) }.toSet(),
                enabled = o.optBoolean("enabled", true),
                lastFiredWake = o.optLong("lastFiredWake", 0L),
                source = LightSource.parse(o.optString("source")),
                color = o.optInt("color", ScreenColors.DEFAULT),
                boost = o.optBoolean("boost", true),
            )
        }
    }
}
