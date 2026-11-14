package com.lifelock.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GoalEditor(onClose: () -> Unit) {
    val c = LocalContext.current
    var title by remember { mutableStateOf("") }
    var end by remember { mutableStateOf(LocalDate.now().plusMonths(1).withDayOfMonth(1)) }
    var apps by remember { mutableStateOf(setOf<String>()) }
    var picker by remember { mutableStateOf(false) }
    EditorPage("New goal", onClose) {
        OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true,
            label = { Text("Goal, e.g. No food delivery") }, modifier = Modifier.fillMaxWidth())
        Surf(onClick = {
            DatePickerDialog(c, { _, y, m, d -> end = LocalDate.of(y, m + 1, d) }, end.year, end.monthValue - 1, end.dayOfMonth)
                .apply { datePicker.minDate = System.currentTimeMillis() + 86400000L }.show()
        }) {
            Sub("Keep going until")
            Text(end.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pal.ink)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1 week" to 7L, "2 weeks" to 14L, "1 month" to 30L, "3 months" to 90L).forEach { (l, d) ->
                Pill(l, false) { end = LocalDate.now().plusDays(d) }
            }
        }
        Surf {
            Title("Block apps until then")
            Sub("Optional. These apps stay locked until the goal ends. No passes.")
            if (apps.isNotEmpty()) { Spacer(Modifier.height(10.dp)); AppsRow(apps.toList()) }
            Btn(if (apps.isEmpty()) "Choose apps" else "Change apps (${apps.size})", Modifier.padding(top = 12.dp), filled = false) { picker = true }
        }
        Btn("Start goal") {
            if (title.isBlank()) toast(c, "Give your goal a name") else { Store.addGoal(c, title.trim(), end.toEpochDay(), apps.toList()); onClose() }
        }
    }
    if (picker) AppPicker("Block until goal ends", true, apps, { apps = it; picker = false }, { picker = false })
}

@Composable
fun LimitEditor(onClose: () -> Unit) {
    val c = LocalContext.current
    var pkg by remember { mutableStateOf<String?>(null) }
    var mins by remember { mutableIntStateOf(30) }
    var picker by remember { mutableStateOf(false) }
    EditorPage("Limit an app", onClose) {
        Surf(onClick = { picker = true }) {
            val p = pkg
            if (p == null) {
                Title("Choose app")
                Sub("Tap to search your installed apps")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(p, 48.dp); Spacer(Modifier.width(14.dp)); Title(AppRepo.label(p))
                }
            }
        }
        Surf {
            Title("Daily time allowed")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60, 90, 120).forEach { Pill("$it min", mins == it) { mins = it } }
            }
            Stepper("Minutes per day", mins, 5, 600, " min", 5) { mins = it }
            Sub("Once used up, the app is locked until tomorrow. Limits can be lowered but not raised, and can't be removed for 7 days.")
        }
        Btn("Save limit", enabled = pkg != null) {
            val p = pkg
            if (p != null) { if (Store.addLimit(c, p, mins)) onClose() else toast(c, "An existing limit can only be lowered.") }
        }
    }
    if (picker) AppPicker("Choose app", false, emptySet(), { pkg = it.firstOrNull(); picker = false }, { picker = false })
}

@Composable
fun FocusEditor(onClose: () -> Unit) {
    val c = LocalContext.current
    var mins by remember { mutableIntStateOf(60) }
    var apps by remember { mutableStateOf(Store.focusApps(c)) }
    var picker by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    EditorPage("Focus session", onClose) {
        Surf {
            Title("How long?")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 45, 60, 90, 120).forEach { Pill("$it min", mins == it) { mins = it } }
            }
            Stepper("Minutes", mins, 5, 480, " min", 5) { mins = it }
        }
        Surf {
            Title("Allowed apps")
            Sub("Everything else is locked. Calls, SMS, the home screen and keyboards always work.")
            Spacer(Modifier.height(10.dp))
            AppsRow(apps.toList())
            Btn("Choose allowed apps", Modifier.padding(top = 12.dp), filled = false) { picker = true }
        }
        Btn("Lock me in for $mins min") { confirm = true }
    }
    if (picker) AppPicker("Allowed during focus", true, apps, { apps = it; Store.setFocusApps(c, it); picker = false }, { picker = false })
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = Pal.card,
        title = { Text("Lock for $mins minutes?", color = Pal.ink) },
        text = { Text("You will not be able to cancel this.", color = Pal.muted) },
        confirmButton = { TextButton(onClick = { Store.startFocus(c, mins); onClose() }) { Text("Lock me in") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Not now") } }
    )
}

