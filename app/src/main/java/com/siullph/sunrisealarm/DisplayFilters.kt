package com.siullph.sunrisealarm

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Keeps the phone's color filters from changing a screen sunrise: Night Light (and Samsung's
 * Eye comfort shield), which tint the screen yellow, and grayscale (Bedtime mode, or the
 * accessibility color correction set to grayscale), which drains its color.
 *
 * Switching those off needs WRITE_SECURE_SETTINGS, which Android only gives a sideloaded app
 * through `adb shell pm grant` ([GRANT_COMMAND]). With it, [suspend] switches off whichever
 * filters are on and [restore] puts back exactly those. What was switched off is written down
 * first, so a backup alarm ([RestoreReceiver]) and the next app start can still put it back
 * if the app is killed mid-sunrise. Without it, [suspend] can only shift the color to cancel
 * Night Light's tint; grayscale throws the color away, so nothing drawn can undo it.
 */
object DisplayFilters {
    const val GRANT_COMMAND =
        "adb shell pm grant com.siullph.sunrisealarm android.permission.WRITE_SECURE_SETTINGS"

    // AOSP / Pixel (Settings.Secure).
    private const val NIGHT_ACTIVATED = "night_display_activated"
    private const val NIGHT_AUTO_MODE = "night_display_auto_mode"
    private const val NIGHT_LAST_ACTIVATED = "night_display_last_activated_time"
    private const val NIGHT_TEMPERATURE = "night_display_color_temperature"
    private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    private const val DALTONIZER_MODE = "accessibility_display_daltonizer"
    private const val DALTONIZER_GRAYSCALE = 0

    // Samsung Eye comfort shield (Settings.System).
    private const val SAMSUNG_BLUE_FILTER = "blue_filter_enabled"

    /** AOSP's default Night Light temperature, used when the phone won't say. */
    private const val DEFAULT_NIGHT_KELVIN = 2850

    private const val PREFS = "display_filters"
    private const val KEY_SAVED = "saved"
    private const val RESTORE_REQUEST = 2_000_000

    /** What is on right now; null where the phone won't let the app read it. */
    data class Status(val nightLight: Boolean?, val grayscale: Boolean?, val eyeComfort: Boolean?)

