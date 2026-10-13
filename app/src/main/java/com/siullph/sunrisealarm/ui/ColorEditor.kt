package com.siullph.sunrisealarm.ui

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.siullph.sunrisealarm.ColorSlot
import com.siullph.sunrisealarm.ScreenColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class PickMode { WHEEL, TEMPERATURE }

private const val MIN_KELVIN = 1000
private const val MAX_KELVIN = 10000
private const val NEW_COLOR_KELVIN = 2700

/**
 * Creates or edits a color slot: a name, then a color from the wheel or the color
 * temperature slider. The ⋮ menu holds "Fine-tune color" (exact red, green and blue
 * sliders) and, for a saved color, "Delete color".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorEditorDialog(
    initial: ColorSlot?,
    defaultName: String,
    onDismiss: () -> Unit,
    onSave: (name: String, color: Int) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val start = initial?.color ?: ScreenColors.fromKelvin(NEW_COLOR_KELVIN)
    var color by remember { mutableIntStateOf(start) }
    var name by remember { mutableStateOf(initial?.name ?: defaultName) }
    var mode by remember { mutableStateOf(if (initial == null) PickMode.TEMPERATURE else PickMode.WHEEL) }
    val startHsv = remember { FloatArray(3).also { AndroidColor.colorToHSV(start, it) } }
    var hue by remember { mutableFloatStateOf(startHsv[0]) }
    var saturation by remember { mutableFloatStateOf(startHsv[1]) }
    var value by remember { mutableFloatStateOf(startHsv[2]) }
    var kelvin by remember { mutableIntStateOf(NEW_COLOR_KELVIN) }
    var fineTune by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    fun applyHsv() {
        color = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value))
    }

    /** Moves the wheel to match a color set some other way. */
    fun syncWheel(c: Int) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(c, hsv)
        if (hsv[1] > 0.001f) hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
    }

    val preview by animateColorAsState(Color(color), tween(180), label = "preview")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (initial == null) "New color" else "Edit color", Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Fine-tune color") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = if (fineTune) MaterialTheme.colorScheme.primary else Color.Transparent,
                                )
                            },
                            onClick = {
                                fineTune = !fineTune
                                menuOpen = false
                            },
                        )
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text("Delete color") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(preview),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PickMode.entries.forEachIndexed { index, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(index, PickMode.entries.size),
                        ) { Text(if (m == PickMode.WHEEL) "Wheel" else "Temperature") }
                    }
                }
                Spacer(Modifier.height(16.dp))
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(150)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "pick mode",
                ) { m ->
                    when (m) {
                        PickMode.WHEEL -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ColorWheel(
                                hue = hue,
                                saturation = saturation,
                                value = value,
                                onChange = { h, s ->
                                    hue = h
                                    saturation = s
                                    if (value < 0.05f) value = 1f
                                    applyHsv()
                                },
                                modifier = Modifier.fillMaxWidth(0.85f),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Brightness ${(value * 100).roundToInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Slider(
                                value = value,
                                onValueChange = {
                                    value = it
                                    applyHsv()
                                },
                                valueRange = 0.05f..1f,
                            )
                        }
                        PickMode.TEMPERATURE -> Column {
                            Text("$kelvin K", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            KelvinBar()
                            Slider(
                                value = kelvin.toFloat(),
                                onValueChange = {
                                    kelvin = (it / 50f).roundToInt() * 50
                                    color = ScreenColors.fromKelvin(kelvin)
                                    syncWheel(color)
                                },
                                valueRange = MIN_KELVIN.toFloat()..MAX_KELVIN.toFloat(),
                            )
                            Row {
                                Text("Candle", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                Text("Daylight", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                Text("Blue sky", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = fineTune,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(Modifier.padding(top = 16.dp)) {
                        Text(
                            "Fine-tune  #%06X".format(color and 0xFFFFFF),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        fun setChannel(r: Int, g: Int, b: Int) {
                            color = AndroidColor.rgb(r, g, b)
                            syncWheel(color)
                        }
                        val r = AndroidColor.red(color)
                        val g = AndroidColor.green(color)
                        val b = AndroidColor.blue(color)
                        ChannelSlider("Red", r, Color.Red) { setChannel(it, g, b) }
                        ChannelSlider("Green", g, Color.Green) { setChannel(r, it, b) }
                        ChannelSlider("Blue", b, Color.Blue) { setChannel(r, g, it) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim().ifEmpty { defaultName }, color) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Hue around the circle, saturation from the center out. Drag or tap to pick. */
@Composable
private fun ColorWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    val hues = remember { (0..12).map { Color.hsv(it * 30f, 1f, 1f) } }
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                fun pick(position: Offset) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val radius = minOf(cx, cy)
                    val dx = position.x - cx
                    val dy = position.y - cy
                    val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                    currentOnChange((angle + 360f) % 360f, (hypot(dx, dy) / radius).coerceIn(0f, 1f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pick(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        pick(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        val radius = size.minDimension / 2f
        drawCircle(Brush.sweepGradient(hues, center), radius)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, radius), radius)
        if (value < 1f) drawCircle(Color.Black.copy(alpha = 1f - value), radius)
        val a = Math.toRadians(hue.toDouble())
        val knob = Offset(
            center.x + (cos(a) * saturation * radius).toFloat(),
            center.y + (sin(a) * saturation * radius).toFloat(),
        )
        drawCircle(Color.Black.copy(alpha = 0.35f), 14.dp.toPx(), knob, style = Stroke(5.dp.toPx()))
        drawCircle(Color.White, 14.dp.toPx(), knob, style = Stroke(3.dp.toPx()))
    }
}

@Composable
private fun KelvinBar() {
    val stops = remember {
        (MIN_KELVIN..MAX_KELVIN step 500).map { Color(ScreenColors.fromKelvin(it)) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(MaterialTheme.shapes.small)
            .background(Brush.horizontalGradient(stops)),
    )
}

@Composable
fun ChannelSlider(label: String, value: Int, tint: Color, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
        )
    }
}
