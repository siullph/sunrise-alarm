package com.siullph.sunrisealarm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Notices the phone being picked up off the nightstand. Uses the gyroscope: the phone has
 * to turn by about 35° within a second and a half, which a bump or a shaking mattress does
 * not do. Phones without a gyroscope fall back to the accelerometer and watch for the phone
 * tilting away from how it was lying.
 */
class PickupDetector(context: Context, private val onPickup: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var armAt = 0L
    private var listening = false
    private var lastNanos = 0L
    private val turns = ArrayDeque<Pair<Long, Float>>()
    private var turned = 0f
    private var resting: FloatArray? = null

    val available: Boolean get() = gyroscope != null || accelerometer != null

    /** Starts watching; movement during the first [armDelayMs] is ignored. */
    fun start(armDelayMs: Long) {
        stop()
        val sensor = gyroscope ?: accelerometer ?: return
        armAt = SystemClock.elapsedRealtime() + armDelayMs
        listening = sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) == true
    }

    fun stop() {
        if (listening) sensors?.unregisterListener(this)
        listening = false
        lastNanos = 0L
        turns.clear()
        turned = 0f
        resting = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening) return
        if (SystemClock.elapsedRealtime() < armAt) {
            lastNanos = event.timestamp
            return
        }
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) onGyroscope(event) else onAccelerometer(event)
    }

    private fun onGyroscope(event: SensorEvent) {
        val dt = if (lastNanos == 0L) 0f else ((event.timestamp - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = event.timestamp
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val speed = sqrt(x * x + y * y + z * z)
        // Ignore sensor drift and tiny tremors.
        val angle = if (speed < NOISE_RAD_S) 0f else speed * dt
        turns.addLast(event.timestamp to angle)
        turned += angle
        while (turns.isNotEmpty() && event.timestamp - turns.first().first > WINDOW_NS) {
            turned -= turns.removeFirst().second
        }
        if (turned > PICKUP_RAD) fire()
    }

    private fun onAccelerometer(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = sqrt(x * x + y * y + z * z)
        if (g < 0.1f) return
        val now = floatArrayOf(x / g, y / g, z / g)
        val base = resting ?: run {
            resting = now
            return
        }
        val dot = (now[0] * base[0] + now[1] * base[1] + now[2] * base[2]).coerceIn(-1f, 1f)
        if (acos(dot) > PICKUP_RAD || abs(g - SensorManager.GRAVITY_EARTH) > JOLT_M_S2) fire()
    }

    private fun fire() {
        stop()
        onPickup()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val NOISE_RAD_S = 0.15f
        const val PICKUP_RAD = 0.6f
        const val WINDOW_NS = 1_500_000_000L
        const val JOLT_M_S2 = 4f
    }
}
