package com.siullph.sunrisealarm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.siullph.sunrisealarm.ui.AlarmScreen
import com.siullph.sunrisealarm.ui.SunriseTheme
import kotlinx.coroutines.launch

/**
 * The full-screen side of a screen sunrise. Shows over the lock screen and keeps it
 * occluded, so the light overlay above it can be seen while the phone is locked. It also
 * draws the light itself (in case the overlay isn't allowed), and once the light is put
 * out it shows this app's alarm screen with the dismiss / snooze slider.
 */
class ScreenSunriseActivity : ComponentActivity() {
    private lateinit var light: LightView
    private lateinit var driver: LightDriver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val s = ScreenSunrise.current.value
                when {
                    s == null -> finish()
                    // Back puts the light out, like picking the phone up.
                    s.lightOn -> SunriseService.lightOff(this@ScreenSunriseActivity)
                    // The alarm screen waits for the slider, like a clock app's does.
                    s.alarmScreen -> Unit
                    else -> SunriseService.stop(this@ScreenSunriseActivity)
                }
            }
        })

        val alarmUi = ComposeView(this).apply {
            setContent {
                val sunrise by ScreenSunrise.current.collectAsState()
                SunriseTheme(darkTheme = true) {
                    AlarmScreen(
                        sunrise = sunrise,
                        onDismiss = { SunriseService.stop(this@ScreenSunriseActivity) },
                        onSnooze = { SunriseService.snooze(this@ScreenSunriseActivity) },
                    )
                }
            }
        }
        light = LightView(this).apply {
            isClickable = true
            setOnClickListener { SunriseService.lightOff(this@ScreenSunriseActivity) }
            visibility = View.GONE
        }
        driver = LightDriver(light) { brightness, headroom ->
            val attrs = window.attributes
            LightDriver.applyTo(attrs, brightness, headroom)
            window.attributes = attrs
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            val match = ViewGroup.LayoutParams.MATCH_PARENT
            addView(alarmUi, FrameLayout.LayoutParams(match, match))
            addView(light, FrameLayout.LayoutParams(match, match))
        }
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScreenSunrise.current.collect { s -> onSunrise(s) }
            }
        }
    }

    private fun onSunrise(s: ScreenSunrise?) {
        when {
            s == null -> {
                driver.stop()
                finish()
            }
            s.lightOn -> {
                val attrs = window.attributes
                LightDriver.hdrColorMode(attrs, s.boost)
                window.attributes = attrs
                light.visibility = View.VISIBLE
                driver.start(s)
            }
            else -> driver.fadeOut { light.visibility = View.GONE }
        }
    }

    override fun onStop() {
        super.onStop()
        // Hand brightness back if we are covered or closed; onSunrise restarts the light.
        if (driver.running) driver.stop()
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, ScreenSunriseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
