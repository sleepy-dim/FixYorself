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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

private class Tpl(val name: String, val emoji: String, val kind: Int, val target: Int, val unit: String, val step: Int,
                  val from: Int, val to: Int, val every: Int)

private val TPLS = listOf(
    Tpl("Drink water", "💧", 1, 3000, "ml", 250, 480, 1320, 90),
    Tpl("Walk break", "🚶", 1, 8, "walks", 1, 540, 1140, 60),
    Tpl("Calories", "🍽️", 2, 1300, "kcal", 100, 540, 1260, 240),
    Tpl("Steps", "👟", 1, 8000, "steps", 1000, 600, 1200, 120),
    Tpl("Workout", "🏋️", 0, 1, "", 1, 1080, 1140, 1440),
    Tpl("Read", "📖", 0, 1, "", 1, 1260, 1320, 1440),
    Tpl("Meditate", "🧘", 0, 1, "", 1, 420, 480, 1440),
    Tpl("Vitamins", "💊", 0, 1, "", 1, 540, 600, 1440)
)

fun everyLabel(m: Int) = if (m >= 1440) "day" else if (m % 60 == 0) "${m / 60} h" else "$m min"

// ---------------------------------------------------------------- list screen
@Composable
fun HabitsScreen(ver: Int, now: Long, sub: Int, onSub: (Int) -> Unit, go: (Page) -> Unit, bump: () -> Unit) {
    val c = LocalContext.current
    val t = Store.today()
    val hs = Store.habits(c)
    val trs = Store.tracks(c)
    val due = hs.filter { Store.hActive(it, t) }
    val done = due.count { Store.met(it, Store.hLog(c, it.id, t)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Progress", if (sub == 0) "Daily targets with their own reminders." else "Work up or down to a number: weight, push-ups, savings…") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Daily habits", sub == 0) { onSub(0) }
                Pill("Targets", sub == 1) { onSub(1) }
            }
        }
        if (sub == 0) {
            if (due.isNotEmpty()) item {
                Surf {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ring(done.toFloat() / due.size, 72.dp, 8.dp, Pal.green) {
                            Text("${(100 * done / due.size)}%", fontWeight = FontWeight.ExtraBold, color = Pal.ink, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Title("$done of ${due.size} done today", size = 18)
                            Sub(if (done == due.size) "Perfect day. Keep it going 🔥" else "${due.size - done} to go")
                        }
                    }
                }
            }
            if (hs.isEmpty()) item { EmptyState("🌱", "No habits yet", "Start from a template: Drink water, Walk break, Calories, Steps…") }
            items(hs, key = { it.id }) { h -> HabitCard(h, t, ver, go, bump) }
        } else {
            if (trs.isEmpty()) item { EmptyState("📈", "No targets yet", "Track weight, push-ups, running distance or savings. The app picks the +/− jumps for you.") }
            items(trs, key = { it.id }) { tr -> TargetCard(tr, ver, go, bump) }
        }
    }
}

