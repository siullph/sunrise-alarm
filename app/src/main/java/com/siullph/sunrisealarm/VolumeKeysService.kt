package com.siullph.sunrisealarm

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Switches the flashlight on or off when volume up and volume down are pressed together
 * ([FlashlightShortcut]).
 *
 * The buttons reach the app two ways, depending on the screen:
 * - Screen on, or the always-on display showing: Android hands the buttons to accessibility
 *   services first ([onKeyEvent]). The first button goes through as usual; the second one,
 *   pressed while the first is held, is kept from the system, and the volume the first one
 *   changed is put back.
 * - Screen fully off: Android gives the buttons to no app, only to the media session that
 *   is playing. So while the screen is off (and nothing else is playing) the app holds a
 *   silent media session whose volume is "remote", and reads the presses from it.
 */
class VolumeKeysService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var torch: Torch
    private lateinit var audio: AudioManager
    private var torchOn = false
    private var session: MediaSession? = null
    private var screenOff = false
    private var lastToggle = 0L

    // Screen-on state: which buttons are held, and whether this press became the shortcut.
    private val held = mutableSetOf<Int>()
    private val passedThrough = mutableSetOf<Int>()
    private var chord = false
    private var volumesBefore: Map<Int, Int> = emptyMap()
    private var ringerBefore = AudioManager.RINGER_MODE_NORMAL

    // Screen-off state: when each button last reached the media session.
    private var lastRaise = 0L
    private var lastLower = 0L

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torch.cameraId) torchOn = enabled
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            updateSession()
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            updateSession()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        torch = Torch(this)
        audio = getSystemService(AudioManager::class.java)
        try {
            getSystemService(CameraManager::class.java).registerTorchCallback(torchCallback, handler)
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Can't follow the flashlight's state", e)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!FlashlightShortcut.enabled(this) || !torch.available) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> onButtonDown(code, event.repeatCount)
            KeyEvent.ACTION_UP -> onButtonUp(code)
            else -> false
        }
    }

    /** Returns true to keep the press from the system. */
    private fun onButtonDown(code: Int, repeat: Int): Boolean {
        if (repeat > 0) return chord
        if (chord) {
            held += code
            return true
        }
        if (held.isEmpty() || code in held) {
            // The first button: let it change the volume as normal, but note the volume so it
            // can be put back if this turns out to be the shortcut.
            volumesBefore = STREAMS.associateWith { audio.getStreamVolume(it) }
            ringerBefore = audio.ringerMode
            held += code
            passedThrough += code
            return false
        }
        // The other button while the first is held: the shortcut.
        held += code
        chord = true
        toggle()
        // The first button's volume step lands a moment after its press; undo it after that.
        handler.postDelayed({ undoVolumeChange() }, UNDO_DELAY_MS)
        return true
    }

    private fun onButtonUp(code: Int): Boolean {
        held -= code
        // A button whose press went through must have its release go through too.
        val consumed = !passedThrough.remove(code) && chord
        if (held.isEmpty()) chord = false
        return consumed
    }

    private fun undoVolumeChange() {
        volumesBefore.forEach { (stream, volume) ->
            try {
                if (audio.getStreamVolume(stream) != volume) audio.setStreamVolume(stream, volume, 0)
            } catch (e: Exception) {
                // Do Not Disturb can block changing the ringer.
            }
        }
        try {
            if (audio.ringerMode != ringerBefore) audio.ringerMode = ringerBefore
        } catch (e: Exception) {
        }
    }

    /**
     * Holds the silent media session only while the screen is off, the shortcut is on and
     * nothing else is playing, so the volume buttons keep controlling music and calls.
     */
    private fun updateSession() {
        val want = screenOff && FlashlightShortcut.enabled(this) && torch.available &&
            !audio.isMusicActive && audio.mode == AudioManager.MODE_NORMAL
        if (want && session == null) {
            session = MediaSession(this, "SunriseAlarmFlashlight").apply {
                setPlaybackToRemote(object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                    override fun onAdjustVolume(direction: Int) {
                        if (direction != 0) onScreenOffPress(raise = direction > 0)
                    }
                })
                setPlaybackState(
                    PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 0f).build(),
                )
                isActive = true
            }
        } else if (!want && session != null) {
            session?.release()
            session = null
        }
    }

    private fun onScreenOffPress(raise: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (raise) lastRaise = now else lastLower = now
        // Holding a button repeats it, so "together" is both arriving within a short gap.
        if (abs(lastRaise - lastLower) <= SCREEN_OFF_WINDOW_MS) {
            lastRaise = 0L
            lastLower = 0L
            toggle()
        }
    }

    private fun toggle() {
        val now = SystemClock.uptimeMillis()
        if (now - lastToggle < COOLDOWN_MS) return
        lastToggle = now
        if (torchOn) {
            torch.setLevel(0)
        } else {
            torch.setLevel(FlashlightShortcut.level(this, torch))
        }
        buzz()
    }

    /** A short tick, so the shortcut can be felt with the screen off. */
    private fun buzz() {
        try {
            val vibrator = getSystemService(Vibrator::class.java) ?: return
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            } else {
                VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build(),
                )
            }
        } catch (e: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        // Each may never have been registered if the service didn't finish connecting.
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { audio.unregisterAudioPlaybackCallback(playbackCallback) }
        runCatching { getSystemService(CameraManager::class.java).unregisterTorchCallback(torchCallback) }
        super.onDestroy()
    }

    companion object {
        private val STREAMS = listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_SYSTEM,
        )
        private const val UNDO_DELAY_MS = 150L
        private const val SCREEN_OFF_WINDOW_MS = 400L
        private const val COOLDOWN_MS = 700L
    }
}