    fun canOverride(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun status(context: Context): Status {
        val cr = context.contentResolver
        val grayscale = secureInt(context, DALTONIZER_ENABLED)?.let { enabled ->
            enabled == 1 && secureInt(context, DALTONIZER_MODE) == DALTONIZER_GRAYSCALE
        }
        val eyeComfort = try {
            Settings.System.getInt(cr, SAMSUNG_BLUE_FILTER, -1).takeIf { it >= 0 }?.let { it == 1 }
        } catch (e: Exception) {
            null
        }
        return Status(secureInt(context, NIGHT_ACTIVATED)?.let { it == 1 }, grayscale, eyeComfort)
    }

    /**
     * Gets the screen ready for a sunrise of [color] lasting until [until] and returns the
     * color to draw: [color] itself once the filters are off, or a counter-tinted color when
     * Night Light stays on.
     */
    fun suspend(context: Context, color: Int, until: Long): Int {
        val now = status(context)
        if (!canOverride(context)) {
            return if (now.nightLight == true) {
                counterTint(color, secureInt(context, NIGHT_TEMPERATURE) ?: DEFAULT_NIGHT_KELVIN)
            } else {
                color
            }
        }
        val cr = context.contentResolver
        // Add to anything still waiting to be put back, so a sunrise that replaces another
        // doesn't record the already-off filters as the user's settings.
        val saved = loadSaved(context) ?: JSONObject()
        if (now.nightLight == true && !saved.has("night")) {
            saved.put(
                "night",
                JSONObject()
                    .put("auto", secureInt(context, NIGHT_AUTO_MODE) ?: 0)
                    .put("last", secureString(context, NIGHT_LAST_ACTIVATED) ?: JSONObject.NULL),
            )
        }
        if (now.grayscale == true) saved.put("grayscale", true)
        if (now.eyeComfort == true) saved.put("eyeComfort", true)
        if (saved.length() == 0) return color

        // Written down before anything changes, so it can always be undone.
        prefs(context).edit().putString(KEY_SAVED, saved.toString()).commit()
        scheduleBackup(context, until)
        tryWrite("Night Light") {
            if (now.nightLight == true) Settings.Secure.putInt(cr, NIGHT_ACTIVATED, 0)
        }
        tryWrite("grayscale") {
            if (now.grayscale == true) Settings.Secure.putInt(cr, DALTONIZER_ENABLED, 0)
        }
        tryWrite("Eye comfort shield") {
            if (now.eyeComfort == true) Settings.System.putInt(cr, SAMSUNG_BLUE_FILTER, 0)
        }
        return color
    }

    /** Puts back every filter [suspend] switched off. Does nothing if none are waiting. */
    fun restore(context: Context) {
        val saved = loadSaved(context) ?: return
        val cr = context.contentResolver
        saved.optJSONObject("night")?.let { night ->
            tryWrite("Night Light") {
                val auto = night.optInt("auto", 0)
                if (auto == 0) {
                    Settings.Secure.putInt(cr, NIGHT_ACTIVATED, 1)
                } else {
                    // On a schedule, Night Light should be on again only if the schedule still
                    // says so; if the sunrise outlasted the schedule's end, switching it back on
                    // would keep it on all day. Putting back the time it was last switched on
                    // and restarting the schedule lets the phone decide, as if we'd never
                    // touched it.
                    val last = if (night.isNull("last")) null else night.optString("last")
                    Settings.Secure.putString(cr, NIGHT_LAST_ACTIVATED, last)
                    Settings.Secure.putInt(cr, NIGHT_AUTO_MODE, 0)
                    Settings.Secure.putInt(cr, NIGHT_AUTO_MODE, auto)
                }
            }
        }
        if (saved.optBoolean("grayscale")) {
            tryWrite("grayscale") { Settings.Secure.putInt(cr, DALTONIZER_ENABLED, 1) }
        }
        if (saved.optBoolean("eyeComfort")) {
            tryWrite("Eye comfort shield") { Settings.System.putInt(cr, SAMSUNG_BLUE_FILTER, 1) }
        }
        prefs(context).edit().remove(KEY_SAVED).commit()
        context.getSystemService(AlarmManager::class.java).cancel(backupIntent(context))
    }

    /** [restore], unless a screen sunrise is still lit in this process. */
    fun restoreIfIdle(context: Context) {
        val s = ScreenSunrise.current.value
        if (s == null || !s.lightOn) restore(context)
    }

    /**
     * Shifts [color] so it looks like itself through a Night Light at [kelvin]: channels Night
     * Light dims are raised relative to the others. Nothing can be raised past full, so the
     * result is darker, and a pure blue only gets back its hue, not its brightness.
     */
    fun counterTint(color: Int, kelvin: Int): Int {
        val tint = ScreenColors.fromKelvin(kelvin)
        val r = Color.red(color) / Color.red(tint).coerceAtLeast(1).toFloat()
        val g = Color.green(color) / Color.green(tint).coerceAtLeast(1).toFloat()
        val b = Color.blue(color) / Color.blue(tint).coerceAtLeast(1).toFloat()
        val top = maxOf(r, g, b)
        if (top <= 0f) return color
        fun c(v: Float) = (v / top * 255f).roundToInt().coerceIn(0, 255)
        return Color.rgb(c(r), c(g), c(b))
    }

    private fun scheduleBackup(context: Context, until: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, backupIntent(context))
    }

    private fun backupIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, RESTORE_REQUEST, Intent(context, RestoreReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun loadSaved(context: Context): JSONObject? = prefs(context).getString(KEY_SAVED, null)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.takeIf { it.length() > 0 }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Hidden settings: newer Android versions may refuse to let apps read some of them.
    private fun secureInt(context: Context, key: String): Int? = try {
        Settings.Secure.getInt(context.contentResolver, key, -1).takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    private fun secureString(context: Context, key: String): String? = try {
        Settings.Secure.getString(context.contentResolver, key)
    } catch (e: Exception) {
        null
    }

    private inline fun tryWrite(what: String, write: () -> Unit) {
        try {
            write()
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not change $what", e)
        }
    }

    /** Puts the filters back if the app was killed before the sunrise could. */
    class RestoreReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = restoreIfIdle(context)
    }
}