@Composable
fun HabitCard(h: Habit, t: Long, ver: Int, go: (Page) -> Unit, bump: () -> Unit) {
    val c = LocalContext.current
    val v = Store.hLog(c, h.id, t)
    val active = Store.hActive(h, t)
    val met = Store.met(h, v)
    val cd = Instant.ofEpochMilli(h.created).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    val frac = if (h.kind == 0) (if (met) 1f else 0f) else (v.toFloat() / h.target.coerceAtLeast(1)).coerceIn(0f, 1f)
    val col = if (h.kind == 2 && v > h.target) Pal.red else if (met) Pal.green else Pal.accent
    var custom by remember { mutableStateOf(false) }
    var txt by remember { mutableStateOf("") }
    Surf(onClick = { go(Page.HabitEdit(h.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(frac, 68.dp, 7.dp, col) { Text(h.emoji, fontSize = 26.sp) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Title(h.name)
                Text(if (!active) "Rest day" else when (h.kind) {
                    0 -> if (met) "Done ✓" else "Not done yet"
                    1 -> "$v / ${h.target} ${h.unit}"
                    else -> "$v / ${h.target} ${h.unit} max"
                }, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = if (active) col else Pal.muted)
            }
            Chip("🔥 ${Store.hStreak(c, h)}", Pal.orange)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 6 downTo 0) {
                val d = t - i
                val on = Store.hActive(h, d) && d >= cd
                val pv = Store.hLog(c, h.id, d)
                val f = if (!on) 0f else when (h.kind) {
                    0 -> if (pv >= 1) 1f else 0f
                    1 -> (pv.toFloat() / h.target.coerceAtLeast(1)).coerceIn(0f, 1f)
                    else -> if (pv > 0) 1f else 0f
                }
                val bc = if (h.kind == 2 && pv > h.target) Pal.red else if (Store.met(h, pv)) Pal.green else Pal.accent
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(Pal.card2), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight(f.coerceAtLeast(0.001f)).background(bc.copy(alpha = if (d == t) 1f else 0.75f)))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(LocalDate.ofEpochDay(d).dayOfWeek.name.take(1), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (d == t) Pal.ink else Pal.muted)
                }
            }
        }
        if (h.remOn) Sub("🔔 " + (if (h.remEvery >= 1440) "daily at ${hhmm(h.remFrom)}"
            else "every ${everyLabel(h.remEvery)}, " + (if (h.remFrom == h.remTo) "all day" else "${hhmm(h.remFrom)}–${hhmm(h.remTo)}")),
            Modifier.padding(top = 12.dp))
        if (active) {
            if (h.kind == 0) {
                Btn(if (met) "Undo" else "Mark done", Modifier.padding(top = 14.dp), filled = !met, color = Pal.green) {
                    Store.hSet(c, h.id, if (met) 0 else 1); bump()
                }
            } else {
                Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("−${h.step}", false) { Store.hAdd(c, h.id, -h.step); bump() }
                    Pill("+${h.step}", true) { Store.hAdd(c, h.id, h.step); bump() }
                    Pill("+${h.step * 2}", false) { Store.hAdd(c, h.id, h.step * 2); bump() }
                    Pill("Custom", false) { custom = true }
                }
            }
        }
    }
    if (custom) AlertDialog(
        onDismissRequest = { custom = false }, containerColor = Pal.card,
        title = { Text("Add to ${h.name}", color = Pal.ink) },
        text = {
            OutlinedTextField(value = txt, onValueChange = { txt = it.filter { ch -> ch.isDigit() }.take(6) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(h.unit.ifBlank { "Amount" }) })
        },
        confirmButton = {
            TextButton(onClick = {
                val n = txt.toIntOrNull() ?: 0
                if (n > 0) { Store.hAdd(c, h.id, n); bump() }
                txt = ""; custom = false
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- editor
@Composable
fun HabitEditor(id: String?, onClose: () -> Unit) {
    val c = LocalContext.current
    val ex = remember { Store.habits(c).firstOrNull { it.id == id } }
    var name by remember { mutableStateOf(ex?.name ?: "") }
    var emoji by remember { mutableStateOf(ex?.emoji ?: "✅") }
    var kind by remember { mutableIntStateOf(ex?.kind ?: 1) }
    var target by remember { mutableIntStateOf(ex?.target ?: 1) }
    var unit by remember { mutableStateOf(ex?.unit ?: "") }
    var step by remember { mutableIntStateOf(ex?.step ?: 1) }
    var days by remember { mutableIntStateOf(ex?.days ?: 127) }
    var remOn by remember { mutableStateOf(ex?.remOn ?: false) }
    var allDay by remember { mutableStateOf(ex != null && ex.remFrom == ex.remTo) }
    var from by remember { mutableIntStateOf(ex?.remFrom ?: 480) }
    var to by remember { mutableIntStateOf(ex?.remTo ?: 1320) }
    var every by remember { mutableIntStateOf(ex?.remEvery ?: 60) }

    fun apply(t: Tpl) {
        name = t.name; emoji = t.emoji; kind = t.kind; target = t.target; unit = t.unit; step = t.step
        remOn = true; allDay = false; from = t.from; to = t.to; every = t.every
    }

    EditorPage(if (ex == null) "New habit" else "Edit habit", onClose) {
        if (ex == null) {
            Sub("Quick start")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TPLS.forEach { Pill("${it.emoji} ${it.name}", false) { apply(it) } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = emoji, onValueChange = { emoji = it.take(4) }, singleLine = true, modifier = Modifier.width(84.dp))
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Habit name") }, modifier = Modifier.weight(1f))
        }
        Surf {
            Title("Type")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Yes / No", kind == 0) { kind = 0 }
                Pill("At least", kind == 1) { kind = 1 }
                Pill("At most", kind == 2) { kind = 2 }
            }
            Sub(when (kind) {
                0 -> "A simple daily task. Tap once when it's done."
                1 -> "Reach at least the target, e.g. 3000 ml of water."
                else -> "Stay at or under the target, e.g. 1300 kcal. A day counts once you've logged something."
            }, Modifier.padding(top = 8.dp))
        }
        if (kind != 0) Surf {
            Title("Daily target")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = unit, onValueChange = { unit = it.take(12) }, singleLine = true,
                label = { Text("Unit (ml, kcal, walks…)") }, modifier = Modifier.fillMaxWidth())
            val sfx = if (unit.isBlank()) "" else " ${unit.trim()}"
            Stepper("Target", target, 1, 1000000, sfx, step) { target = it }
            Stepper("Quick-add amount", step, 1, 100000, sfx, if (step >= 100) 50 else 1) { step = it }
        }
        Surf {
            Title("Days")
            Spacer(Modifier.height(10.dp))
            DayChips(days) { days = it }
        }
        Surf {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Title("Reminders"); Sub("Skipped on quiet times and once the target is hit") }
                Switch(checked = remOn, onCheckedChange = { remOn = it })
            }
            if (remOn) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("All day (24h)", allDay) { allDay = true }
                    Pill("Only between", !allDay) { allDay = false }
                }
                if (!allDay) {
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TimeBtn("From", from, Modifier.weight(1f)) { from = it }
                        Spacer(Modifier.width(12.dp))
                        TimeBtn("Until", to, Modifier.weight(1f)) { to = it }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Sub("How often")
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30 to "30 min", 60 to "1 hour", 90 to "90 min", 120 to "2 hours", 180 to "3 hours", 240 to "4 hours", 1440 to "Once a day").forEach { (m, l) ->
                        Pill(l, every == m) { every = m }
                    }
                }
                if (every >= 1440) Sub("Once a day fires at the From time" + if (allDay) " (00:00 when set to all day)." else ".", Modifier.padding(top = 8.dp))
            }
        }
        Btn("Save habit") {
            if (days == 0) toast(c, "Pick at least one day")
            else {
                val f = if (allDay) 0 else from
                val tt = if (allDay) 0 else to
                if (remOn && !allDay && from == to) toast(c, "From and Until can't be the same. Choose All day instead.")
                else {
                    Store.saveHabit(c, Habit(ex?.id ?: System.currentTimeMillis().toString(), name.ifBlank { "Habit" }, emoji.ifBlank { "✅" },
                        kind, if (kind == 0) 1 else target, unit.trim(), if (kind == 0) 1 else step, days, remOn, f, tt, every,
                        ex?.created ?: System.currentTimeMillis(), ex?.streak ?: 0, ex?.best ?: 0, ex?.ev ?: (Store.today() - 1)))
                    ReminderReceiver.schedule(c)
                    onClose()
                }
            }
        }
        if (ex != null) Btn("Delete habit", filled = false) { Store.deleteHabit(c, ex.id); ReminderReceiver.schedule(c); onClose() }
    }
}