@Composable
fun SchedEditor(id: String?, onClose: () -> Unit) {
    val c = LocalContext.current
    val ex = remember { Store.scheds(c).firstOrNull { it.id == id } }
    var name by remember { mutableStateOf(ex?.name ?: "") }
    var days by remember { mutableIntStateOf(ex?.days ?: 31) }
    var start by remember { mutableIntStateOf(ex?.start ?: 540) }
    var end by remember { mutableIntStateOf(ex?.end ?: 1020) }
    var allowOnly by remember { mutableStateOf(ex?.allowOnly ?: true) }
    var entries by remember { mutableStateOf(ex?.entries ?: emptyList()) }
    var picker by remember { mutableStateOf(false) }
    var capFor by remember { mutableStateOf<String?>(null) }
    var capVal by remember { mutableIntStateOf(0) }
    EditorPage(if (ex == null) "New schedule" else "Edit schedule", onClose) {
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Name (Study, Work, School…)") }, modifier = Modifier.fillMaxWidth())
        Surf {
            Title("When")
            Spacer(Modifier.height(10.dp))
            DayChips(days) { days = it }
            Spacer(Modifier.height(12.dp))
            Row {
                TimeBtn("From", start, Modifier.weight(1f)) { start = it }
                Spacer(Modifier.width(12.dp))
                TimeBtn("Until", end, Modifier.weight(1f)) { end = it }
            }
            Sub("Can cross midnight, e.g. 23:00 → 07:00.", Modifier.padding(top = 8.dp))
        }
        Surf {
            Title("What happens")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Only allow these apps", allowOnly) { allowOnly = true }
                Pill("Block these apps", !allowOnly) { allowOnly = false }
            }
            Sub(if (allowOnly) "Listed apps work (optionally with a daily time cap). Everything else is locked, except calls, SMS and the home screen."
                else "Listed apps are locked. Give one a time cap to allow a limited amount instead.",
                Modifier.padding(top = 8.dp))
        }
        Title("Apps (${entries.size})")
        entries.forEach { e ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pal.card)
                .clickable { capVal = e.cap; capFor = e.pkg }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(e.pkg, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(AppRepo.label(e.pkg), color = Pal.ink, fontWeight = FontWeight.SemiBold)
                    Sub(if (e.cap > 0) "${e.cap} min per day · tap to change" else if (allowOnly) "No time cap · tap to set" else "Blocked completely · tap to allow some time")
                }
                IconButton(onClick = { entries = entries - e }) { Icon(Icons.Default.Close, null, tint = Pal.muted) }
            }
        }
        Btn("Add apps", filled = false) { picker = true }
        if (ex != null && Store.schedLocked(ex)) {
            Sub("This schedule is active right now, so it can't be changed until it ends.")
        } else {
            Btn("Save schedule") {
                if (days == 0) toast(c, "Pick at least one day")
                else if (start == end) toast(c, "Start and end can't be the same")
                else {
                    Store.saveSched(c, Sched(ex?.id ?: System.currentTimeMillis().toString(), name.ifBlank { "Schedule" },
                        days, start, end, allowOnly, entries, ex?.created ?: System.currentTimeMillis()))
                    onClose()
                }
            }
            if (ex != null) Btn("Delete schedule", filled = false) { Store.deleteSched(c, ex.id); onClose() }
        }
    }
    if (picker) AppPicker("Choose apps", true, entries.map { it.pkg }.toSet(), { sel ->
        entries = sel.map { p -> entries.firstOrNull { it.pkg == p } ?: Entry(p, 0) }
        picker = false
    }, { picker = false })
    val cf = capFor
    if (cf != null) AlertDialog(
        onDismissRequest = { capFor = null }, containerColor = Pal.card,
        title = { Text(AppRepo.label(cf), color = Pal.ink) },
        text = {
            Column {
                Sub("Daily time allowed inside this schedule. None = no cap (blocked completely in 'Block' mode).")
                Stepper("Minutes", capVal, 0, 480, " min", 5) { capVal = it }
            }
        },
        confirmButton = { TextButton(onClick = { entries = entries.map { if (it.pkg == cf) it.copy(cap = capVal) else it }; capFor = null }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { capFor = null }) { Text("Cancel") } }
    )
}

@Composable
fun QuietEditor(id: String?, onClose: () -> Unit) {
    val c = LocalContext.current
    val ex = remember { Store.quiets(c).firstOrNull { it.id == id } }
    var name by remember { mutableStateOf(ex?.name ?: "School") }
    var days by remember { mutableIntStateOf(ex?.days ?: 31) }
    var start by remember { mutableIntStateOf(ex?.start ?: 480) }
    var end by remember { mutableIntStateOf(ex?.end ?: 900) }
    EditorPage(if (ex == null) "New quiet time" else "Edit quiet time", onClose) {
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Name (School, Work…)") }, modifier = Modifier.fillMaxWidth())
        Surf {
            Sub("No reminders on these days between these times.")
            Spacer(Modifier.height(12.dp))
            DayChips(days) { days = it }
            Spacer(Modifier.height(12.dp))
            Row {
                TimeBtn("From", start, Modifier.weight(1f)) { start = it }
                Spacer(Modifier.width(12.dp))
                TimeBtn("Until", end, Modifier.weight(1f)) { end = it }
            }
        }
        Btn("Save") {
            if (days == 0) toast(c, "Pick at least one day") else {
                Store.saveQuiet(c, Quiet(ex?.id ?: System.currentTimeMillis().toString(), name.ifBlank { "Quiet time" }, days, start, end))
                ReminderReceiver.schedule(c); onClose()
            }
        }
        if (ex != null) Btn("Delete", filled = false) { Store.deleteQuiet(c, ex.id); ReminderReceiver.schedule(c); onClose() }
    }
}
