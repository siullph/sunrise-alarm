package com.siullph.sunrisealarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.siullph.sunrisealarm.ui.MainScreen
import com.siullph.sunrisealarm.ui.SunriseTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    /** Bumped whenever stored or system state may have changed, so the UI re-reads it. */
    private var refresh by mutableIntStateOf(0)

    // Shizuku connects a moment after the app starts, and answers permission requests later.
    private val shizukuConnected = Shizuku.OnBinderReceivedListener { refresh++ }
    private val shizukuAnswered = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addBinderReceivedListenerSticky(shizukuConnected)
        Shizuku.addRequestPermissionResultListener(shizukuAnswered)
        setContent {
            SunriseTheme {
                MainScreen(refresh = refresh, onChanged = { changed() })
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuConnected)
        Shizuku.removeRequestPermissionResultListener(shizukuAnswered)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        changed()
    }

    private fun changed() {
        Scheduler.rescheduleAll(this)
        DisplayFilters.restoreIfIdle(this)
        refresh++
    }
}
