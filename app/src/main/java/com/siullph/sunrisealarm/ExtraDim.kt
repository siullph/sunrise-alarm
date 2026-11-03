package com.siullph.sunrisealarm

import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import kotlin.math.roundToInt

/**
 * Extra dim: makes the screen darker than its lowest brightness, switched from a Quick
 * Settings tile ([ExtraDimTileService]) or from the app.
 *
 * Two ways to do it:
 * - [Method.SYSTEM]: Android's own Extra dim (Android 12+, "reduce bright colors"), the one
 *   Pixel used to have a tile for. It dims everything, the status bar and the shade too, but
 *   switching it needs WRITE_SECURE_SETTINGS (the same adb or Shizuku grant as Night Light).
 * - [Method.OVERLAY]: a see-through black window over other apps ([ExtraDimService]). Works
 *   on any phone with "display over other apps", but leaves the status bar, navigation bar
 *   and the notification shade at full brightness, and Android lets touches through only up
 *   to 80% darkness.
 *
 * A screen sunrise switches either one off while it's lit ([pauseForSunrise]) and puts it
 * back afterwards ([resumeAfterSunrise]), the same way it treats Night Light.
 */
object ExtraDim {
    enum class Method { AUTO, SYSTEM, OVERLAY }

    // Settings.Secure, Android 12+.
    private const val RBC_ACTIVATED = "reduce_bright_colors_activated"
    private const val RBC_LEVEL = "reduce_bright_colors_level"

    private const val PREFS = "extra_dim"
    private const val KEY_METHOD = "method"
    private const val KEY_DIMNESS = "dimness"
    private const val KEY_OVERLAY_ON = "overlay_on"
    private const val KEY_OVERLAY_BOOT = "overlay_boot"
    private const val KEY_PAUSED = "paused"

    /** How dark the overlay gets at the ends of the slider. Android caps it at 0.8. */
    private const val OVERLAY_MIN_ALPHA = 0.25f
    private const val OVERLAY_MAX_ALPHA = 0.8f

    /** Android's own Extra dim exists on this Android version. */
    fun systemSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            systemBool("config_reduceBrightColorsAvailable") != false

    /** The app may switch Android's own Extra dim. */
    fun canUseSystem(context: Context): Boolean =
        systemSupported() && DisplayFilters.canOverride(context)

    fun method(context: Context): Method =
        runCatching { Method.valueOf(prefs(context).getString(KEY_METHOD, null) ?: "") }
            .getOrDefault(Method.AUTO)

    /** The way Extra dim is actually switched right now, after resolving [Method.AUTO]. */
    fun activeMethod(context: Context): Method = when (method(context)) {
        Method.OVERLAY -> Method.OVERLAY
        Method.SYSTEM, Method.AUTO -> if (canUseSystem(context)) Method.SYSTEM else Method.OVERLAY
    }

    fun setMethod(context: Context, value: Method) {
        val wasOn = isOn(context)
        val before = activeMethod(context)
        prefs(context).edit().putString(KEY_METHOD, value.name).apply()
        // Carry an Extra dim that's on over to the new way, so only one of them is ever on.
        if (wasOn && activeMethod(context) != before) {
            switchOff(context, before)
            setOn(context, true)
        }
        refreshTile(context)
    }

