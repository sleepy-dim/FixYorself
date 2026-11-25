package com.lifelock.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.text.font.FontFamily
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        Text(title, color = Pal.ink, modifier = Modifier.weight(1f))
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
                Text(greet, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Pal.ink)
            }
        }
        item { SetupCard(now) }
        if (Store.habits(c).isEmpty() || Store.goals(c).isEmpty() || (Store.limits(c).isEmpty() && Store.scheds(c).isEmpty())) item {
            val steps = listOf(
                Triple("Add a habit, like drinking water", Store.habits(c).isNotEmpty(), Page.HabitEdit(null) as Page),
                Triple("Limit or block a distracting app", Store.limits(c).isNotEmpty() || Store.scheds(c).isNotEmpty(), Page.NewLimit as Page),
                Triple("Set a goal with a deadline", Store.goals(c).isNotEmpty(), Page.NewGoal as Page))
            Surf {
                Title("Get started")
                Sub("Three quick things to try. Tap one.", Modifier.padding(top = 2.dp, bottom = 6.dp))
                steps.forEach { (label, done, target) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { go(target) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircleCheck(done) { go(target) }
                        Spacer(Modifier.width(14.dp))
                        Text(label, color = if (done) Pal.muted else Pal.ink, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (Store.habits(c).isEmpty()) Btn("Add 3 starter habits for me", Modifier.padding(top = 10.dp), filled = false) {
                    val t0 = System.currentTimeMillis()
                    val y = Store.today() - 1
                    listOf(
                        Habit(t0.toString(), "Drink water", "💧", 1, 3000, "ml", 250, 127, true, 480, 1320, 90, t0, 0, 0, y),
                        Habit((t0 + 1).toString(), "Walks", "🚶", 1, 0, "walks", 1, 127, true, 540, 1140, 60, t0, 0, 0, y),
                        Habit((t0 + 2).toString(), "Read 20 minutes", "📖", 0, 1, "", 1, 127, true, 1260, 1320, 1440, t0, 0, 0, y)
                    ).forEach { Store.saveHabit(c, it) }
                    ReminderReceiver.schedule(c)
                    bump()
                }
            }
        }
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
            EmptyState("🎯", "No goals yet", "Try “No food delivery until next month” and block the apps that tempt you.", "Set a goal") { go(Page.NewGoal) }
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
            Text(h.name, color = Pal.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (h.kind == 0) Sub(if (met) "Done" else "To do")
            else {
                Sub(if (h.kind == 1 && h.target == 0) ("$v ${h.unit} today" + (if (Store.hBest(c, h.id, t) > 0) " · best ${Store.hBest(c, h.id, t)}" else "")).trim() else "$v / ${h.target} ${h.unit}")
                if (!(h.kind == 1 && h.target == 0)) { Spacer(Modifier.height(6.dp)); Bar(frac, col, 5.dp) }
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
                Text("🔥 ${Store.liveStreak(g)}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Pal.ink)
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
                if (Store.canDelete(g)) { Store.deleteGoal(c, g.id); ReminderReceiver.schedule(c); bump() }
                else toast(c, "Locked until ${LocalDate.ofEpochDay(g.end)}. Goals can only be deleted in the first 10 minutes.")
            }) { Icon(Icons.Default.Delete, null, tint = Pal.muted) }
        }
    }
}

// ---------------------------------------------------------------- LIMITS
@Composable
fun LimitsScreen(ver: Int, now: Long, sub: Int, onSub: (Int) -> Unit, go: (Page) -> Unit, bump: () -> Unit) {
    val c = LocalContext.current
    val limits = Store.limits(c)
    var range by remember { mutableIntStateOf(0) }
    var sel by remember { mutableIntStateOf(6) }
    val data by produceState<Insights?>(null, sub, now / 60000L) {
        if (sub == 1 && Compat.usageOk(c)) value = withContext(Dispatchers.Default) { UsageCalc.load(c) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ScreenTitle(if (sub == 0) "Limits" else "Insights",
                if (sub == 0) "A daily time budget per app. When it's gone, the app is locked until tomorrow."
                else "Where your time actually goes. Everything is read on your phone.")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Limits", sub == 0) { onSub(0) }
                Pill("Insights", sub == 1) { onSub(1) }
            }
        }
        if (sub == 0 && !Compat.usageOk(c)) item {
            Surf {
                Title("Use Android's own usage numbers")
                Sub("Allow Usage access and LifeLock reads exact app time from Android instead of counting itself. More accurate, and it saves battery.",
                    Modifier.padding(top = 4.dp))
                Btn("Grant usage access", Modifier.padding(top = 12.dp)) { Perm.open(c, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
        }
        if (sub == 0) {
            if (limits.isEmpty()) item { EmptyState("⏳", "No limits yet", "Give Instagram, YouTube or any app a daily budget. Not sure which? Check Insights.", "Add a limit") { go(Page.NewLimit) } }
            items(limits, key = { it.pkg }) { l -> LimitCard(l, now, go, bump) }
        } else insightsItems(c, data, range, { range = it }, sel, { sel = it }, bump)
    }
}

@Composable
fun LimitCard(l: Limit, now: Long, go: (Page) -> Unit, bump: () -> Unit) {
    val c = LocalContext.current
    val ar = Store.activeRule(l)
    val key = if (ar != null) Store.ruleKey(l.pkg, ar.first, ar.second) else Store.gKey(l.pkg)
    val a = ar?.first?.min ?: -1
    val used = Store.used(c, key) / 60000
    val cap = if (a < 0) 0 else a + Store.extra(c, key)
    val frac = if (a < 0) 0f else if (cap == 0) 1f else (used.toFloat() / cap).coerceIn(0f, 1f)
    val col = if (frac >= 1f) Pal.red else if (frac > 0.7f) Pal.orange else Pal.accent
    Surf(onClick = { go(Page.LimitEdit(l.pkg)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(l.pkg, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Title(AppRepo.label(l.pkg))
                Sub(if (a < 0) "No limit right now" else if (frac >= 1f) (if (a == 0) (if (ar != null && ar.first.from != ar.first.to) "Blocked until ${hhmm(ar.first.to)}" else "Blocked today") else "Locked until tomorrow") else "${cap - used} min left today",
                    color = if (a >= 0 && frac >= 1f) Pal.red else Pal.muted)
            }
            IconButton(onClick = {
                if (Store.removeLimit(c, l.pkg)) bump() else toast(c, "Limits are locked for 7 days after you create them.")
            }) { Icon(Icons.Default.Delete, null, tint = Pal.muted) }
        }
        Spacer(Modifier.height(14.dp))
        Bar(frac, col)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a >= 0) Chip("$used / $cap min", col)
            Chip("🔥 ${l.streak} days under", Pal.green)
        }
        Sub(Store.limitSummary(l) + (if (Compat.usageOk(c)) " · time read from Android" else " · counted by LifeLock (allow Usage access for exact time)"),
            Modifier.padding(top = 10.dp))
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
            EmptyState("🗓️", "No schedules yet", "Example: Mon–Fri 9–5, only Docs and WhatsApp, YouTube capped at 20 min.", "Create a schedule") { go(Page.SchedEdit(null)) }
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
                    Text(hhmm(s.start), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Pal.ink)
                    Text("  →  ", fontSize = 18.sp, color = Pal.muted)
                    Text(hhmm(s.end), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Pal.ink)
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
        Text(text, color = Pal.ink, modifier = Modifier.weight(1f))
        Text(if (ok) "On" else "Off", color = if (ok) Pal.green else Pal.red, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SettingsScreen(ver: Int, now: Long, go: (Page) -> Unit, bump: () -> Unit) {
    val c = LocalContext.current
    val rem = Store.rem(c)
    val quiets = Store.quiets(c)
    val appVer = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (e: Exception) { "?" }
    var showAdv by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val later: (() -> Unit) -> Unit = { f -> scope.launch(Dispatchers.Default) { f() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Settings", "LifeLock version $appVer") }
        item { SectionLabel("Appearance") }
        item {
            Surf {
                Title("Theme")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "Default", 4 to "Dark", 2 to "AMOLED", 3 to "Light", 0 to "System").forEach { (m, l) ->
                        Pill(l, Pal.mode == m) { Pal.mode = m; Store.setInt(c, "theme", m); bump() }
                    }
                }
                Sub("Default is the deep-navy look. Dark is black with a purple glow. AMOLED is pure #000000 with no glow, so pixels are fully off on your S24's screen.", Modifier.padding(top = 10.dp))
            }
        }
        item { SectionLabel("Default reminder type") }
        item {
            val st = Store.style(c)
            val fsOk = Build.VERSION.SDK_INT < 34 || c.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
            Surf {
                Title("How do you want to be reminded?")
                Sub("This is the default. Every reminder uses it unless you choose something else for that habit, target or goal. You can also change it from the reminder options while you add one.", Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeCard("🔔", "Gentle", "A normal notification that pops up while you're using the phone.", st == 0) { Store.setStyle(c, 0); later { ReminderReceiver.schedule(c) }; bump() }
                    ModeCard("📣", "Loud", "Alarm-style sound and strong vibration, plus a pop-up. Hard to miss.", st == 1) { Store.setStyle(c, 1); later { ReminderReceiver.schedule(c) }; bump() }
                    ModeCard("📱", "Wake the screen", "Loud, and turns the screen on with a big pop-up you can answer from the lock screen.", st == 2) { Store.setStyle(c, 2); later { ReminderReceiver.schedule(c) }; bump() }
                }
                if (st == 2 && !fsOk) {
                    Sub("Android needs your permission to show pop-ups over the lock screen.", Modifier.padding(top = 12.dp), Pal.orange)
                    Btn("Allow full-screen pop-ups", Modifier.padding(top = 8.dp), filled = false) {
                        Perm.open(c, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${c.packageName}")))
                    }
                }
                Sub("Not seeing pop-ups on Samsung? Settings → Notifications → App notifications → LifeLock: turn on Pop-up and Sound for the reminder categories, and check Do Not Disturb.",
                    Modifier.padding(top = 10.dp))
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
                    Switch(checked = rem.on, onCheckedChange = { Store.saveRem(c, rem.copy(on = it)); later { ReminderReceiver.schedule(c) }; bump() })
                }
                if (rem.on) {
                    Spacer(Modifier.height(16.dp))
                    Row {
                        TimeBtn("From", rem.from, Modifier.weight(1f)) { Store.saveRem(c, rem.copy(from = it)); later { ReminderReceiver.schedule(c) }; bump() }
                        Spacer(Modifier.width(12.dp))
                        TimeBtn("Until", rem.to, Modifier.weight(1f)) { Store.saveRem(c, rem.copy(to = it)); later { ReminderReceiver.schedule(c) }; bump() }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "Every 15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 180 to "3 hours").forEach { (m, l) ->
                            Pill(l, rem.every == m) { Store.saveRem(c, rem.copy(every = m)); later { ReminderReceiver.schedule(c) }; bump() }
                        }
                    }
                }
            }
        }
        if (rem.on || Store.habits(c).any { it.remOn } || Store.tracks(c).any { it.remOn }) {
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
        item { SectionLabel("Help & support") }
        item {
            Surf {
                Title("Quick answers")
                listOf(
                    "Blocking isn't working" to "Check Protection status above: every line should say On. Also set LifeLock's battery to Unrestricted, and if Accessibility is greyed out, allow restricted settings in App info.",
                    "An app was blocked by mistake" to "The lock screen shows the app's ID at the bottom. Note it so the app can be added to the exempt list.",
                    "I can't delete a goal or limit" to "They lock on purpose so you can't talk yourself out of them. Goals unlock after 10 minutes or when they end. Limits unlock after 7 days.",
                    "A reminder came at the wrong time" to "Check Quiet times. Diagnostics below lists the last reminders sent and the hours each one was set for."
                ).forEach { (q, a) ->
                    Text(q, color = Pal.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                    Sub(a, Modifier.padding(top = 2.dp))
                }
            }
        }
        item {
            Surf {
                Title("Something not working?")
                Sub("Copy a short report of what LifeLock has been doing and send it to whoever looks after the app. It contains no personal data, only counts.",
                    Modifier.padding(top = 4.dp))
                Btn("Copy support report", Modifier.padding(top = 12.dp), filled = false) {
                    val day = Store.today()
                    val sb = StringBuilder("LifeLock $appVer support report (today / yesterday)\n")
                    listOf("events" to "App switches seen", "ticks" to "Timer wake-ups", "blocks" to "Block screens shown",
                        "alarms" to "Reminder alarms", "notifs" to "Notifications sent", "polls" to "Compatibility checks").forEach { (k, l) ->
                        sb.append("$l: ${Store.diag(c, k, day)} / ${Store.diag(c, k, day - 1)}\n")
                    }
                    sb.append("Recent reminders:\n"); Store.notifLog(c).forEach { sb.append("  $it\n") }
                    sb.append("Engine running: ${GuardService.inst != null}, usage access: ${Compat.usageOk(c)}, battery-saver reminders: ${Store.lowPower(c)}, default reminder style: ${Store.style(c)}\n")
                    c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("lifelock", sb.toString()))
                    toast(c, "Copied")
                }
            }
        }
        if (showAdv) {
        item {
            Surf {
                Title("Protection status")
                StatusLine(Perm.guard(c), "Blocking engine")
                StatusLine(Perm.overlay(c), "Block screen over apps")
                StatusLine(Perm.notif(c), "Notifications")
                StatusLine(Compat.usageOk(c), "Usage access (exact time limits)")
                Sub(if (GuardService.inst == null) "Engine is not running."
                    else "Since it started ${(System.currentTimeMillis() - GuardService.startedAt) / 60000} min ago: ${GuardService.events} app switches, ${GuardService.ticks} timer wake-ups.",
                    Modifier.padding(top = 12.dp))
            }
        }
        item { SectionLabel("Diagnostics") }
        item {
            val day = Store.today()
            val rows = listOf("events" to "App switches seen", "ticks" to "Timer wake-ups", "blocks" to "Block screens shown",
                "alarms" to "Reminder alarms", "notifs" to "Notifications sent", "polls" to "Compatibility checks")
            Surf {
                Title("Activity: today / yesterday")
                rows.forEach { (k, l) ->
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Text(l, color = Pal.text2, modifier = Modifier.weight(1f))
                        Text("${Store.diag(c, k, day)} / ${Store.diag(c, k, day - 1)}", color = Pal.ink, fontWeight = FontWeight.SemiBold)
                    }
                }
                val rlog = Store.notifLog(c)
                if (rlog.isNotEmpty()) {
                    Text("Recent reminders", color = Pal.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                    rlog.forEach { Text(it, fontSize = 12.sp, color = Pal.text2, modifier = Modifier.padding(top = 4.dp)) }
                }
                Sub("Low numbers mean LifeLock is mostly asleep. If your battery page still shows it high, send me this report.", Modifier.padding(top = 12.dp))
                Btn("Copy report", Modifier.padding(top = 12.dp), filled = false) {
                    val sb = StringBuilder("LifeLock diagnostics (today / yesterday)\n")
                    rows.forEach { (k, l) -> sb.append("$l: ${Store.diag(c, k, day)} / ${Store.diag(c, k, day - 1)}\n") }
                    sb.append("Recent reminders:\n"); Store.notifLog(c).forEach { sb.append("  $it\n") }
                    sb.append("Engine running: ${GuardService.inst != null}, compat helper: ${WatchService.alive}, battery-saver reminders: ${Store.lowPower(c)}\n")
                    c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("lifelock", sb.toString()))
                    toast(c, "Copied")
                }
            }
        }
        }
        item { SectionLabel("Battery") }
        item {
            Surf {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip("🔋", Pal.green)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Title("Battery-saver reminders")
                        Sub("Reminders appear when you next pick up your phone instead of waking it. Lowest possible drain, but not instant while the phone is idle. Only applies to the Gentle style; Loud reminders always arrive on time.")
                    }
                    Switch(checked = Store.lowPower(c), onCheckedChange = { Store.setLowPower(c, it); later { ReminderReceiver.schedule(c) }; bump() })
                }
            }
        }
        item { SectionLabel("Widget & quick tiles") }
        item {
            val counters = Store.habits(c).filter { it.kind == 1 }
            val qh = Store.quickHabit(c)
            Surf {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip("⚡", Pal.orange)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Title("One-tap logging")
                        Sub("Log water, check in a goal or start a focus session without opening the app.")
                    }
                }
                Btn("Add home-screen widget", Modifier.padding(top = 14.dp)) {
                    val wm = android.appwidget.AppWidgetManager.getInstance(c)
                    if (wm.isRequestPinAppWidgetSupported)
                        wm.requestPinAppWidget(android.content.ComponentName(c, LifeWidget::class.java), null, null)
                    else toast(c, "Long-press your home screen, then Widgets, then LifeLock.")
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    Btn("Add Quick Settings tiles", Modifier.padding(top = 10.dp), filled = false) {
                        val sbm = c.getSystemService(android.app.StatusBarManager::class.java)
                        listOf<Pair<Class<*>, String>>(QuickLogTile::class.java to "Quick log", CheckInTile::class.java to "Check in",
                            FocusTile::class.java to "Focus").forEach { (cls, label) ->
                            sbm.requestAddTileService(android.content.ComponentName(c, cls), label,
                                android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_stat), c.mainExecutor) { }
                        }
                    }
                } else Sub("Swipe down twice, tap the edit pencil, and drag the LifeLock tiles in.", Modifier.padding(top = 10.dp))
                if (counters.isNotEmpty()) {
                    Sub("Quick-log habit", Modifier.padding(top = 14.dp))
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Auto", qh.isEmpty() || counters.none { it.id == qh }) { Store.setQuickHabit(c, ""); later { LifeWidget.refresh(c) }; bump() }
                        counters.forEach { h ->
                            Pill("${h.emoji} ${h.name}", qh == h.id) { Store.setQuickHabit(c, h.id); later { LifeWidget.refresh(c) }; bump() }
                        }
                    }
                }
            }
        }
        if (showAdv) {
        item { SectionLabel("Works with banking apps") }
        item {
            val pa = Store.pauseApps(c).toList()
            var picker by remember { mutableStateOf(false) }
            Surf {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip("🛡️", Pal.accent2)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Title("Pause blocking in chosen apps")
                        Sub("For apps that refuse to run while an accessibility service is on, like banking apps. LifeLock switches itself off while you're in them and back on when you leave.")
                    }
                }
                StatusLine(Compat.secureOk(c), "Secure-settings permission (one-time ADB)")
                StatusLine(Compat.usageOk(c), "Usage access")
                StatusLine(WatchService.alive, "Helper service running")
                if (!Compat.secureOk(c)) {
                    Spacer(Modifier.height(12.dp))
                    Text(Compat.CMD, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Pal.text2,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.card2).padding(12.dp))
                    Sub("Run this once from a computer with USB debugging on. On Samsung, switch off Auto Blocker first.", Modifier.padding(top = 8.dp))
                    Btn("Copy command", Modifier.padding(top = 10.dp), filled = false) {
                        c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", Compat.CMD))
                        toast(c, "Copied")
                    }
                }
                if (!Compat.usageOk(c)) Btn("Grant usage access", Modifier.padding(top = 10.dp), filled = false) {
                    Perm.open(c, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                if (pa.isNotEmpty()) Spacer(Modifier.height(10.dp))
                pa.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(p, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(AppRepo.label(p), color = Pal.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Pill("Open", true) { Compat.openPaused(c, p) }
                        IconButton(onClick = { Store.setPauseApps(c, Store.pauseApps(c) - p); Compat.sync(c); bump() }) {
                            Icon(Icons.Default.Close, null, tint = Pal.muted)
                        }
                    }
                }
                Btn(if (pa.isEmpty()) "Choose apps" else "Change apps", Modifier.padding(top = 10.dp), filled = false) { picker = true }
                Sub("Tip: use the Open button here to launch the app. It pauses blocking before the app starts, which is the most reliable way.",
                    Modifier.padding(top = 10.dp))
            }
            if (picker) AppPicker("Pause blocking in…", true, pa.toSet(), { sel ->
                val ok = sel.filter { !Store.ruleTouches(c, it) }.toSet()
                if (ok.size < sel.size) toast(c, "Apps that your goals, limits or schedules block can't be exempt.")
                Store.setPauseApps(c, ok); Compat.sync(c); picker = false; bump()
            }, { picker = false })
        }
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
        item {
            Btn(if (showAdv) "Hide advanced settings" else "Show advanced settings (diagnostics, banking-app mode)",
                Modifier.padding(top = 8.dp), filled = false) { showAdv = !showAdv }
        }
    }
}
