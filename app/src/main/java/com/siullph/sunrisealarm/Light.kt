package com.siullph.sunrisealarm

import kotlinx.coroutines.flow.MutableStateFlow

/** Where a sunrise's light comes from. */
enum class LightSource {
    FLASH,
    SCREEN;

    companion object {
        fun parse(name: String?): LightSource = entries.firstOrNull { it.name == name } ?: FLASH
    }
}

/** Screen colors offered as one-tap presets (ARGB). */
object ScreenColors {
    const val SUNRISE = 0xFFFF8A2B.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLUE = 0xFF0000FF.toInt()

    /** Screen color used until the user picks one. */
    const val DEFAULT = BLUE
}

/** A screen sunrise that is running now, shared by [SunriseService] and [ScreenSunriseActivity]. */
data class ScreenSunrise(
    val color: Int,
    val startAt: Long,
    val wakeAt: Long,
    val holdUntil: Long,
) {
    /** 0 at the start of the fade, 1 at the wake-up time and after. */
    fun progress(now: Long): Float {
        val span = (wakeAt - startAt).coerceAtLeast(1L)
        return ((now - startAt).toFloat() / span).coerceIn(0f, 1f)
    }

    companion object {
        /** Null when no screen sunrise is running. */
        val current = MutableStateFlow<ScreenSunrise?>(null)
    }
}