    /** How dark, 0 (a little) to 1 (as dark as it goes). Matches Pixel's default at first. */
    fun dimness(context: Context): Float {
        val saved = prefs(context).getFloat(KEY_DIMNESS, -1f)
        if (saved >= 0f) return saved.coerceIn(0f, 1f)
        val (min, max) = strengthRange()
        val default = systemInt("config_reduceBrightColorsStrengthDefault") ?: 50
        return ((default - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }

    /** Sets how dark Extra dim is, and applies it at once if it's on. */
    fun setDimness(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_DIMNESS, value.coerceIn(0f, 1f)).apply()
        if (canUseSystem(context)) writeSystemLevel(context)
        ExtraDimService.applyDimness()
    }

    /** Whether Extra dim is on (either way, or paused for a sunrise that's lit right now). */
    fun isOn(context: Context): Boolean =
        systemOn(context) || overlayOn(context) || prefs(context).getString(KEY_PAUSED, null) != null

    /**
     * Whether the overlay should be up ([ExtraDimService] reads it after a restart). It never
     * outlives a reboot, like Android's own Extra dim.
     */
    fun overlayWanted(context: Context): Boolean {
        val p = prefs(context)
        return p.getBoolean(KEY_OVERLAY_ON, false) && p.getInt(KEY_OVERLAY_BOOT, -1) == bootCount(context)
    }

    /**
     * Whether "display over other apps" is missing for the overlay way. The tile opens the app
     * to ask for it instead of switching on.
     */
    fun needsOverlayPermission(context: Context): Boolean =
        activeMethod(context) == Method.OVERLAY && !Settings.canDrawOverlays(context)

    fun toggle(context: Context): Boolean {
        val on = !isOn(context)
        setOn(context, on)
        return on
    }

    /** Switches Extra dim on or off; returns false if it couldn't be switched on. */
    fun setOn(context: Context, on: Boolean): Boolean {
        val app = context.applicationContext
        if (!on) {
            prefs(app).edit().remove(KEY_PAUSED).apply()
            switchOff(app, Method.SYSTEM)
            switchOff(app, Method.OVERLAY)
            refreshTile(app)
            return true
        }
        val ok = when (activeMethod(app)) {
            Method.SYSTEM -> writeSystem(app, true)
            else -> startOverlay(app)
        }
        refreshTile(app)
        return ok
    }

    /** Switches Extra dim off while a screen sunrise is lit. Safe to call twice. */
    fun pauseForSunrise(context: Context) {
        val app = context.applicationContext
        if (prefs(app).getString(KEY_PAUSED, null) != null) return
        val paused = when {
            systemOn(app) && DisplayFilters.canOverride(app) -> Method.SYSTEM
            overlayOn(app) -> Method.OVERLAY
            else -> return
        }
        // Written down first, so it can be put back even if the app is killed mid-sunrise.
        prefs(app).edit().putString(KEY_PAUSED, paused.name).commit()
        if (paused == Method.SYSTEM) writeSystem(app, false) else ExtraDimService.stop(app)
    }

    /** Puts back an Extra dim [pauseForSunrise] switched off. Does nothing otherwise. */
    fun resumeAfterSunrise(context: Context) {
        val app = context.applicationContext
        val paused = prefs(app).getString(KEY_PAUSED, null) ?: return
        prefs(app).edit().remove(KEY_PAUSED).commit()
        if (paused == Method.SYSTEM.name) writeSystem(app, true) else startOverlay(app)
        refreshTile(app)
    }

    fun refreshTile(context: Context) {
        try {
            TileService.requestListeningState(
                context, ComponentName(context, ExtraDimTileService::class.java),
            )
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not refresh the Extra dim tile", e)
        }
    }

    /** The overlay's window opacity for the current [dimness]. */
    fun overlayAlpha(context: Context): Float {
        val cap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                context.getSystemService(android.hardware.input.InputManager::class.java)
                    .maximumObscuringOpacityForTouch
            }.getOrDefault(OVERLAY_MAX_ALPHA)
        } else {
            OVERLAY_MAX_ALPHA
        }
        val top = minOf(OVERLAY_MAX_ALPHA, cap)
        return OVERLAY_MIN_ALPHA + (top - OVERLAY_MIN_ALPHA) * dimness(context)
    }

    fun systemOn(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        try {
            Settings.Secure.getInt(context.contentResolver, RBC_ACTIVATED, 0) == 1
        } catch (e: Exception) {
            false
        }

    private fun overlayOn(context: Context): Boolean = overlayWanted(context)

    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private fun switchOff(context: Context, method: Method) {
        when (method) {
            Method.SYSTEM -> if (systemOn(context)) writeSystem(context, false)
            else -> {
                prefs(context).edit().putBoolean(KEY_OVERLAY_ON, false).commit()
                ExtraDimService.stop(context)
            }
        }
    }

    private fun startOverlay(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        prefs(context).edit()
            .putBoolean(KEY_OVERLAY_ON, true)
            .putInt(KEY_OVERLAY_BOOT, bootCount(context))
            .commit()
        return ExtraDimService.start(context)
    }

    private fun writeSystem(context: Context, on: Boolean): Boolean = try {
        if (on) writeSystemLevel(context)
        Settings.Secure.putInt(context.contentResolver, RBC_ACTIVATED, if (on) 1 else 0)
    } catch (e: Exception) {
        Log.w("SunriseAlarm", "Could not switch Extra dim", e)
        false
    }

    private fun writeSystemLevel(context: Context) {
        val (min, max) = strengthRange()
        val strength = (min + (max - min) * dimness(context)).roundToInt()
        try {
            Settings.Secure.putInt(context.contentResolver, RBC_LEVEL, strength)
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not set the Extra dim level", e)
        }
    }

    /** The strengths Android's Extra dim accepts on this phone (Pixel: 25 to 90). */
    private fun strengthRange(): Pair<Int, Int> {
        val min = systemInt("config_reduceBrightColorsStrengthMin") ?: 25
        val max = systemInt("config_reduceBrightColorsStrengthMax") ?: 90
        return if (max > min) min to max else 25 to 90
    }

    private fun systemBool(name: String): Boolean? = try {
        val res = Resources.getSystem()
        val id = res.getIdentifier(name, "bool", "android")
        if (id == 0) null else res.getBoolean(id)
    } catch (e: Exception) {
        null
    }

    private fun systemInt(name: String): Int? = try {
        val res = Resources.getSystem()
        val id = res.getIdentifier(name, "integer", "android")
        if (id == 0) null else res.getInteger(id)
    } catch (e: Exception) {
        null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
