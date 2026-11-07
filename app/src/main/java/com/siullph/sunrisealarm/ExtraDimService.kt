package com.siullph.sunrisealarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Extra dim's overlay way: a see-through black window over every app, kept up by a quiet
 * foreground service so Android doesn't take it away. Touches go through it.
 */
class ExtraDimService : Service() {
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ExtraDim.setOn(this, false)
            return START_NOT_STICKY
        }
        startForegroundCompat()
        // Restarted by Android after being killed: come back only if still wanted.
        if (!ExtraDim.overlayWanted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this) || !show()) {
            // Can't be shown (permission taken away): say so on the tile instead of "On".
            ExtraDim.setOn(this, false)
            return START_NOT_STICKY
        }
        ExtraDim.refreshTile(this)
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        remove()
        ExtraDim.refreshTile(this)
        super.onDestroy()
    }

    private fun show(): Boolean {
        if (view != null) {
            update()
            return true
        }
        val wm = getSystemService(WindowManager::class.java)
        // A square as big as the screen's long side covers it whichever way it's turned.
        val side = longSide(wm)
        @Suppress("DEPRECATION")
        val lp = WindowManager.LayoutParams(
            side,
            side,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            title = "Extra dim"
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = ExtraDim.overlayAlpha(this@ExtraDimService)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
        val v = View(this).apply { setBackgroundColor(Color.BLACK) }
        return try {
            wm.addView(v, lp)
            view = v
            params = lp
            true
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not show the Extra dim overlay", e)
            false
        }
    }

    private fun update() {
        val v = view ?: return
        val lp = params ?: return
        lp.alpha = ExtraDim.overlayAlpha(this)
        try {
            getSystemService(WindowManager::class.java).updateViewLayout(v, lp)
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not update the Extra dim overlay", e)
        }
    }

    private fun remove() {
        val v = view ?: return
        view = null
        params = null
        try {
            getSystemService(WindowManager::class.java).removeView(v)
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not remove the Extra dim overlay", e)
        }
    }

    private fun longSide(wm: WindowManager): Int {
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p.x to p.y
        }
        // A little extra in case the reported size leaves out a rounded corner or cutout.
        return maxOf(w, h) + 200
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Extra dim", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Extra dim is on"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ExtraDimService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_extra_dim)
            .setContentTitle("Extra dim is on")
            .setContentText("Tap to change how dark")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val CHANNEL_ID = "extra_dim"
        private const val NOTIFICATION_ID = 3
        private const val ACTION_STOP = "com.siullph.sunrisealarm.EXTRA_DIM_STOP"

        private var instance: ExtraDimService? = null

        /** Returns false if Android refused to start it from where it was asked. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context, Intent(context, ExtraDimService::class.java),
            )
            true
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not start the Extra dim overlay", e)
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ExtraDimService::class.java))
        }

        /** Re-reads how dark to be, if the overlay is up. */
        fun applyDimness() {
            ContextCompat.getMainExecutor(instance ?: return).execute { instance?.update() }
        }
    }
}
