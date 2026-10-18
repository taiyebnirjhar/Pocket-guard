package com.taiyeb.pocketguard

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** The days in the order this region's week runs (Bangladesh: from Saturday). */
private fun weekDays(): List<DayOfWeek> {
    val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return (0L until 7L).map { first.plus(it) }
}

private fun dayName(day: DayOfWeek) = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())

private fun timeText(context: Context, time: LocalTime): String =
    time.format(DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"))

private fun timeText(context: Context, at: Long): String =
    timeText(context, Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime())

@Composable
private fun daysText(days: Set<DayOfWeek>): String =
    if (days.size == 7) stringResource(R.string.schedule_every_day) else weekDays().filter { it in days }.joinToString(", ") { dayName(it) }

@Composable
private fun windowText(context: Context, w: ArmSchedule.Window): String {
    val end = timeText(context, w.end) + if (w.overnight) " (" + stringResource(R.string.schedule_next_day) + ")" else ""
    return daysText(w.days) + " · " + timeText(context, w.start) + "–" + end
}

/** Triggers: arm on a schedule, with the times it covers. */
@Composable
fun ScheduleCard(refreshKey: Int) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(Prefs.scheduleOn(context)) }
    var windows by remember { mutableStateOf(ArmScheduler.windows(context)) }
    // Index of the window being edited; -1 for a new one.
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val exact = remember(refreshKey) { ArmScheduler.exact(context) }
    val save = { list: List<ArmSchedule.Window> ->
        Prefs.setScheduleJson(context, ArmSchedule.toJson(list))
        windows = list
        ArmScheduler.apply(context, quiet = true)
    }

    ToggleRow(R.drawable.ic_timer, stringResource(R.string.triggers_schedule), stringResource(R.string.triggers_schedule_sub), on) {
        on = it
        Prefs.setScheduleOn(context, it)
        if (it && windows.isEmpty()) editing = -1
        ArmScheduler.apply(context, quiet = true)
    }
    if (!on) return

    windows.forEachIndexed { i, w ->
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { editing = i },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(windowText(context, w), color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
            val remove = stringResource(R.string.schedule_remove)
            TextButton(
                onClick = { save(windows.filterIndexed { j, _ -> j != i }) },
                modifier = Modifier.semantics { contentDescription = remove },
            ) { Text(stringResource(R.string.contacts_remove), color = Lavender) }
        }
    }
    if (windows.size < ArmSchedule.MAX_WINDOWS) {
        TextButton(onClick = { editing = -1 }) {
            Text(stringResource(R.string.schedule_add), color = Lavender, fontWeight = FontWeight.Bold)
        }
    }
    if (!exact && !BuildConfig.PLAY_STORE && Build.VERSION.SDK_INT >= 31) {
        PermissionWarning(stringResource(R.string.schedule_exact)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    Text(stringResource(R.string.schedule_note), color = Muted, fontSize = 12.sp)

    editing?.let { index ->
        WindowDialog(
            initial = windows.getOrNull(index)
                ?: ArmSchedule.Window(DayOfWeek.values().toSet(), LocalTime.of(9, 0), LocalTime.of(18, 0)),
            onSave = { w ->
                save(if (index < 0) windows + w else windows.mapIndexed { j, old -> if (j == index) w else old })
                editing = null
            },
            onDismiss = {
                editing = null
                // Switched on and then no time added: back off, rather than "on" with nothing set.
                if (windows.isEmpty()) {
                    on = false
                    Prefs.setScheduleOn(context, false)
                    ArmScheduler.apply(context, quiet = true)
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WindowDialog(initial: ArmSchedule.Window, onSave: (ArmSchedule.Window) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var days by remember { mutableStateOf(initial.days) }
    var start by remember { mutableStateOf(initial.start) }
    var end by remember { mutableStateOf(initial.end) }
    val pick = { time: LocalTime, set: (LocalTime) -> Unit ->
        TimePickerDialog(
            context,
            { _, hour, minute -> set(LocalTime.of(hour, minute)) },
            time.hour,
            time.minute,
            android.text.format.DateFormat.is24HourFormat(context),
        ).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    weekDays().forEach { day ->
                        val selected = day in days
                        Text(
                            dayName(day),
                            color = if (selected) Color.White else Muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(CircleShape)
                                .background(if (selected) Purple else Track)
                                .toggleable(value = selected, role = Role.Checkbox) { days = if (it) days + day else days - day }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                TimeRow(stringResource(R.string.schedule_start), timeText(context, start)) { pick(start) { start = it } }
                TimeRow(
                    stringResource(R.string.schedule_end),
                    timeText(context, end) + if (!end.isAfter(start)) " (" + stringResource(R.string.schedule_next_day) + ")" else "",
                ) { pick(end) { end = it } }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ArmSchedule.Window(days, start, end)) }, enabled = days.isNotEmpty()) {
                Text(stringResource(R.string.contacts_save), color = if (days.isNotEmpty()) Lavender else Muted, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Lavender) }
        },
        containerColor = CardColor,
        titleContentColor = TextPrimary,
        textContentColor = Muted,
        shape = RoundedCornerShape(28.dp),
    )
}

@Composable
private fun TimeRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Home: what the schedule is doing now, with the pause controls. Nothing when it's off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScheduleCard(refreshKey: Int) {
    val context = LocalContext.current
    val version by GuardState.scheduleVersion.collectAsState()
    val phase by GuardState.phase.collectAsState()
    val status = remember(refreshKey, version, phase) { ArmScheduler.status(context) } ?: return
    val paused = status.pausedUntil
    val text = when {
        paused != null && status.skipped -> stringResource(R.string.home_schedule_skipped, timeText(context, paused))
        paused != null -> stringResource(R.string.home_schedule_paused, timeText(context, paused))
        status.notArmed -> stringResource(R.string.home_schedule_not_armed)
        status.armedUntil != null -> stringResource(R.string.home_schedule_on, timeText(context, status.armedUntil))
        status.nextStart != null -> stringResource(
            R.string.home_schedule_next,
            dayName(Instant.ofEpochMilli(status.nextStart).atZone(ZoneId.systemDefault()).dayOfWeek) + " " +
                timeText(context, status.nextStart),
        )
        else -> return
    }
    Spacer(Modifier.height(12.dp))
    GlassCard {
        SettingRow(R.drawable.ic_timer, stringResource(R.string.triggers_schedule), text)
        // Never during an alarm: pausing turns the guard off, which only the owner's unlock may do.
        if (!phase.isAlarm && (paused != null || status.armedUntil != null || status.notArmed)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paused != null || status.notArmed) {
                    PillAction(stringResource(R.string.schedule_arm_now), { ArmScheduler.armNow(context) })
                }
                if (paused != null && !status.skipped) {
                    PillAction(stringResource(R.string.schedule_pause_more), { ArmScheduler.pauseMore(context) })
                }
                if (!status.skipped) {
                    PillAction(stringResource(R.string.schedule_skip), { ArmScheduler.skipToday(context) }, color = Muted)
                }
            }
        }
    }
}
