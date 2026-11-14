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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp

object Perm {
    fun guard(c: Context) = (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
        .contains("${c.packageName}/")
    fun overlay(c: Context) = Settings.canDrawOverlays(c)
    fun notif(c: Context) = Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun open(c: Context, i: Intent) = c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun SetupRow(ok: Boolean, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Pal.green else Pal.orange))
        Spacer(Modifier.width(12.dp))
        Text(title, color = Color.White, modifier = Modifier.weight(1f))
        if (!ok) Text("Enable", color = Pal.accent, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Pal.accent.copy(alpha = 0.16f)).clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp))
        else Text("On", color = Pal.green, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SetupCard(now: Long) {
    val c = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val g = Perm.guard(c)
    val o = Perm.overlay(c)
    val n = Perm.notif(c)
    if (g && o && n) return
    Surf {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip("⚠️", Pal.orange)
            Spacer(Modifier.width(14.dp))
            Column {
                Title("Finish setup")
                Sub("Blocking only works once these are on.")
            }
        }
        SetupRow(g, "Blocking engine (Accessibility)") { Perm.open(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        SetupRow(o, "Show block screen over apps") {
            Perm.open(c, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")))
        }
        SetupRow(n, "Notifications") { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
        Sub("Accessibility greyed out? App info → ⋮ menu → Allow restricted settings.", Modifier.padding(top = 14.dp))
        Btn("Open app info (set battery to Unrestricted)", Modifier.padding(top = 12.dp), filled = false) {
            Perm.open(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")))
        }
    }
}

// ---------------------------------------------------------------- TODAY
@Composable
fun TodayScreen(ver: Int, now: Long, go: (Page) -> Unit, bump: () -> Unit) {
    val c = LocalContext.current
    val t = Store.today()
    val hr = LocalDateTime.now().hour
    val greet = if (hr < 5) "Still up?" else if (hr < 12) "Good morning" else if (hr < 18) "Good afternoon" else "Good evening"
    val goals = Store.goals(c)
    val live = goals.filter { t < it.end }
    val due = Store.habits(c).filter { Store.hActive(it, t) }
    val done = due.count { Store.met(it, Store.hLog(c, it.id, t)) }
    val top = goals.maxOfOrNull { Store.liveStreak(it) } ?: 0
    val left = Store.focusUntil(c) - now
    val active = Store.scheds(c).filter { Store.schedActive(it) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")).uppercase(), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = Pal.accent)
                Text(greet, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
        }
        item { SetupCard(now) }
        item {
            Surf(brush = Brush.linearGradient(listOf(Color(0xFF6D4DF6), Color(0xFF3F7CF5)))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(if (due.isEmpty()) 0f else done.toFloat() / due.size, 116.dp, 12.dp, Color.White, Color.White.copy(alpha = 0.22f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (due.isEmpty()) "–" else "$done/${due.size}", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text("habits today", fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
                        }
                    }
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Text("🔥 $top", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text("day best streak", fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
                        Spacer(Modifier.height(12.dp))
                        Text("${live.size} active goal${if (live.size == 1) "" else "s"}", fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
                        Text("${Store.passesLeft(c)} passes left this week", fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
                    }
                }
            }
        }
        items(active, key = { it.id }) { s ->
            Surf {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Pal.green))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Title("${s.name} is active")
                        Sub(if (s.allowOnly) "Only your allowed apps work until ${hhmm(s.end)}." else "Blocked apps are locked until ${hhmm(s.end)}.")
                    }
                }
            }
        }
        item {
            Surf {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip("🎧", Pal.accent)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Title("Focus session")
                        if (left > 0) {
                            val s = left / 1000
                            Text("%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Pal.accent)
                        } else Sub("Lock everything except what you allow")
                    }
                    if (left <= 0) Pill("Start", true) { go(Page.FocusSetup) }
                }
                if (left > 0) Sub("Locked in. There is no cancel button.", Modifier.padding(top = 8.dp))
            }
        }
        if (due.isNotEmpty()) {
            item { SectionLabel("Habits today") }
            item {
                Surf {
                    due.forEachIndexed { i, h ->
                        if (i > 0) Divider1()
                        HabitRow(h, t, ver, bump)
                    }
                }
            }
        }
        item { SectionLabel("Goals") }
        if (goals.isEmpty()) item {
            EmptyState("🎯", "No goals yet", "Try “No food delivery until next month” and block the apps that tempt you.")
        }
        items(goals, key = { it.id }) { g -> GoalCard(g, t, bump) }
    }
}

@Composable
fun HabitRow(h: Habit, t: Long, ver: Int, bump: () -> Unit) {
    val c = LocalContext.current
    val v = Store.hLog(c, h.id, t)
    val met = Store.met(h, v)
    val frac = (v.toFloat() / h.target.coerceAtLeast(1)).coerceIn(0f, 1f)
    val col = if (h.kind == 2 && v > h.target) Pal.red else if (met) Pal.green else Pal.accent
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconChip(h.emoji, col, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(h.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (h.kind == 0) Sub(if (met) "Done" else "To do")
            else {
                Sub("$v / ${h.target} ${h.unit}")
                Spacer(Modifier.height(6.dp))
                Bar(frac, col, 5.dp)
            }
        }
        Spacer(Modifier.width(14.dp))
        if (h.kind == 0) CircleCheck(met) { Store.hSet(c, h.id, if (met) 0 else 1); bump() }
        else RoundAction("+${h.step}") { Store.hAdd(c, h.id, h.step); bump() }
    }
}

@Composable
fun GoalCard(g: Goal, t: Long, bump: () -> Unit) {
    val c = LocalContext.current
    val active = t < g.end
    val cd = Instant.ofEpochMilli(g.created).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    val p = ((t - cd).toFloat() / (g.end - cd).coerceAtLeast(1)).coerceIn(0f, 1f)
    Surf {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip("🎯", if (active) Pal.accent else Pal.green)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Title(g.title)
                Sub(if (active) "${g.end - t} days left · until ${LocalDate.ofEpochDay(g.end)}" else "Completed 🎉")
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("🔥 ${Store.liveStreak(g)}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Sub("best ${g.best}")
            }
        }
        Spacer(Modifier.height(14.dp))
        Bar(if (active) p else 1f, if (active) Pal.accent else Pal.green)
        if (g.apps.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { AppsRow(g.apps); Sub("  blocked", color = Pal.muted) }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                val done = g.last == t
                Box(Modifier.weight(1f)) {
                    Btn(if (done) "Checked in today ✓" else "Check in today", color = if (done) Pal.green else Pal.accent, enabled = !done) {
                        Store.checkIn(c, g.id); bump()
                    }
                }
            } else Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                if (Store.canDelete(g)) { Store.deleteGoal(c, g.id); bump() }
                else toast(c, "Locked until ${LocalDate.ofEpochDay(g.end)}. Goals can only be deleted in the first 10 minutes.")
            }) { Icon(Icons.Default.Delete, null, tint = Pal.muted) }
        }
    }
}

