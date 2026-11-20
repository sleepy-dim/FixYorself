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
    Tpl("Walk break", "🚶", 1, 0, "walks", 1, 540, 1140, 60),
    Tpl("Daily walk", "🚶", 0, 1, "", 1, 1020, 1200, 60),
    Tpl("Calories", "🍽️", 2, 1300, "kcal", 100, 540, 1260, 240),
    Tpl("Steps", "👟", 1, 8000, "steps", 1000, 600, 1200, 120),
    Tpl("Workout", "🏋️", 0, 1, "", 1, 1080, 1140, 1440),
    Tpl("Read", "📖", 0, 1, "", 1, 1260, 1320, 1440),
    Tpl("Meditate", "🧘", 0, 1, "", 1, 420, 480, 1440),
    Tpl("Vitamins", "💊", 0, 1, "", 1, 540, 600, 1440)
)

fun ruleText(from: Int, to: Int, every: Int): String =
    if (every >= 1440) "daily at ${hhmm(from)}"
    else "every ${everyLabel(every)}, " + (if (from == to) "all day" else "${hhmm(from)}–${hhmm(to)}")

fun remSummary(h: Habit): String = ruleText(h.remFrom, h.remTo, h.remEvery) +
    (if (h.extra.isNotEmpty()) " · +${h.extra.size} more schedule${if (h.extra.size > 1) "s" else ""}" else "")

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
    val open = h.kind == 1 && h.target == 0
    val best = if (open) Store.hBest(c, h.id, t) else 0
    val frac = if (h.kind == 0) (if (met) 1f else 0f)
        else if (open) (if (best == 0) (if (v > 0) 1f else 0f) else (v.toFloat() / best).coerceIn(0f, 1f))
        else (v.toFloat() / h.target.coerceAtLeast(1)).coerceIn(0f, 1f)
    val col = if (open) (if (best > 0 && v > best) Pal.green else Pal.accent)
        else if (h.kind == 2 && v > h.target) Pal.red else if (met) Pal.green else Pal.accent
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
                    1 -> if (h.target == 0) "$v ${h.unit} today".trim() else "$v / ${h.target} ${h.unit}"
                    else -> "$v / ${h.target} ${h.unit} max"
                }, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = if (active) col else Pal.muted)
                if (open && active) Sub(
                    if (best == 0) "Log your first one to set a record"
                    else if (v > best) "🎉 New record! Previous best: $best"
                    else "Best day: $best · ${best - v + 1} more to beat it")
            }
            Chip("🔥 ${Store.hStreak(c, h)}", Pal.orange)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val weekMax = (0..6).maxOf { Store.hLog(c, h.id, t - it) }
            for (i in 6 downTo 0) {
                val d = t - i
                val on = Store.hActive(h, d) && d >= cd
                val pv = Store.hLog(c, h.id, d)
                val f = if (!on) 0f else when (h.kind) {
                    0 -> if (pv >= 1) 1f else 0f
                    1 -> (pv.toFloat() / (if (h.target == 0) weekMax.coerceAtLeast(1) else h.target)).coerceIn(0f, 1f)
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
        if (h.remOn) Sub("🔔 " + remSummary(h), Modifier.padding(top = 12.dp))
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
/** One reminder schedule: either "once a day at a time" or "repeat through the day", on chosen days. */
@Composable
fun RuleEditor(r: RemRule, showDays: Boolean, onChange: (RemRule) -> Unit) {
    val once = r.every >= 1440
    val allDay = !once && r.from == r.to
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Once a day", once) { onChange(r.copy(every = 1440, to = r.from)) }
            Pill("Repeat through the day", !once) {
                onChange(r.copy(every = if (r.every >= 1440) 60 else r.every, to = if (r.from == r.to) (r.from + 720).coerceAtMost(1439) else r.to))
            }
        }
        if (once) {
            TimeBtn("Remind me at", r.from, Modifier.fillMaxWidth()) { onChange(r.copy(from = it, to = it)) }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("All day (24h)", allDay) { onChange(r.copy(from = 0, to = 0)) }
                Pill("Only between", !allDay) { onChange(r.copy(from = if (allDay) 540 else r.from, to = if (allDay) 1260 else r.to)) }
            }
            if (!allDay) Row {
                TimeBtn("From", r.from, Modifier.weight(1f)) { onChange(r.copy(from = it)) }
                Spacer(Modifier.width(12.dp))
                TimeBtn("Until", r.to, Modifier.weight(1f)) { onChange(r.copy(to = it)) }
            }
            if (!allDay && r.from > r.to) Sub("⚠️ This window runs past midnight, until ${hhmm(r.to)} the next day.", color = Pal.orange)
            Sub("How often")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30 min", 60 to "1 hour", 90 to "90 min", 120 to "2 hours", 180 to "3 hours", 240 to "4 hours").forEach { (m, l) ->
                    Pill(l, r.every == m) { onChange(r.copy(every = m)) }
                }
            }
        }
        if (showDays) {
            Sub("On these days")
            DayChips(r.days) { onChange(r.copy(days = it)) }
        }
    }
}

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
    var rules by remember {
        mutableStateOf(
            if (ex != null) listOf(RemRule(if (ex.remDays < 0) ex.days else ex.remDays, ex.remFrom, ex.remTo, ex.remEvery)) + ex.extra
            else listOf(RemRule(127, 480, 1320, 60))
        )
    }
    var open by remember { mutableStateOf(ex != null && ex.kind == 1 && ex.target == 0) }
    var adv by remember { mutableStateOf(ex != null && (ex.extra.isNotEmpty() || (ex.remDays >= 0 && ex.remDays != ex.days))) }

    fun useTpl(t: Tpl) {
        name = t.name; emoji = t.emoji; kind = t.kind; target = t.target; unit = t.unit; step = t.step
        open = t.kind == 1 && t.target == 0
        remOn = true; adv = false; rules = listOf(RemRule(127, t.from, t.to, t.every))
    }

    EditorPage(if (ex == null) "New habit" else "Edit habit", onClose) {
        if (ex == null) {
            Sub("Quick start")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TPLS.forEach { Pill("${it.emoji} ${it.name}", false) { useTpl(it) } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = emoji, onValueChange = { emoji = it.take(4) }, singleLine = true, modifier = Modifier.width(84.dp))
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Habit name") }, modifier = Modifier.weight(1f))
        }
        Surf {
            Title("How do you do it?")
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard("✅", "Once a day", "Tick it off once. Like a workout, reading or vitamins.", kind == 0) { kind = 0 }
                ModeCard("🔢", "Several times a day, with a target", "Aim for a number. Like 8 walks or 3000 ml of water.", kind == 1 && !open) { kind = 1; open = false }
                ModeCard("♾️", "As many as I can, no target", "Log every time and beat your best day. Like walks or push-ups.", kind == 1 && open) { kind = 1; open = true }
                ModeCard("🚧", "Daily limit", "Stay under a number. Like 1300 kcal or 2 coffees.", kind == 2) { kind = 2 }
            }
        }
        if (kind != 0) Surf {
            Title(if (kind == 1) "What are you counting?" else "Daily limit")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = unit, onValueChange = { unit = it.take(12) }, singleLine = true,
                label = { Text("Unit (times, ml, kcal, walks…)") }, modifier = Modifier.fillMaxWidth())
            val sfx = if (unit.isBlank()) "" else " ${unit.trim()}"
            if (kind == 1 && open) Sub("No target. Your best day becomes the record to beat. Reminders keep coming but skip if you logged recently.", Modifier.padding(top = 10.dp))
            if (!(kind == 1 && open)) Stepper("Target", target, 1, 1000000, sfx, step) { target = it }
            Stepper("Quick-add amount", step, 1, 100000, sfx, if (step >= 100) 50 else 1) { step = it }
        }
        Surf {
            Title("Active on")
            Spacer(Modifier.height(10.dp))
            DayChips(days) { days = it }
        }
        Surf {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Title("Reminders"); Sub("Skipped on quiet times and once you've done it") }
                Switch(checked = remOn, onCheckedChange = { remOn = it })
            }
            if (remOn) {
                Spacer(Modifier.height(14.dp))
                if (!adv) {
                    RuleEditor(rules[0], false) { rules = listOf(it) }
                } else {
                    rules.forEachIndexed { i, r ->
                        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(Pal.card2).padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Schedule ${i + 1}", fontWeight = FontWeight.Bold, color = Pal.ink, modifier = Modifier.weight(1f))
                                if (rules.size > 1) Pill("Remove", false) { rules = rules.filterIndexed { j, _ -> j != i } }
                            }
                            Spacer(Modifier.height(10.dp))
                            RuleEditor(r, true) { nr -> rules = rules.mapIndexed { j, old -> if (j == i) nr else old } }
                        }
                    }
                    if (rules.size < 4) Btn("+ Add another schedule", filled = false) { rules = rules + rules.last().copy(days = 0) }
                }
                Spacer(Modifier.height(12.dp))
                Pill(if (adv) "Back to simple" else "Advanced: different days or times", adv) {
                    if (adv) { adv = false; rules = listOf(rules[0]) }
                    else { adv = true; val r0 = rules[0]; rules = listOf(r0.copy(days = 31), r0.copy(days = 96)) }
                }
                if (!adv) Sub("Advanced lets you set different reminders for school days and weekends, or any mix.", Modifier.padding(top = 8.dp))
            }
        }
        Btn("Save habit") {
            if (days == 0) toast(c, "Pick at least one day")
            else if (remOn && adv && rules.any { it.days == 0 }) toast(c, "Each reminder schedule needs at least one day")
            else {
                val m = rules[0]
                Store.saveHabit(c, Habit(ex?.id ?: System.currentTimeMillis().toString(), name.ifBlank { "Habit" }, emoji.ifBlank { "✅" },
                    kind, if (kind == 0) 1 else if (kind == 1 && open) 0 else target, unit.trim(), if (kind == 0) 1 else step, days, remOn, m.from, m.to, m.every,
                    ex?.created ?: System.currentTimeMillis(), ex?.streak ?: 0, ex?.best ?: 0, ex?.ev ?: (Store.today() - 1),
                    if (adv) m.days else -1, if (adv) rules.drop(1) else emptyList()))
                ReminderReceiver.schedule(c)
                onClose()
            }
        }
        if (ex != null) ConfirmBtn("Delete habit") { Store.deleteHabit(c, ex.id); ReminderReceiver.schedule(c); onClose() }
    }
}
