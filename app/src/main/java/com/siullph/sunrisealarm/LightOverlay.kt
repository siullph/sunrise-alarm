package com.siullph.sunrisealarm

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager

/**
 * The sunrise light as a window drawn over other apps, so it stays on top when the Clock
 * app's alarm screen opens at the wake-up time. Fading it away reveals whatever is beneath:
 * the Clock app's slider for a linked alarm, or this app's own alarm screen. Needs the
 * "display over other apps" permission; without it the light lives only in
 * [ScreenSunriseActivity].
 */
class LightOverlay(private val context: Context, private val onTapped: () -> Unit) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: LightView? = null
    private var params: WindowManager.LayoutParams? = null
    private var driver: LightDriver? = null

    val showing: Boolean get() = view != null

    /** Returns false when the overlay can't be shown (permission missing or refused). */
    fun show(sunrise: ScreenSunrise): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        driver?.let {
            it.start(sunrise)
            return true
        }
        val lightView = LightView(context).apply {
            isClickable = true
            setOnClickListener { onTapped() }
        }
        @Suppress("DEPRECATION")
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.OPAQUE,
        ).apply {
            title = "Sunrise light"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            screenBrightness = LightRamp.MIN_BACKLIGHT
            LightDriver.hdrColorMode(this, sunrise.boost)
        }
        return try {
            windowManager.addView(lightView, lp)
            view = lightView
            params = lp
            driver = LightDriver(lightView) applyWindow@{ brightness, headroom ->
                val v = view ?: return@applyWindow
                val p = params ?: return@applyWindow
                LightDriver.applyTo(p, brightness, headroom)
                try {
                    windowManager.updateViewLayout(v, p)
                } catch (e: Exception) {
                    Log.w("SunriseAlarm", "Overlay update failed", e)
                }
            }.also { it.start(sunrise) }
            true
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not show the light overlay", e)
            false
        }
    }

    /** Fades the light out quickly, then removes the window. */
    fun fadeOut() {
        val d = driver ?: return
        d.fadeOut { remove() }
    }

    fun remove() {
        val v = view
        view = null
        params = null
        driver?.let {
            driver = null
            // stop() would update the window we are about to remove; just end the loop.
            it.detach()
        }
        if (v != null) {
            try {
                windowManager.removeView(v)
            } catch (e: Exception) {
                Log.w("SunriseAlarm", "Could not remove the light overlay", e)
            }
        }
    }
}