// ---------------------------------------------------------------- LIMITS
@Composable
fun LimitsScreen(ver: Int, now: Long, go: (Page) -> Unit, bump: () -> Unit) {
    val c = LocalContext.current
    val limits = Store.limits(c)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Limits", "A daily time budget per app. When it's gone, the app is locked until tomorrow.") }
        if (limits.isEmpty()) item { EmptyState("⏳", "No limits yet", "Give Instagram, YouTube or any app a daily budget.") }
        items(limits, key = { it.pkg }) { l -> LimitCard(l, now, bump) }
    }
}

@Composable
fun LimitCard(l: Limit, now: Long, bump: () -> Unit) {
    val c = LocalContext.current
    val key = Store.gKey(l.pkg)
    val used = Store.used(c, key) / 60000
    val cap = l.min + Store.extra(c, key)
    val frac = (used.toFloat() / cap).coerceIn(0f, 1f)
    val col = if (frac >= 1f) Pal.red else if (frac > 0.7f) Pal.orange else Pal.accent
    Surf {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(l.pkg, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Title(AppRepo.label(l.pkg))
                Sub(if (frac >= 1f) "Locked until tomorrow" else "${cap - used} min left today", color = if (frac >= 1f) Pal.red else Pal.muted)
            }
            IconButton(onClick = {
                if (Store.removeLimit(c, l.pkg)) bump() else toast(c, "Limits are locked for 7 days after you create them.")
            }) { Icon(Icons.Default.Delete, null, tint = Pal.muted) }
        }
        Spacer(Modifier.height(14.dp))
        Bar(frac, col)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("$used / $cap min", col)
            Chip("🔥 ${l.streak} days under", Pal.green)
        }
    }
}

