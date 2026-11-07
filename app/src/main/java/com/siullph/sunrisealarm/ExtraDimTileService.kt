package com.siullph.sunrisealarm

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** The "Extra dim" Quick Settings tile. */
class ExtraDimTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        show(ExtraDim.isOn(this))
    }

    override fun onClick() {
        super.onClick()
        if (!ExtraDim.isOn(this) && ExtraDim.needsOverlayPermission(this)) {
            // Nothing to switch on with yet: open the app, which explains what to allow.
            open(Intent(this, MainActivity::class.java))
            return
        }
        val on = !ExtraDim.isOn(this)
        if (ExtraDim.setOn(this, on)) {
            show(on)
        } else {
            // Android may refuse to start the overlay from the shade on some phones; a
            // see-through activity is allowed to.
            open(Intent(this, ExtraDimToggleActivity::class.java))
        }
    }

    private fun show(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.extra_dim_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_extra_dim)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) "On" else "Off"
        }
        tile.updateTile()
    }

    private fun open(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w("SunriseAlarm", "Could not open from the Extra dim tile", e)
        }
    }
}

/** Switches the Extra dim overlay on from the foreground, then gets out of the way. */
class ExtraDimToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ExtraDim.setOn(this, true)
        finish()
    }
}
