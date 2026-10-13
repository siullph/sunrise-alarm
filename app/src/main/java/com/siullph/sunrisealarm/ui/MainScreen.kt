package com.siullph.sunrisealarm.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.siullph.sunrisealarm.AlarmStore
import com.siullph.sunrisealarm.ColorSlot
import com.siullph.sunrisealarm.LightSource
import com.siullph.sunrisealarm.Scheduler
import com.siullph.sunrisealarm.ScreenColors
import com.siullph.sunrisealarm.SunriseAlarm
import com.siullph.sunrisealarm.SunriseService
import com.siullph.sunrisealarm.Torch
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val MIN_FADE = 5
private const val MAX_FADE = 60
private const val FADE_STEP = 5

/** The user's saved colors, shared by every light picker on the screen. */
class ColorLibrary(private val store: AlarmStore, private val onAlarmsChanged: () -> Unit) {
    var slots by mutableStateOf(store.colorSlots())
        private set

    val canAdd: Boolean get() = slots.size < ColorSlot.MAX

    fun add(name: String, color: Int): ColorSlot {
        val slot = ColorSlot(store.newColorSlotId(), name, color)
        save(slots + slot)
        return slot
    }

    /** Saves a changed slot; alarms that used its old color follow it to the new one. */
    fun update(slot: ColorSlot) {
        val old = slots.firstOrNull { it.id == slot.id } ?: return
        save(slots.map { if (it.id == slot.id) slot else it })
        if (old.color != slot.color) {
            store.replaceColor(old.color, slot.color)
            onAlarmsChanged()
        }
    }

    fun delete(id: Int) = save(slots.filterNot { it.id == id })

    fun nextName(): String {
        var n = slots.size + 1
        while (slots.any { it.name == "Color $n" }) n++
        return "Color $n"
    }