// ---------------------------------------------------------------- SCHEDULES
@Composable
fun SchedulesScreen(ver: Int, now: Long, go: (Page) -> Unit, bump: () -> Unit) {
    val c = LocalContext.current
    val list = Store.scheds(c)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Schedules", "Weekly routines. Allow only certain apps, or block some, with optional daily caps.") }
        if (list.isEmpty()) item {
            EmptyState("🗓️", "No schedules yet", "Example: Mon–Fri 9–5, only Docs and WhatsApp, YouTube capped at 20 min.")
        }
        items(list, key = { it.id }) { s ->
            val active = Store.schedActive(s)
            val tint = if (s.allowOnly) Pal.accent2 else Pal.orange
            Surf(onClick = {
                if (Store.schedLocked(s)) toast(c, "${s.name} is active right now, so it's locked until it ends.")
                else go(Page.SchedEdit(s.id))
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(if (s.allowOnly) "🛡️" else "⛔", tint)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Title(s.name); Sub(daysLabel(s.days)) }
                    if (active) Chip("● ACTIVE", Pal.green)
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hhmm(s.start), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("  →  ", fontSize = 18.sp, color = Pal.muted)
                    Text(hhmm(s.end), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
                Spacer(Modifier.height(12.dp))
                DayDots(s.days, tint)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(if (s.allowOnly) "ONLY ${s.entries.size} APPS ALLOWED" else "${s.entries.size} APPS BLOCKED", tint)
                    Spacer(Modifier.width(12.dp))
                    if (s.entries.isNotEmpty()) AppsRow(s.entries.map { it.pkg })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- SETTINGS
@Composable
fun StatusLine(ok: Boolean, text: String) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Pal.green else Pal.red))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White, modifier = Modifier.weight(1f))
        Text(if (ok) "On" else "Off", color = if (ok) Pal.green else Pal.red, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SettingsScreen(ver: Int, now: Long, go: (Page) -> Unit, bump: () -> Unit) {
    val c = LocalContext.current
    val rem = Store.rem(c)
    val quiets = Store.quiets(c)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Settings") }
        item {
            Surf {
                Title("Protection status")
                StatusLine(Perm.guard(c), "Blocking engine")
                StatusLine(Perm.overlay(c), "Block screen over apps")
                StatusLine(Perm.notif(c), "Notifications")
            }
        }
        item { SectionLabel("Passes") }
        item {
            Surf {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip("🎟️", Pal.orange)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Title("${Store.passesLeft(c)} passes left this week")
                        Sub("Extra minutes on an app that hit its limit")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Stepper("Passes per week", Store.passN(c), 0, 21) { Store.setInt(c, "pn", it); bump() }
                Stepper("Minutes per pass", Store.passMin(c), 1, 120, " min", 5) { Store.setInt(c, "pm", it); bump() }
            }
        }
        item { SectionLabel("Goal reminders") }
        item {
            Surf {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip("🔔", Pal.accent)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Title("Check-in reminders"); Sub("Nudges while goals are pending") }
                    Switch(checked = rem.on, onCheckedChange = { Store.saveRem(c, rem.copy(on = it)); ReminderReceiver.schedule(c); bump() })
                }
                if (rem.on) {
                    Spacer(Modifier.height(16.dp))
                    Row {
                        TimeBtn("From", rem.from, Modifier.weight(1f)) { Store.saveRem(c, rem.copy(from = it)); ReminderReceiver.schedule(c); bump() }
                        Spacer(Modifier.width(12.dp))
                        TimeBtn("Until", rem.to, Modifier.weight(1f)) { Store.saveRem(c, rem.copy(to = it)); ReminderReceiver.schedule(c); bump() }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "Every 15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 180 to "3 hours").forEach { (m, l) ->
                            Pill(l, rem.every == m) { Store.saveRem(c, rem.copy(every = m)); ReminderReceiver.schedule(c); bump() }
                        }
                    }
                    Btn("Send a test notification", Modifier.padding(top = 14.dp), filled = false) { Reminders.send(c, true) }
                }
            }
        }
        if (rem.on || Store.habits(c).any { it.remOn }) {
            item { SectionLabel("Quiet times") }
            item { Sub("No goal or habit reminders on these days and times, e.g. school or work.", Modifier.padding(horizontal = 4.dp)) }
            items(quiets, key = { it.id }) { q ->
                Surf(onClick = { go(Page.QuietEdit(q.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconChip("🤫", Pal.accent2)
                        Spacer(Modifier.width(14.dp))
                        Column { Title(q.name); Sub("${daysLabel(q.days)} · ${hhmm(q.start)}–${hhmm(q.end)}") }
                    }
                }
            }
            item { Btn("+ Add quiet time", filled = false) { go(Page.QuietEdit(null)) } }
        }
        item { SectionLabel("Motivation") }
        item {
            var why by remember { mutableStateOf(Store.why(c)) }
            Surf {
                Title("Your reason")
                Sub("Shown on the block screen when you try to open a blocked app.")
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = why, onValueChange = { why = it; Store.setWhy(c, it) },
                    placeholder = { Text("Why are you doing this?") }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
