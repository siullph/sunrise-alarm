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

class MainActivity : ComponentActivity() {
    /** Bumped whenever stored or system state may have changed, so the UI re-reads it. */
    private var refresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SunriseTheme {
                MainScreen(refresh = refresh, onChanged = { changed() })
            }
        }
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
