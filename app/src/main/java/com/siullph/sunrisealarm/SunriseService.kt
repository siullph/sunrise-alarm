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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.ceil

/**
 * Runs one sunrise: the flashlight starts at its dimmest level and brightens until
 * the wake-up time, then stays at full brightness for a while before switching off.
 * Light only: the notification channel is silent.
 */
class SunriseService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var torch: Torch
    private var wakeLock: PowerManager.WakeLock? = null

    private var startAt = 0L
    private var wakeAt = 0L
    private var holdUntil = 0L
    private var currentLevel = -1

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now >= holdUntil) {
                stopSunrise()
                return
            }
            val span = (wakeAt - startAt).coerceAtLeast(1L)
            val progress = ((now - startAt).toDouble() / span).coerceIn(0.0, 1.0)
            // Squared curve: our eyes notice early brightness steps much more than late ones.
            val level = ceil(torch.maxLevel * progress * progress).toInt().coerceIn(1, torch.maxLevel)
            if (level != currentLevel) {
                torch.setLevel(level)
                currentLevel = level
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        torch = Torch(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSunrise()
            return START_NOT_STICKY
        }
        val now = System.currentTimeMillis()
        val requestedWake = intent?.getLongExtra(Scheduler.EXTRA_WAKE_AT, 0L) ?: 0L
        val holdMs = intent?.getLongExtra(EXTRA_HOLD_MS, HOLD_MS) ?: HOLD_MS

        startForegroundCompat(requestedWake)

        if (requestedWake <= 0L || !torch.available) {
            stopSunrise()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tick)
        startAt = now
        wakeAt = maxOf(requestedWake, now)
        holdUntil = wakeAt + holdMs
        currentLevel = -1
        acquireWakeLock(holdUntil - now + 60_000L)
        handler.post(tick)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(wakeAt: Long) {
        ensureChannel(this)
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SunriseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val time = if (wakeAt > 0L) android.text.format.DateFormat.getTimeFormat(this)
            .format(java.util.Date(wakeAt)) else ""
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sunrise)
            .setContentTitle("Sunrise in progress")
            .setContentText(if (time.isNotEmpty()) "Full brightness at $time" else "Brightening")
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
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

    private fun stopSunrise() {
        handler.removeCallbacks(tick)
        if (currentLevel > 0) torch.setLevel(0)
        currentLevel = -1
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (currentLevel > 0) torch.setLevel(0)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.siullph.sunrisealarm.STOP"
        private const val EXTRA_HOLD_MS = "hold_ms"
        private const val CHANNEL_ID = "sunrise"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 1_000L

        /** How long the light stays at full brightness after the wake-up time. */
        const val HOLD_MS = 30 * 60_000L

        fun start(context: Context, wakeAt: Long, holdMs: Long = HOLD_MS) {
            val intent = Intent(context, SunriseService::class.java)
                .putExtra(Scheduler.EXTRA_WAKE_AT, wakeAt)
                .putExtra(EXTRA_HOLD_MS, holdMs)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SunriseService::class.java).setAction(ACTION_STOP),
            )
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "Sunrise", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the flashlight is brightening"
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
