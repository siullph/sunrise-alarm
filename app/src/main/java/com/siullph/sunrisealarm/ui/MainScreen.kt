package com.siullph.sunrisealarm.ui

import android.Manifest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.siullph.sunrisealarm.AlarmStore
import com.siullph.sunrisealarm.Scheduler
import com.siullph.sunrisealarm.SunriseAlarm
import com.siullph.sunrisealarm.SunriseService
import com.siullph.sunrisealarm.Torch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val MIN_FADE = 5
private const val MAX_FADE = 60
private const val FADE_STEP = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(refresh: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AlarmStore(context) }
    val torch = remember { Torch(context) }
    val alarms = remember(refresh) { store.alarms() }
    val phoneAlarm = remember(refresh) { Scheduler.nextPhoneAlarm(context) }
    val canExact = remember(refresh) { Scheduler.canScheduleExact(context) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sunrise Alarm") },
                actions = {
                    TextButton(
                        enabled = torch.available,
                        onClick = {
                            SunriseService.start(
                                context,
                                wakeAt = System.currentTimeMillis() + 30_000L,
                                holdMs = 10_000L,
                            )
                        },
                    ) { Text("Test") }
                    TextButton(onClick = { SunriseService.stop(context) }) { Text("Stop") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = SunriseAlarm(id = 0, hour = 7, minute = 0, fadeMinutes = 30)
            }) {
                Icon(Icons.Filled.Add, contentDescription = "New sunrise alarm")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!torch.available) {
                item { Notice("This phone has no flashlight the app can control, so sunrises can't run.") }
            } else if (!torch.dimmable) {
                item {
                    Notice(
                        "This phone's flashlight only switches on or off, so it turns on at the " +
                            "start of the fade instead of brightening gradually.",
                    )
                }
            }
            if (!notificationsGranted) {
                item {
                    Notice(
                        "Allow notifications so a running sunrise shows a Stop button.",
                        action = "Allow",
                    ) { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
            }
            if (!canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    Notice(
                        "Allow exact alarms, or sunrises may start a few minutes late.",
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

            item { SectionTitle("Phone alarm") }
            item {
                LinkedAlarmCard(
                    context = context,
                    store = store,
                    phoneAlarmAt = phoneAlarm?.wakeAt,
                    appLabel = phoneAlarm?.appLabel,
                    refresh = refresh,
                    onChanged = onChanged,
                )
            }

            item { SectionTitle("Sunrise-only alarms") }
            if (alarms.isEmpty()) {
                item {
                    Text(
                        "None yet. Tap + to add a sunrise that runs on its own, without a phone alarm.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(alarms, key = { it.id }) { alarm ->
                AlarmRow(
                    context = context,
                    alarm = alarm,
                    onToggle = { enabled ->
                        store.save(alarm.copy(enabled = enabled))
                        onChanged()
                    },
                    onClick = { editing = alarm },
                )
            }
        }
    }

    editing?.let { alarm ->
        AlarmEditor(
            initial = alarm,
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
                    editing = null
                    onChanged()
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun LinkedAlarmCard(
    context: Context,
    store: AlarmStore,
    phoneAlarmAt: Long?,
    appLabel: String?,
    refresh: Int,
    onChanged: () -> Unit,
) {
    var enabled by remember(refresh) { mutableStateOf(store.linkedEnabled) }
    var fade by remember(refresh) { mutableIntStateOf(store.linkedFadeMinutes) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
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
                    style = MaterialTheme.typography.headlineMedium,
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
            FadeSlider(
                minutes = fade,
                onChange = { fade = it },
                onChangeFinished = {
                    store.linkedFadeMinutes = fade
                    onChanged()
                },
            )
            if (enabled && phoneAlarmAt != null) {
                Text(
                    "The light starts at ${formatTime(context, phoneAlarmAt - fade * 60_000L)}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Android only shares your next alarm, so this follows whichever phone alarm comes next.",
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
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatHourMinute(context, alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${alarm.fadeMinutes} min fade · ${describeDays(alarm.days)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmEditor(
    initial: SunriseAlarm,
    onDismiss: () -> Unit,
    onSave: (SunriseAlarm) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val timeState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    var fade by remember { mutableIntStateOf(initial.fadeMinutes) }
    var days by remember { mutableStateOf(initial.days) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0) "New sunrise" else "Edit sunrise") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Wake-up time (full brightness)", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                TimePicker(state = timeState)
                FadeSlider(minutes = fade, onChange = { fade = it })
                Text("Repeat", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                DayPicker(days = days, onChange = { days = it })
                Spacer(Modifier.height(4.dp))
                Text(
                    if (days.isEmpty()) "No days picked: runs once." else describeDays(days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete, contentPadding = PaddingValues(0.dp)) {
                        Text("Delete alarm", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        hour = timeState.hour,
                        minute = timeState.minute,
                        fadeMinutes = fade,
                        days = days,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DayPicker(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.values().forEach { day ->
            val selected = day.value in days
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onChange(if (selected) days - day.value else days + day.value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
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
