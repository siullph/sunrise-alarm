package com.siullph.sunrisealarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Runs one sunrise: the light starts at its dimmest level and brightens until the
 * wake-up time, then stays at full brightness for a while before switching off.
 *
 * The flashlight is driven from here. A screen sunrise is drawn by a [LightOverlay] over
 * every app (when allowed) and by [ScreenSunriseActivity] beneath it, both kept in step
 * through [ScreenSunrise.current]. Picking the phone up ([PickupDetector]) or tapping the
 * light fades it out quickly, uncovering the alarm's slider. Light only: the notification
 * channels are silent.
 */
class SunriseService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var torch: Torch
    private lateinit var overlay: LightOverlay
    private lateinit var pickup: PickupDetector
    private var wakeLock: PowerManager.WakeLock? = null

    private var startAt = 0L
    private var wakeAt = 0L
    private var holdUntil = 0L
    private var currentLevel = -1
    private var source = LightSource.FLASH
    private var alarmId = ScreenSunrise.NO_ALARM

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now >= holdUntil) {
                stopSunrise()
                return
            }
            if (source == LightSource.FLASH && currentLevel != FADING) {
                val span = (wakeAt - startAt).coerceAtLeast(1L)
                val progress = ((now - startAt).toDouble() / span).coerceIn(0.0, 1.0)
                // Exponential: each step looks as big as the last to the eye.
                val level = torch.maxLevel.toDouble().pow(progress).roundToInt().coerceIn(1, torch.maxLevel)
                if (level != currentLevel) {
                    torch.setLevel(level)
                    currentLevel = level
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val stopRunnable = Runnable { stopSunrise() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        torch = Torch(this)
        overlay = LightOverlay(this) { lightOff() }
        pickup = PickupDetector(this) { lightOff() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (alarmId != ScreenSunrise.NO_ALARM) Scheduler.cancelSnooze(this, alarmId)
                stopSunrise()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                if (alarmId != ScreenSunrise.NO_ALARM) Scheduler.scheduleSnooze(this, alarmId)
                stopSunrise()
                return START_NOT_STICKY
            }
            ACTION_LIGHT_OFF -> {
                if (ScreenSunrise.current.value == null && currentLevel <= 0) stopSunrise() else lightOff()
                return START_NOT_STICKY
            }
            ACTION_SNOOZED -> {
                if (intent != null) showSnoozed(intent)
                return START_NOT_STICKY
            }
        }
        val now = System.currentTimeMillis()
        val requestedWake = intent?.getLongExtra(Scheduler.EXTRA_WAKE_AT, 0L) ?: 0L
        val holdMs = intent?.getLongExtra(EXTRA_HOLD_MS, HOLD_MS) ?: HOLD_MS
        val newSource = LightSource.parse(intent?.getStringExtra(EXTRA_SOURCE))
        val color = intent?.getIntExtra(EXTRA_COLOR, ScreenColors.DEFAULT) ?: ScreenColors.DEFAULT
        val boost = intent?.getBooleanExtra(EXTRA_BOOST, false) ?: false
        val newAlarmId = intent?.getIntExtra(EXTRA_ALARM, ScreenSunrise.NO_ALARM) ?: ScreenSunrise.NO_ALARM
        val alarmScreen = intent?.getBooleanExtra(EXTRA_ALARM_SCREEN, false) ?: false
        val armDelay = intent?.getLongExtra(EXTRA_ARM_DELAY_MS, PICKUP_ARM_MS) ?: PICKUP_ARM_MS

        startForegroundCompat(requestedWake, newSource, snoozed = false)

        if (requestedWake <= 0L || (newSource == LightSource.FLASH && !torch.available)) {
            stopSunrise()
            return START_NOT_STICKY
        }
        // A new sunrise replaces whatever was running.
        resetLight()
        source = newSource
        alarmId = newAlarmId
        startAt = now
        wakeAt = maxOf(requestedWake, now)
        holdUntil = wakeAt + holdMs
        if (source == LightSource.SCREEN) {
            val sunrise = ScreenSunrise(
                // Night Light and grayscale go off for the sunrise, or the color is
                // counter-tinted when the app isn't allowed to switch them.
                color = DisplayFilters.suspend(this, color, until = holdUntil + 60_000L),
                startAt = startAt,
                wakeAt = wakeAt,
                holdUntil = holdUntil,
                boost = boost,
                alarmScreen = alarmScreen,
                alarmId = alarmId,
            )
            ScreenSunrise.current.value = sunrise
            overlay.show(sunrise)
            openScreen()
        } else {
            ScreenSunrise.current.value = null
        }
        pickup.start(armDelay)
        acquireWakeLock(holdUntil - now + 60_000L)
        handler.post(tick)
        return START_NOT_STICKY
    }

    /** A snoozed alarm of this app: its alarm screen comes back, dark, with no sunrise. */
    private fun showSnoozed(intent: Intent) {
        startForegroundCompat(0L, LightSource.SCREEN, snoozed = true)
        resetLight()
        val now = System.currentTimeMillis()
        source = LightSource.SCREEN
        alarmId = intent.getIntExtra(EXTRA_ALARM, ScreenSunrise.NO_ALARM)
        startAt = now
        wakeAt = now
        holdUntil = now + HOLD_MS
        ScreenSunrise.current.value = ScreenSunrise(
            color = intent.getIntExtra(EXTRA_COLOR, ScreenColors.DEFAULT),
            startAt = now,
            wakeAt = now,
            holdUntil = holdUntil,
            alarmScreen = true,
            lightOn = false,
            alarmId = alarmId,
        )
        openScreen()
        acquireWakeLock(HOLD_MS + 60_000L)
        handler.post(tick)
    }

    /**
     * Puts the light out quickly (phone picked up, or the light tapped). A sunrise-only
     * alarm then shows its slider; a linked one ends, leaving the Clock app's alarm.
     */
    private fun lightOff() {
        pickup.stop()
        if (source == LightSource.FLASH) {
            fadeTorchOut { stopSunrise() }
            return
        }
        val s = ScreenSunrise.current.value
        if (s == null) {
            stopSunrise()
            return
        }
        if (!s.lightOn) return
        ScreenSunrise.current.value = s.copy(lightOn = false)
        overlay.fadeOut()
        // The phone's own filters come back once the light is gone.
        handler.postDelayed({ DisplayFilters.restore(this) }, LightDriver.FADE_OUT_MS)
        if (!s.alarmScreen) handler.postDelayed(stopRunnable, LightDriver.FADE_OUT_MS + 150L)
    }

    private fun fadeTorchOut(onDone: () -> Unit) {
        val from = currentLevel
        if (from <= 0) {
            onDone()
            return
        }
        currentLevel = FADING
        val steps = 8
        for (i in 1..steps) {
            handler.postDelayed({
                val level = (from * (1f - i / steps.toFloat())).roundToInt()
                torch.setLevel(level)
                if (i == steps) {
                    currentLevel = 0
                    onDone()
                }
            }, i * (TORCH_FADE_MS / steps))
        }
    }

    /**
     * Android blocks background apps from opening activities, so a screen sunrise is
     * normally opened by the notification's full-screen intent, which the system honors
     * when the phone is locked or asleep. With "display over other apps" allowed, or when
     * the app is in front (the Test button), opening it directly works too.
     */
    private fun openScreen() {
        try {
            startActivity(ScreenSunriseActivity.intent(this))
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not open the screen sunrise directly", e)
        }
    }

    private fun startForegroundCompat(wakeAt: Long, source: LightSource, snoozed: Boolean) {
        ensureChannels(this)
        val screen = source == LightSource.SCREEN
        val openApp = if (screen) {
            PendingIntent.getActivity(
                this, 2, ScreenSunriseActivity.intent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SunriseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val time = if (wakeAt > 0L) android.text.format.DateFormat.getTimeFormat(this)
            .format(java.util.Date(wakeAt)) else ""
        val builder = NotificationCompat.Builder(this, if (screen) SCREEN_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sunrise)
            .setContentTitle(if (snoozed) "Snoozed alarm" else "Sunrise in progress")
            .setContentText(
                when {
                    snoozed -> "Slide to dismiss or snooze again"
                    time.isNotEmpty() -> "Full brightness at $time"
                    else -> "Brightening"
                },
            )
            .setContentIntent(openApp)
            .addAction(0, if (snoozed) "Dismiss" else "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
        if (screen) {
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(openApp, true)
        }
        val notification: Notification = builder.build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        wakeLock?.let { if (it.isHeld) it.release() }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SunriseAlarm:sunrise")
            .apply { acquire(timeoutMs) }
    }

    /** Switches every light off and forgets the running sunrise, without stopping the service. */
    private fun resetLight() {
        handler.removeCallbacksAndMessages(null)
        pickup.stop()
        overlay.remove()
        if (currentLevel > 0 || currentLevel == FADING) torch.setLevel(0)
        currentLevel = -1
        DisplayFilters.restore(this)
    }

    private fun stopSunrise() {
        resetLight()
        ScreenSunrise.current.value = null
        alarmId = ScreenSunrise.NO_ALARM
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        resetLight()
        ScreenSunrise.current.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.siullph.sunrisealarm.STOP"
        private const val ACTION_SNOOZE = "com.siullph.sunrisealarm.SNOOZE"
        private const val ACTION_LIGHT_OFF = "com.siullph.sunrisealarm.LIGHT_OFF"
        private const val ACTION_SNOOZED = "com.siullph.sunrisealarm.SNOOZED"
        private const val EXTRA_HOLD_MS = "hold_ms"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_COLOR = "color"
        private const val EXTRA_BOOST = "boost"
        private const val EXTRA_ALARM = "alarm"
        private const val EXTRA_ALARM_SCREEN = "alarm_screen"
        private const val EXTRA_ARM_DELAY_MS = "arm_delay_ms"
        private const val CHANNEL_ID = "sunrise"
        private const val SCREEN_CHANNEL_ID = "sunrise_screen"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 250L
        private const val TORCH_FADE_MS = 400L

        /** [currentLevel] while the flashlight is fading out. */
        private const val FADING = -2

        /** Movement right after a sunrise starts (the phone being set down) isn't a pickup. */
        private const val PICKUP_ARM_MS = 5_000L

        /** How long the light stays at full brightness after the wake-up time. */
        const val HOLD_MS = 30 * 60_000L

        fun start(
            context: Context,
            wakeAt: Long,
            holdMs: Long = HOLD_MS,
            source: LightSource = LightSource.FLASH,
            color: Int = ScreenColors.DEFAULT,
            boost: Boolean = false,
            alarmId: Int = ScreenSunrise.NO_ALARM,
            alarmScreen: Boolean = alarmId != ScreenSunrise.NO_ALARM,
            pickupArmDelayMs: Long = PICKUP_ARM_MS,
        ) {
            val intent = Intent(context, SunriseService::class.java)
                .putExtra(Scheduler.EXTRA_WAKE_AT, wakeAt)
                .putExtra(EXTRA_HOLD_MS, holdMs)
                .putExtra(EXTRA_SOURCE, source.name)
                .putExtra(EXTRA_COLOR, color)
                .putExtra(EXTRA_BOOST, boost)
                .putExtra(EXTRA_ALARM, alarmId)
                .putExtra(EXTRA_ALARM_SCREEN, alarmScreen)
                .putExtra(EXTRA_ARM_DELAY_MS, pickupArmDelayMs)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startSnoozed(context: Context, alarmId: Int, color: Int) {
            val intent = Intent(context, SunriseService::class.java)
                .setAction(ACTION_SNOOZED)
                .putExtra(EXTRA_ALARM, alarmId)
                .putExtra(EXTRA_COLOR, color)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Ends the sunrise (Dismiss). */
        fun stop(context: Context) = send(context, ACTION_STOP)

        /** Ends the sunrise and brings this app's alarm screen back after [Scheduler.SNOOZE_MS]. */
        fun snooze(context: Context) = send(context, ACTION_SNOOZE)

        /** Fades the light out as if the phone had been picked up. */
        fun lightOff(context: Context) = send(context, ACTION_LIGHT_OFF)

        private fun send(context: Context, action: String) {
            try {
                context.startService(Intent(context, SunriseService::class.java).setAction(action))
            } catch (e: Exception) {
                // Not running (or not allowed to start from here); nothing to stop.
                Log.w("SunriseAlarm", "Could not send $action", e)
            }
        }

        private fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val flash = NotificationChannel(
                CHANNEL_ID, "Sunrise", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the flashlight is brightening"
                setSound(null, null)
                enableVibration(false)
            }
            // High importance so the full-screen intent can open the screen light; still silent.
            val screen = NotificationChannel(
                SCREEN_CHANNEL_ID, "Screen sunrise", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Opens the screen light and the alarm screen"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannels(listOf(flash, screen))
        }
    }
}
