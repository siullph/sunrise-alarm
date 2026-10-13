package com.siullph.sunrisealarm

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.pow

/**
 * A full-screen fill in the sunrise color. Draws with float colors, so on a window in HDR
 * mode (16-bit buffer) the low end of the fade has far finer steps than 8-bit color, and
 * values past white light the display beyond its normal maximum.
 */
class LightView(context: Context) : View(context) {
    var color: Int = ScreenColors.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private var intensity = 0f
    private var boost = 1f

    fun show(intensity: Float, boost: Float) {
        if (intensity == this.intensity && boost == this.boost) return
        this.intensity = intensity
        this.boost = boost
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = Color.red(color) / 255f * intensity
        val g = Color.green(color) / 255f * intensity
        val b = Color.blue(color) / 255f * intensity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            canvas.drawColor(
                Color.pack(
                    linear(r) * boost, linear(g) * boost, linear(b) * boost, 1f, LINEAR_EXTENDED,
                ),
            )
        } else {
            canvas.drawColor(Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt()))
        }
    }

    private fun linear(encoded: Float): Float {
        val v = if (encoded <= 0.04045f) encoded / 12.92f
        else ((encoded + 0.055f) / 1.055f).pow(2.4f)
        return v.coerceIn(0f, MAX_LINEAR)
    }

    private companion object {
        val LINEAR_EXTENDED: ColorSpace = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB)

        /** Top of LINEAR_EXTENDED_SRGB's range. */
        const val MAX_LINEAR = 7.49f
    }
}

/**
 * Plays a [ScreenSunrise] on a [LightView] every display frame, and keeps the window's
 * brightness in step. [applyWindow] receives the backlight (or
 * [WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE] to hand brightness back to the
 * phone) and the HDR headroom wanted (0 for none).
 */
class LightDriver(
    private val view: LightView,
    private val applyWindow: (brightness: Float, headroom: Float) -> Unit,
) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private var sunrise: ScreenSunrise? = null
    private var fadeStart = -1L
    private var onFaded: (() -> Unit)? = null
    private var lastBrightness = -2f
    private var lastHeadroom = -2f

    val running: Boolean get() = sunrise != null

    fun start(s: ScreenSunrise) {
        val wasRunning = sunrise != null
        sunrise = s
        view.color = s.color
        fadeStart = -1L
        onFaded = null
        if (!wasRunning) choreographer.postFrameCallback(this)
    }

    /** Quickly fades the light and the backlight out, then calls [onDone]. */
    fun fadeOut(onDone: () -> Unit = {}) {
        if (sunrise == null) {
            onDone()
            return
        }
        if (fadeStart < 0) fadeStart = SystemClock.uptimeMillis()
        onFaded = onDone
    }

    fun stop() {
        choreographer.removeFrameCallback(this)
        sunrise = null
        fadeStart = -1L
        view.show(0f, 1f)
        applyWindow(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE, 0f)
        lastBrightness = -2f
        lastHeadroom = -2f
    }

    /** Ends the frame loop without touching the window (for a window being removed). */
    fun detach() {
        choreographer.removeFrameCallback(this)
        sunrise = null
        fadeStart = -1L
        onFaded = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        val s = sunrise ?: return
        val frame = LightRamp.frame(s.progress(System.currentTimeMillis()), s.boost)
        var fade = 1f
        if (fadeStart >= 0) {
            val t = (SystemClock.uptimeMillis() - fadeStart) / FADE_OUT_MS.toFloat()
            if (t >= 1f) {
                val done = onFaded
                onFaded = null
                stop()
                done?.invoke()
                return
            }
            // Ease out: drops fast at first, then settles into black.
            fade = (1f - t) * (1f - t)
        }
        val boost = 1f + (frame.boost - 1f) * fade
        view.show(frame.intensity * fade, boost)

        val brightness = (frame.backlight * fade).coerceAtLeast(LightRamp.MIN_BACKLIGHT)
        val headroom = if (s.boost) boost else 0f
        // Only touch the window when the change is big enough to matter (0.3 %), since each
        // update is a round trip to the window manager.
        if (abs(brightness - lastBrightness) > brightness * 0.003f ||
            abs(headroom - lastHeadroom) > 0.01f
        ) {
            applyWindow(brightness, headroom)
            lastBrightness = brightness
            lastHeadroom = headroom
        }
        choreographer.postFrameCallback(this)
    }

    companion object {
        const val FADE_OUT_MS = 450L

        /** Puts a window into HDR mode (Android 14+) so boost and fine float color work. */
        fun hdrColorMode(params: WindowManager.LayoutParams, boost: Boolean) {
            if (boost && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                params.colorMode = ActivityInfo.COLOR_MODE_HDR
            }
        }

        /** Applies brightness and, on Android 15+, the HDR headroom asked for. */
        fun applyTo(params: WindowManager.LayoutParams, brightness: Float, headroom: Float) {
            params.screenBrightness = brightness
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                params.setDesiredHdrHeadroom(if (headroom >= 1f) headroom else 0f)
            }
        }
    }
}
