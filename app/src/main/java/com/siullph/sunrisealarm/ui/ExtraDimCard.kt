package com.siullph.sunrisealarm.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.siullph.sunrisealarm.DisplayFilters
import com.siullph.sunrisealarm.ExtraDim
import com.siullph.sunrisealarm.ExtraDimTileService
import com.siullph.sunrisealarm.R
import com.siullph.sunrisealarm.ShizukuShell
import kotlin.math.roundToInt

/**
 * Extra dim: its switch, a button that adds the Quick Settings tile, how dark it gets, and
 * which way it dims (Android's own Extra dim, or an overlay).
 */
@Composable
fun ExtraDimCard(refresh: Int, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var on by remember(refresh) { mutableStateOf(ExtraDim.isOn(context)) }
    var dimness by remember(refresh) { mutableFloatStateOf(ExtraDim.dimness(context)) }
    var method by remember(refresh) { mutableStateOf(ExtraDim.method(context)) }
    val active = remember(refresh, method) { ExtraDim.activeMethod(context) }
    val canOverlay = remember(refresh) { Settings.canDrawOverlays(context) }
    val canWriteSecure = remember(refresh) { DisplayFilters.canOverride(context) }
    val shizukuReady = remember(refresh) { ShizukuShell.ready() }
    val systemSupported = remember { ExtraDim.systemSupported() }
    var tileMessage by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            Modifier
                .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Extra dim", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = on,
                    onCheckedChange = {
                        if (it && ExtraDim.needsOverlayPermission(context)) {
                            openOverlaySettings(context)
                        } else if (ExtraDim.setOn(context, it)) {
                            on = it
                        }
                        onChanged()
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Body(
                "Makes the screen darker than its lowest brightness, for reading in the dark. " +
                    "Add the Extra dim tile to Quick Settings to switch it from anywhere.",
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                FilledTonalButton(
                    onClick = {
                        requestTile(context) { tileMessage = it }
                    },
                    modifier = Modifier.padding(top = 10.dp),
                ) { Text("Add tile to Quick Settings") }
            }
            Body(
                tileMessage ?: ("Or swipe down twice, tap the pencil, and drag \"Extra dim\" " +
                    "(the one with this app's name under it) into your tiles."),
                small = true,
            )

            Spacer(Modifier.height(14.dp))
            Text("How dark: ${(dimness * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = dimness,
                onValueChange = {
                    dimness = it
                    if (on) ExtraDim.setDimness(context, it)
                },
                onValueChangeFinished = { ExtraDim.setDimness(context, dimness) },
            )

            Spacer(Modifier.height(10.dp))
            Text("Dim with", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            val options = listOf(
                ExtraDim.Method.AUTO to "Automatic",
                ExtraDim.Method.SYSTEM to "Android",
                ExtraDim.Method.OVERLAY to "Overlay",
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = method == value,
                        onClick = {
                            method = value
                            ExtraDim.setMethod(context, value)
                            onChanged()
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        enabled = value != ExtraDim.Method.SYSTEM || systemSupported,
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Body(
                if (active == ExtraDim.Method.SYSTEM) {
                    "Using Android's own Extra dim, which dims everything, the status bar and " +
                        "notification shade included. If the screen doesn't get darker on this " +
                        "phone, choose Overlay."
                } else {
                    "Using an overlay: a see-through black layer over your apps. The status " +
                        "bar, navigation bar and notification shade stay at normal brightness, " +
                        "and Android keeps it from going darker than 80% so taps still work."
                },
            )

            if (active == ExtraDim.Method.OVERLAY && !canOverlay) {
                Body("The overlay needs \"display over other apps\".", small = true)
                FilledTonalButton(
                    onClick = { openOverlaySettings(context) },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Allow") }
            }

            if (systemSupported && !canWriteSecure && method != ExtraDim.Method.OVERLAY) {
                Spacer(Modifier.height(10.dp))
                Body(
                    "To use Android's own Extra dim, the app needs the same permission as " +
                        "Night Light, granted once " +
                        if (shizukuReady) "with Shizuku." else "from a computer with USB debugging on:",
                    small = true,
                )
                if (shizukuReady) {
                    FilledTonalButton(
                        onClick = {
                            DisplayFilters.grantWithShizuku(context) {
                                ContextCompat.getMainExecutor(context).execute { onChanged() }
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Allow with Shizuku") }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        DisplayFilters.GRANT_COMMAND,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(12.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(DisplayFilters.GRANT_COMMAND)) }) {
                            Text("Copy command")
                        }
                    }
                }
            }

            Body(
                "A screen sunrise switches Extra dim off while it's lit and back on afterwards.",
                small = true,
            )
        }
    }
}

private fun requestTile(context: Context, onResult: (String) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val sbm = context.getSystemService(StatusBarManager::class.java) ?: return
    try {
        sbm.requestAddTileService(
            ComponentName(context, ExtraDimTileService::class.java),
            context.getString(R.string.extra_dim_tile_label),
            Icon.createWithResource(context, R.drawable.ic_extra_dim),
            ContextCompat.getMainExecutor(context),
        ) { result ->
            onResult(
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Added. Find it in Quick Settings."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The tile is already in Quick Settings."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Not added."
                    else -> "Android didn't add it. Swipe down twice, tap the pencil, and drag " +
                        "\"Extra dim\" into your tiles."
                },
            )
        }
    } catch (e: Exception) {
        onResult("Swipe down twice, tap the pencil, and drag \"Extra dim\" into your tiles.")
    }
}

private fun openOverlaySettings(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
    }
}

@Composable
private fun Body(text: String, small: Boolean = false) {
    Text(
        text,
        style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (small) Modifier.padding(top = 4.dp) else Modifier,
    )
}
