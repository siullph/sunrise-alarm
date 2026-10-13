package com.siullph.sunrisealarm

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow

/** Where a sunrise's light comes from. */
enum class LightSource {
    FLASH,
    SCREEN;

    companion object {
        fun parse(name: String?): LightSource = entries.firstOrNull { it.name == name } ?: FLASH
    }
}

object ScreenColors {
    /** The one built-in color; everything else is a user's color slot. */
    const val WHITE = 0xFFFFFFFF.toInt()

    /** Presets from v0.2, kept only to name them when older alarms are migrated to slots. */
    const val OLD_SUNRISE = 0xFFFF8A2B.toInt()
    const val OLD_BLUE = 0xFF0000FF.toInt()

    /** Screen color used until the user picks one. */
    const val DEFAULT = WHITE

    /** Approximate color of a black body at [kelvin] (Tanner Helland's fit), as opaque ARGB. */
    fun fromKelvin(kelvin: Int): Int {
        val t = kelvin.coerceIn(1000, 40000) / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661
        else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        fun c(v: Double) = v.toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}

/** A color the user saved and named. Up to [MAX] of them. */
data class ColorSlot(val id: Int, val name: String, val color: Int) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("color", color)

    companion object {
        const val MAX = 10

        fun fromJson(o: JSONObject) = ColorSlot(
            id = o.getInt("id"),
            name = o.optString("name", "Color"),
            color = o.getInt("color"),
        )
    }
}

/**
 * A screen sunrise that is running now, shared by [SunriseService], its light overlay and
 * [ScreenSunriseActivity].
 *
 * @param boost push past the normal maximum toward the display's peak (HDR) brightness.
 * @param alarmScreen show the dismiss / snooze slider once the light is put out. Only
 *   sunrise-only alarms have one; a linked sunrise leaves the Clock app's alarm screen.
 * @param lightOn false once the light was put out (phone picked up or the light tapped),
 *   and for an alarm that comes back from a snooze, which never lights the screen.
 */
data class ScreenSunrise(
    val color: Int,
    val startAt: Long,
    val wakeAt: Long,
    val holdUntil: Long,
    val boost: Boolean = false,
    val alarmScreen: Boolean = false,
    val lightOn: Boolean = true,
    val alarmId: Int = NO_ALARM,
) {
    /** 0 at the start of the fade, 1 at the wake-up time and after. */
    fun progress(now: Long): Float {
        val span = (wakeAt - startAt).coerceAtLeast(1L)
        return ((now - startAt).toDouble() / span).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        const val NO_ALARM = -1

        /** Null when no screen sunrise is running. */
        val current = MutableStateFlow<ScreenSunrise?>(null)
    }
}

/** What the screen shows at one moment of a sunrise. */
data class LightFrame(
    /** Color strength, 0 (black) to 1 (the full color), in gamma-encoded (perceptual) terms. */
    val intensity: Float,
    /** Window brightness override, [LightRamp.MIN_BACKLIGHT] to 1. */
    val backlight: Float,
    /** Multiplier past normal white toward the display's peak; 1 means no boost. */
    val boost: Float,
)

/**
 * The shape of a screen sunrise. Each stage changes one thing along a curve the eye sees
 * as even steps, so the light never visibly jumps:
 *  1. the color rises out of black at the lowest backlight (gamma-encoded values are
 *     already perceptually spaced);
 *  2. the backlight rises exponentially, which the eye reads as linear;
 *  3. with boost on, the last stretch pushes into HDR headroom, also exponentially.
 */
object LightRamp {
    /** Dimmest backlight we ask for; 0 can switch the backlight off on some phones. */
    const val MIN_BACKLIGHT = 0.004f

    /** Largest boost we ask for. The display clips this to whatever peak it has. */
    const val MAX_BOOST = 7f

    private const val COLOR_END = 0.35f
    private const val BACKLIGHT_END_WITH_BOOST = 0.88f

    fun frame(progress: Float, boost: Boolean): LightFrame {
        val p = progress.coerceIn(0f, 1f)
        val backlightEnd = if (boost) BACKLIGHT_END_WITH_BOOST else 1f
        val intensity = smooth(p / COLOR_END)
        val t = smooth((p - COLOR_END) / (backlightEnd - COLOR_END))
        val backlight = MIN_BACKLIGHT * (1f / MIN_BACKLIGHT).pow(t)
        val b = if (boost) smooth((p - backlightEnd) / (1f - backlightEnd)) else 0f
        return LightFrame(intensity, backlight, MAX_BOOST.pow(b))
    }

    /** Clamps to 0..1 and eases the ends of a stage so its speed never changes abruptly. */
    private fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        // Mostly linear, with a short ease at each end (blend of linear and smoothstep).
        return 0.75f * c + 0.25f * c * c * (3f - 2f * c)
    }
}