    private fun save(list: List<ColorSlot>) {
        store.saveColorSlots(list)
        slots = list.take(ColorSlot.MAX)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(refresh: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AlarmStore(context) }
    val torch = remember { Torch(context) }
    val library = remember { ColorLibrary(store, onChanged) }
    val alarms = remember(refresh) { store.alarms() }
    val phoneAlarm = remember(refresh) { Scheduler.nextPhoneAlarm(context) }
    val canExact = remember(refresh) { Scheduler.canScheduleExact(context) }
    val canFullScreen = remember(refresh) {
        Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }
    val canOverlay = remember(refresh) { Settings.canDrawOverlays(context) }
    val usesScreen = remember(refresh) {
        store.linkedSource == LightSource.SCREEN || alarms.any { it.source == LightSource.SCREEN }
    }

    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    // null = closed, id 0 = new alarm.
    var editing by remember { mutableStateOf<SunriseAlarm?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text("Sunrise Alarm") },
                actions = {
                    TextButton(onClick = { SunriseService.stop(context) }) { Text("Stop light") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editing = SunriseAlarm(
                        id = 0,
                        hour = 7,
                        minute = 0,
                        fadeMinutes = 30,
                        source = if (torch.available) LightSource.FLASH else LightSource.SCREEN,
                    )
                },
                expanded = fabExpanded,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New sunrise") },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!torch.available) {
                item(key = "no-flash") {
                    Notice(
                        "This phone has no flashlight the app can control, so use the screen " +
                            "as the light instead.",
                        Modifier.animateItem(),
                    )
                }
            } else if (!torch.dimmable) {
                item(key = "flash-not-dimmable") {
                    Notice(
                        "This phone's flashlight only switches on or off, so it turns on at the " +
                            "start of the fade instead of brightening gradually. The screen light " +
                            "always fades gradually.",
                        Modifier.animateItem(),
                    )
                }
            }
            if (!notificationsGranted) {
                item(key = "notifications") {
                    Notice(
                        "Allow notifications so a running sunrise shows a Stop button and the " +
                            "screen light can turn on.",
                        Modifier.animateItem(),
                        action = "Allow",
                    ) { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
            }
            if (usesScreen && !canOverlay) {
                item(key = "overlay") {
                    Notice(
                        "Allow \"display over other apps\" so the screen light stays on top of " +
                            "your Clock app's alarm, and fades away to its slider when you pick " +
                            "the phone up.",
                        Modifier.animateItem(),
                        action = "Allow",
                    ) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }
            }
            if (usesScreen && !canFullScreen && Build.VERSION.SDK_INT >= 34) {
                item(key = "full-screen") {
                    Notice(
                        "Allow full-screen notifications, or the screen light can't turn on by " +
                            "itself while the phone is locked.",
                        Modifier.animateItem(),
                        action = "Allow",
                    ) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }
            }
            if (!canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item(key = "exact") {
                    Notice(
                        "Allow exact alarms, or sunrises may start a few minutes late.",
                        Modifier.animateItem(),
                        action = "Allow",
                    ) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }
            }

            item(key = "linked-title") { SectionTitle("Phone alarm", Modifier.animateItem()) }
            item(key = "linked") {
                LinkedAlarmCard(
                    context = context,
                    store = store,
                    library = library,
                    flashAvailable = torch.available,
                    phoneAlarmAt = phoneAlarm?.wakeAt,
                    appLabel = phoneAlarm?.appLabel,
                    refresh = refresh,
                    onChanged = onChanged,
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "alarms-title") { SectionTitle("Sunrise-only alarms", Modifier.animateItem()) }
            if (alarms.isEmpty()) {
                item(key = "alarms-empty") {
                    Text(
                        "None yet. Tap New sunrise to add one that runs on its own, without a phone alarm.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.animateItem().padding(horizontal = 4.dp),
                    )
                }
            }
            items(alarms, key = { it.id }) { alarm ->
                AlarmRow(
                    context = context,
                    alarm = alarm,
                    library = library,
                    onToggle = { enabled ->
                        store.save(alarm.copy(enabled = enabled))
                        onChanged()
                    },
                    onClick = { editing = alarm },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    editing?.let { alarm ->
        AlarmEditor(
            initial = alarm,
            library = library,
            flashAvailable = torch.available,
            onDismiss = { editing = null },
            onSave = { saved ->
                val id = if (saved.id == 0) store.newId() else saved.id
                // A changed alarm starts fresh, so it can fire even if it ran earlier today.
                store.save(saved.copy(id = id, enabled = true, lastFiredWake = 0L))
                editing = null
                onChanged()
            },
            onDelete = if (alarm.id == 0) null else {
                {
                    store.delete(alarm.id)
                    Scheduler.cancel(context, alarm.id)
                    Scheduler.cancelSnooze(context, alarm.id)
                    editing = null
                    onChanged()
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 12.dp),
    )
}

@Composable
private fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            Modifier
                .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                .padding(20.dp),
        ) { content() }
    }
}

@Composable
private fun LinkedAlarmCard(
    context: Context,
    store: AlarmStore,
    library: ColorLibrary,
    flashAvailable: Boolean,
    phoneAlarmAt: Long?,
    appLabel: String?,
    refresh: Int,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var enabled by remember(refresh) { mutableStateOf(store.linkedEnabled) }
    var fade by remember(refresh) { mutableIntStateOf(store.linkedFadeMinutes) }
    var source by remember(refresh) { mutableStateOf(store.linkedSource) }
    var color by remember(refresh) { mutableIntStateOf(store.linkedColor) }
    var boost by remember(refresh) { mutableStateOf(store.linkedBoost) }

    SectionCard(modifier) {
        if (phoneAlarmAt == null) {
            Text("No upcoming phone alarm found.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Set an alarm in your Clock app and it will show up here, so you can add a sunrise to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                formatDayTime(context, phoneAlarmAt),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Next alarm" + (appLabel?.let { ", set in $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sunrise before my phone alarm", modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    store.linkedEnabled = it
                    onChanged()
                },
            )
        }
        AnimatedVisibility(
            visible = enabled,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                FadeSlider(
                    minutes = fade,
                    onChange = { fade = it },
                    onChangeFinished = {
                        store.linkedFadeMinutes = fade
                        onChanged()
                    },
                )
                LightPicker(
                    source = source,
                    color = color,
                    boost = boost,
                    library = library,
                    flashAvailable = flashAvailable,
                    onSourceChange = {
                        source = it
                        store.linkedSource = it
                        onChanged()
                    },
                    onColorChange = {
                        color = it
                        store.linkedColor = it
                        onChanged()
                    },
                    onBoostChange = {
                        boost = it
                        store.linkedBoost = it
                    },
                )
                if (phoneAlarmAt != null) {
                    Text(
                        "The light starts at ${formatTime(context, phoneAlarmAt - fade * 60_000L)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Android only shares your next alarm, so this follows whichever phone alarm comes " +
                "next. Snoozes of that alarm never start another sunrise.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                try {
                    context.startActivity(
                        Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (e: Exception) {
                    // No clock app handles this intent.
                }
            },
            contentPadding = PaddingValues(0.dp),
        ) { Text("Open Clock app") }
    }
}

@Composable
private fun FadeSlider(minutes: Int, onChange: (Int) -> Unit, onChangeFinished: () -> Unit = {}) {
    Column {
        Text("Fade: $minutes min", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = minutes.toFloat(),
            onValueChange = { onChange((it / FADE_STEP).roundToInt().times(FADE_STEP).coerceIn(MIN_FADE, MAX_FADE)) },
            onValueChangeFinished = onChangeFinished,
            valueRange = MIN_FADE.toFloat()..MAX_FADE.toFloat(),
            steps = (MAX_FADE - MIN_FADE) / FADE_STEP - 1,
        )
    }
}

@Composable
private fun AlarmRow(
    context: Context,
    alarm: SunriseAlarm,
    library: ColorLibrary,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        if (alarm.enabled) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "alarm container",
    )
    val content by animateColorAsState(
        if (alarm.enabled) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "alarm content",
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatHourMinute(context, alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val light = if (alarm.source == LightSource.SCREEN) {
                        "Screen, " + colorName(alarm.color, library.slots)
                    } else {
                        "Flash"
                    }
                    Text(
                        "${alarm.fadeMinutes} min fade · ${describeDays(alarm.days)} · $light",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (alarm.source == LightSource.SCREEN) {
                        Spacer(Modifier.width(6.dp))
                        ColorDot(alarm.color, size = 12.dp)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(
    initial: SunriseAlarm,
    library: ColorLibrary,
    flashAvailable: Boolean,
    onDismiss: () -> Unit,
    onSave: (SunriseAlarm) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    var fade by remember { mutableIntStateOf(initial.fadeMinutes) }
    var days by remember { mutableStateOf(initial.days) }
    var source by remember { mutableStateOf(initial.source) }
    var color by remember { mutableIntStateOf(initial.color) }
    var boost by remember { mutableStateOf(initial.boost) }

    /** Slides the sheet away before acting, so it closes with its animation. */
    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                if (initial.id == 0) "New sunrise" else "Edit sunrise",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Wake-up time (full brightness)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = timeState)
            }
            FadeSlider(minutes = fade, onChange = { fade = it })
            LightPicker(
                source = source,
                color = color,
                boost = boost,
                library = library,
                flashAvailable = flashAvailable,
                onSourceChange = { source = it },
                onColorChange = { color = it },
                onBoostChange = { boost = it },
            )
            Spacer(Modifier.height(12.dp))
            Text("Repeat", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            DayPicker(days = days, onChange = { days = it })
            Spacer(Modifier.height(6.dp))
            Text(
                if (days.isEmpty()) "No days picked: runs once." else describeDays(days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    OutlinedButton(onClick = { close(onDelete) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { close(onDismiss) }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val saved = initial.copy(
                        hour = timeState.hour,
                        minute = timeState.minute,
                        fadeMinutes = fade,
                        days = days,
                        source = source,
                        color = color,
                        boost = boost,
                    )
                    close { onSave(saved) }
                }) { Text("Save") }
            }
        }
    }
}

/**
 * Flash or screen; for the screen, its color (white plus up to 10 saved colors) and the
 * peak brightness boost; and a short test run of exactly this light.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LightPicker(
    source: LightSource,
    color: Int,
    boost: Boolean,
    library: ColorLibrary,
    flashAvailable: Boolean,
    onSourceChange: (LightSource) -> Unit,
    onColorChange: (Int) -> Unit,
    onBoostChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    // null = closed; a slot with id 0 = new color.
    var editingSlot by remember { mutableStateOf<ColorSlot?>(null) }
    val slots = library.slots
    val known = color == ScreenColors.WHITE || slots.any { it.color == color }

    Column {
        Text("Light", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = source == LightSource.FLASH,
                onClick = { onSourceChange(LightSource.FLASH) },
                enabled = flashAvailable,
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Flash") }
            SegmentedButton(
                selected = source == LightSource.SCREEN,
                onClick = { onSourceChange(LightSource.SCREEN) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Screen") }
        }
        AnimatedVisibility(
            visible = source == LightSource.SCREEN,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                Text("Screen color", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ColorChoice("White", ScreenColors.WHITE, selected = color == ScreenColors.WHITE) {
                        onColorChange(ScreenColors.WHITE)
                    }
                    slots.forEach { slot ->
                        ColorChoice(
                            slot.name,
                            slot.color,
                            selected = color == slot.color,
                            onLongClick = { editingSlot = slot },
                        ) { onColorChange(slot.color) }
                    }
                    if (!known) {
                        ColorChoice("Unsaved", color, selected = true) {}
                    }
                    if (library.canAdd) {
                        ColorChoice("Add", null, selected = false) {
                            editingSlot = ColorSlot(0, library.nextName(), color)
                        }
                    }
                }
                Text(
                    if (slots.isEmpty()) "Tap Add to save up to 10 colors of your own."
                    else "Long-press a color to rename, change or delete it (${slots.size} of ${ColorSlot.MAX}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Peak brightness", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "At the end of the fade, push past the usual maximum toward the " +
                                "screen's peak (HDR). The phone limits how long it can hold this.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = boost, onCheckedChange = onBoostChange)
                }
                Text(
                    "Fades from black to this color until the wake-up time. Pick the phone up " +
                        "(or tap the light) and it fades away in a moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            enabled = source == LightSource.SCREEN || flashAvailable,
            onClick = {
                SunriseService.start(
                    context,
                    wakeAt = System.currentTimeMillis() + 30_000L,
                    holdMs = 15_000L,
                    source = source,
                    color = color,
                    boost = boost,
                    alarmScreen = source == LightSource.SCREEN,
                    pickupArmDelayMs = 4_000L,
                )
            },
        ) { Text("Test this light (30 s)") }
    }

    editingSlot?.let { slot ->
        val isNew = slot.id == 0
        ColorEditorDialog(
            initial = if (isNew) null else slot,
            defaultName = slot.name,
            onDismiss = { editingSlot = null },
            onSave = { name, picked ->
                if (isNew) {
                    library.add(name, picked)
                    onColorChange(picked)
                } else {
                    val wasSelected = color == slot.color
                    library.update(slot.copy(name = name, color = picked))
                    if (wasSelected) onColorChange(picked)
                }
                editingSlot = null
            },
            onDelete = if (isNew) null else {
                {
                    library.delete(slot.id)
                    editingSlot = null
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorChoice(
    label: String,
    color: Int?,
    selected: Boolean,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val ring by animateDpAsState(
        if (selected) 3.dp else 1.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "ring",
    )
    val inset by animateDpAsState(
        if (selected) 5.dp else 2.dp,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "inset",
    )
    val scale by animateFloatAsState(
        if (selected) 1.08f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "scale",
    )
    val ringColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "ring color",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(56.dp)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .border(width = ring, color = ringColor, shape = CircleShape)
                .padding(inset)
                .clip(CircleShape)
                .background(
                    if (color != null) Color(color)
                    else MaterialTheme.colorScheme.secondaryContainer,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (color == null) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ColorDot(color: Int, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .background(Color(color)),
    )
}

@Composable
private fun DayPicker(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        DayOfWeek.values().forEach { day ->
            val selected = day.value in days
            val background by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "day",
            )
            val text by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "day text",
            )
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(background)
                    .clickable { onChange(if (selected) days - day.value else days + day.value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    color = text,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun colorName(color: Int, slots: List<ColorSlot>): String = when {
    color == ScreenColors.WHITE -> "White"
    else -> slots.firstOrNull { it.color == color }?.name ?: "custom color"
}

private fun describeDays(days: Set<Int>): String = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Every day"
    days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    days == setOf(6, 7) -> "Weekends"
    else -> days.sorted().joinToString(", ") {
        DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }
}

private fun formatTime(context: Context, millis: Long): String =
    DateFormat.getTimeFormat(context).format(Date(millis))

private fun formatDayTime(context: Context, millis: Long): String {
    val day = DateFormat.format("EEE", millis)
    return "$day ${formatTime(context, millis)}"
}

private fun formatHourMinute(context: Context, hour: Int, minute: Int): String {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
    }
    return formatTime(context, cal.timeInMillis)
}
