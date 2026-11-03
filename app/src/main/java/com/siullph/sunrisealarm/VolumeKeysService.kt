package com.siullph.sunrisealarm

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
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
 * - Screen on (locked or in use), or the always-on display showing: Android hands the buttons
 *   to accessibility services first ([onKeyEvent]). The first button goes through as usual;
 *   the second one, pressed while the first is held, is kept from the system, and the volume
 *   the first one changed is put back. Android's own volume-keys accessibility shortcut
 *   swallows the pair before any app sees it, so that shortcut has to be off
 *   ([FlashlightShortcut.systemShortcutOn]).
 * - Screen fully off: Android gives the buttons to no app. They only change the media
 *   volume, and only while something is playing. So while the screen is off the app plays
 *   silence, and reads the presses from the volume going up and down ([volumeReceiver]).
 */
class VolumeKeysService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var torch: Torch
    private lateinit var audio: AudioManager
    private var torchOn = false
    private var lastToggle = 0L

    // Screen-on state: which buttons are held, and whether this press became the shortcut.
    private val held = mutableSetOf<Int>()
    private val passedThrough = mutableSetOf<Int>()
    private var chord = false
    private var volumesBefore: Map<Int, Int> = emptyMap()
    private var ringerBefore = AudioManager.RINGER_MODE_NORMAL

    // Screen-off state.
    private var silence: AudioTrack? = null
    private var listening = false
    private var lastRaise = 0L
    private var lastLower = 0L
    /** Volume changes the app makes itself are ignored until then. */
    private var ignoreVolumeUntil = 0L
    /** The media volume before it was moved off a limit (or -1), and what it was moved to. */
    private var nudgedFrom = -1
    private var nudgedTo = -1

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torch.cameraId) torchOn = enabled
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) startScreenOff() else stopScreenOff()
        }
    }

    /** Every volume step, from any app or button; only listened to while the screen is off. */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (SystemClock.uptimeMillis() < ignoreVolumeUntil) return
            val now = intent.getIntExtra(EXTRA_VOLUME, -1)
            val before = intent.getIntExtra(EXTRA_PREV_VOLUME, -1)
            if (now < 0 || before < 0 || now == before) return
            onScreenOffPress(raise = now > before)
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
        ignoreVolumeUntil = SystemClock.uptimeMillis() + IGNORE_OWN_MS
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
     * Screen just went off: play silence so the buttons move the media volume, and listen for
     * that. A volume at its top or bottom can't move one way, so it is stepped in by one
     * until the screen comes back on.
     */
    private fun startScreenOff() {
        if (!FlashlightShortcut.enabled(this) || !torch.available || listening) return
        listening = true
        lastRaise = 0L
        lastLower = 0L
        val stream = AudioManager.STREAM_MUSIC
        val volume = audio.getStreamVolume(stream)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(stream) else 0
        val max = audio.getStreamMaxVolume(stream)
        val target = when (volume) {
            max -> max - 1
            min -> min + 1
            else -> volume
        }
        if (target != volume && min < max) {
            try {
                ignoreVolumeUntil = SystemClock.uptimeMillis() + IGNORE_OWN_MS
                audio.setStreamVolume(stream, target, 0)
                nudgedFrom = volume
                nudgedTo = target
            } catch (e: Exception) {
            }
        }
        ContextCompat.registerReceiver(
            this,
            volumeReceiver,
            IntentFilter(VOLUME_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        playSilence()
    }

    private fun stopScreenOff() {
        if (!listening) return
        listening = false
        runCatching { unregisterReceiver(volumeReceiver) }
        silence?.let { runCatching { it.stop() }; it.release() }
        silence = null
        // Put the volume back, unless it was changed on purpose in the meantime.
        if (nudgedFrom >= 0) {
            try {
                if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == nudgedTo) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, nudgedFrom, 0)
                }
            } catch (e: Exception) {
            }
        }
        nudgedFrom = -1
        nudgedTo = -1
    }

    /**
     * Loops one second of silence. It asks for no audio focus, so it never pauses or ducks
     * anything; it only makes Android count media as playing.
     */
    private fun playSilence() {
        try {
            val rate = 8_000
            val frames = rate
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                .build()
            track.write(ShortArray(frames), 0, frames)
            track.setLoopPoints(0, frames, -1)
            track.play()
            silence = track
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not play silence for the screen-off shortcut", e)
        }
    }

    private fun onScreenOffPress(raise: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (raise) lastRaise = now else lastLower = now
        // Holding a button repeats it, so "together" is both arriving within a short gap.
        if (abs(lastRaise - lastLower) <= SCREEN_OFF_WINDOW_MS) {
            lastRaise = 0L
            lastLower = 0L
            // The two steps cancel out, so the volume is already back where it was.
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
        // Each may never have been registered if the service didn't finish connecting.
        runCatching { stopScreenOff() }
        runCatching { unregisterReceiver(screenReceiver) }
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

        // AudioManager's volume broadcast; hidden from the SDK but sent on every Android version.
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

        private const val UNDO_DELAY_MS = 150L
        private const val SCREEN_OFF_WINDOW_MS = 500L
        private const val COOLDOWN_MS = 700L
        private const val IGNORE_OWN_MS = 500L
    }
}
