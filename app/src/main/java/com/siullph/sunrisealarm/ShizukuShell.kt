package com.siullph.sunrisealarm

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs shell commands with adb's privileges through the Shizuku app, for what no permission
 * an app can be granted reaches: Bedtime mode's grayscale is the display's global saturation,
 * which only the system and the adb shell may set (`cmd color_display set-saturation`).
 *
 * Shizuku has to be installed, started (from a computer, or with Wireless debugging on the
 * phone itself; again after every reboot) and allowed for this app.
 */
object ShizukuShell {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 7

    /** Shizuku is running (its binder reached this process). */
    fun running(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    } catch (e: Throwable) {
        false
    }

    /** Running and this app is allowed to use it. */
    fun ready(): Boolean = running() && try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun installed(pm: PackageManager): Boolean = try {
        pm.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            Log.w("SunriseAlarm", "Could not ask Shizuku for permission", e)
        }
    }

    /**
     * Waits up to [timeoutMs] for Shizuku's binder, which arrives a moment after this app's
     * process starts (as it does when an alarm wakes it). Call off the main thread.
     */
    fun awaitReady(timeoutMs: Long): Boolean {
        if (ready()) return true
        val latch = CountDownLatch(1)
        val listener = Shizuku.OnBinderReceivedListener { latch.countDown() }
        try {
            Shizuku.addBinderReceivedListenerSticky(listener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Throwable) {
            Log.w("SunriseAlarm", "Shizuku unavailable", e)
        } finally {
            try {
                Shizuku.removeBinderReceivedListener(listener)
            } catch (_: Throwable) {
            }
        }
        return ready()
    }

    /** Runs [command] with `sh -c` as the shell user; returns its output, or null on failure. */
    fun exec(command: String): String? {
        if (!ready()) return null
        return try {
            // Shizuku keeps newProcess private (it prefers bound services), but a one-off
            // command is all this needs.
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val out = process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            process.waitFor()
            out
        } catch (e: Throwable) {
            Log.w("SunriseAlarm", "Shizuku command failed: $command", e)
            null
        }
    }
}
