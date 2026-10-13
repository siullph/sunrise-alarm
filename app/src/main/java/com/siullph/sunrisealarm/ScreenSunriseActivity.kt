package com.siullph.sunrisealarm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * Full-screen light for a screen sunrise. Shows over the lock screen, keeps the screen on,
 * and fades from black to the chosen color by raising both the color's intensity and the
 * screen's brightness. Tap anywhere to show the Stop button.
 */
class ScreenSunriseActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Leaving with Back ends the sunrise rather than leaving it running out of sight.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = stopSunrise()
        })

        setContent {
            val sunrise by ScreenSunrise.current.collectAsState()
            val running = sunrise
            if (running == null) {
                LaunchedEffect(Unit) { finish() }
                Box(Modifier.fillMaxSize().background(Color.Black))
                return@setContent
            }

            var progress by remember(running) {
                mutableFloatStateOf(running.progress(System.currentTimeMillis()))
            }
            LaunchedEffect(running) {
                while (true) {
                    progress = running.progress(System.currentTimeMillis())
                    setScreenBrightness(progress)
                    delay(TICK_MS)
                }
            }
            var controlsVisible by remember { mutableStateOf(false) }
            LaunchedEffect(controlsVisible) {
                if (controlsVisible) {
                    delay(CONTROLS_MS)
                    controlsVisible = false
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(lerp(Color.Black, Color(running.color), progress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { controlsVisible = !controlsVisible },
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (controlsVisible) {
                    Row(
                        modifier = Modifier.padding(bottom = 64.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OutlinedButton(onClick = { controlsVisible = false }) {
                            Text("Hide", color = Color.White)
                        }
                        Button(
                            onClick = { stopSunrise() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                            ),
                        ) { Text("Stop sunrise") }
                    }
                }
            }
        }
    }

    /** Window brightness overrides the phone's own setting only while this screen is shown. */
    private fun setScreenBrightness(progress: Float) {
        val attrs = window.attributes
        val brightness = progress.coerceIn(MIN_BRIGHTNESS, 1f)
        if (attrs.screenBrightness != brightness) {
            attrs.screenBrightness = brightness
            window.attributes = attrs
        }
    }

    private fun stopSunrise() {
        SunriseService.stop(this)
        finish()
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
        private const val TICK_MS = 500L
        private const val CONTROLS_MS = 6_000L

        /** Dimmest backlight we ask for; 0 can switch the backlight off on some phones. */
        private const val MIN_BRIGHTNESS = 0.01f

        fun intent(context: Context): Intent =
            Intent(context, ScreenSunriseActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
