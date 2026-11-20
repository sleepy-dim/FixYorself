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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin

fun fmtNum(v: Double): String = String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

/** 1, 2 or 5 times a power of ten, nearest to x. */
fun niceStep(x: Double): Double {
    if (x <= 0.0) return 1.0
    val p = Math.pow(10.0, Math.floor(Math.log10(x)))
    val f = x / p
    return (if (f < 1.5) 1.0 else if (f < 3.5) 2.0 else if (f < 7.5) 5.0 else 10.0) * p
}

/** The app picks sensible jump sizes from the size of the journey: weight 80→70 gives 0.1 / 0.5 / 1, push-ups 0→100 gives 1 / 5 / 10. */
fun smartSteps(start: Double, target: Double, unit: String): List<Double> {
    val r = Math.abs(target - start).coerceAtLeast(0.1)
    val decimals = start % 1.0 != 0.0 || target % 1.0 != 0.0 || unit.trim().lowercase() in listOf("kg", "lb", "lbs", "cm", "in", "km", "mi", "%")
    val floor = if (decimals) 0.1 else 1.0
    return listOf(niceStep(r / 100), niceStep(r / 20), niceStep(r / 8)).map { maxOf(it, floor) }.distinct().sorted()
}

@Composable
fun Sparkline(vals: List<Double>, target: Double, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (vals.size < 2) return@Canvas
        val all = vals + target
        val lo = all.min()
        val hi = all.max()
        val span = if (hi - lo > 0.0) hi - lo else 1.0
        fun y(v: Double): Float = (size.height * (1.0 - (v - lo) / span)).toFloat()
        drawLine(color.copy(alpha = 0.35f), Offset(0f, y(target)), Offset(size.width, y(target)), strokeWidth = 2f)
        val path = Path()
        vals.forEachIndexed { i, v ->
            val x = size.width * i / (vals.size - 1)
            if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
        }
        drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color, 8f, Offset(size.width, y(vals.last())))
    }
}

@Composable
fun TargetCard(tr: Track, ver: Int, go: (Page) -> Unit, bump: () -> Unit) {
    val c = LocalContext.current
    val cur = tr.cur
    val total = tr.target - tr.start
    val frac = if (total == 0.0) 1f else ((cur - tr.start) / total).toFloat().coerceIn(0f, 1f)
    val reached = if (tr.up) cur >= tr.target else cur <= tr.target
    val steps = smartSteps(tr.start, tr.target, tr.unit)
    val good = if (reached) Pal.green else Pal.accent
    val today = Store.today()
    val cd = Instant.ofEpochMilli(tr.created).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    var pace: Pair<String, Color>? = null
    var need = ""
    if (tr.end > 0 && !reached) {
        val tot = (tr.end - cd).coerceAtLeast(1L).toDouble()
        val el = (today - cd).coerceAtLeast(0L).coerceAtMost((tr.end - cd).coerceAtLeast(1L)).toDouble()
        val expected = tr.start + total * (el / tot)
        val ahead = if (tr.up) cur - expected else expected - cur
        val daysLeft = (tr.end - today).coerceAtLeast(0L)
        val perWeek = if (daysLeft > 0) Math.abs(tr.target - cur) / daysLeft * 7 else Math.abs(tr.target - cur)
        pace = (if (ahead >= 0) "On track" else "Behind by ${fmtNum(-ahead)} ${tr.unit}".trim()) to (if (ahead >= 0) Pal.green else Pal.orange)
        need = "${fmtNum(perWeek)} ${tr.unit}/week needed · $daysLeft days left".replace("  ", " ")
    }
    var dlg by remember { mutableStateOf(false) }
    var mode by remember { mutableIntStateOf(0) }
    var txt by remember { mutableStateOf("") }
    Surf(onClick = { go(Page.TargetEdit(tr.id)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ring(frac, 76.dp, 8.dp, good) { Text("${(frac * 100).toInt()}%", fontWeight = FontWeight.ExtraBold, color = Pal.ink, fontSize = 16.sp) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Title("${tr.emoji} ${tr.name}")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmtNum(cur), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Pal.ink)
                    Text(" ${tr.unit}", fontSize = 14.sp, color = Pal.muted, modifier = Modifier.padding(bottom = 5.dp))
                }
                Sub("start ${fmtNum(tr.start)} → goal ${fmtNum(tr.target)} ${tr.unit}".trim())
            }
        }
        Spacer(Modifier.height(12.dp))
        Sparkline(tr.hist.takeLast(40).map { it.second }, tr.target, good, Modifier.fillMaxWidth().height(56.dp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (reached) Chip("🎉 Goal reached", Pal.green)
            else Chip("${fmtNum(Math.abs(tr.target - cur))} ${tr.unit} to go".replace("  ", " "), Pal.accent)
            pace?.let { Chip(it.first, it.second) }
        }
        if (need.isNotEmpty()) Sub(need, Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.reversed().forEach { s -> Pill("−${fmtNum(s)}", !tr.up) { Store.trackAdd(c, tr.id, -s); bump() } }
            steps.forEach { s -> Pill("+${fmtNum(s)}", tr.up) { Store.trackAdd(c, tr.id, s); bump() } }
            Pill("Custom", false) { txt = ""; mode = 0; dlg = true }
            if (tr.hist.size > 1) Pill("Undo", false) { Store.trackUndo(c, tr.id); bump() }
        }
    }
    if (dlg) AlertDialog(
        onDismissRequest = { dlg = false }, containerColor = Pal.card,
        title = { Text("Log ${tr.name}", color = Pal.ink) },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Add", mode == 0) { mode = 0 }
                    Pill("Subtract", mode == 1) { mode = 1 }
                    Pill("Set to", mode == 2) { mode = 2 }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = txt, onValueChange = { txt = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(9) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text(tr.unit.ifBlank { "Value" }) })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = txt.replace(',', '.').toDoubleOrNull()
                if (v != null) {
                    if (mode == 0) Store.trackAdd(c, tr.id, v) else if (mode == 1) Store.trackAdd(c, tr.id, -v) else Store.trackSet(c, tr.id, v)
                    bump()
                }
                dlg = false
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { dlg = false }) { Text("Cancel") } }
    )
}

private class TTpl(val name: String, val emoji: String, val unit: String, val start: Double, val target: Double)
private val TTPLS = listOf(
    TTpl("Lose weight", "⚖️", "kg", 80.0, 70.0),
    TTpl("Push-ups", "💪", "reps", 0.0, 100.0),
    TTpl("Running", "🏃", "km", 0.0, 10.0),
    TTpl("Bench press", "🏋️", "kg", 40.0, 80.0),
    TTpl("Savings", "💰", "", 0.0, 1000.0)
)

@Composable
fun TargetEditor(id: String?, onClose: () -> Unit) {
    val c = LocalContext.current
    val ex = remember { Store.tracks(c).firstOrNull { it.id == id } }
    var name by remember { mutableStateOf(ex?.name ?: "") }
    var emoji by remember { mutableStateOf(ex?.emoji ?: "🎯") }
    var unit by remember { mutableStateOf(ex?.unit ?: "") }
    var startTxt by remember { mutableStateOf(ex?.let { fmtNum(it.start) } ?: "") }
    var targetTxt by remember { mutableStateOf(ex?.let { fmtNum(it.target) } ?: "") }
    var end by remember { mutableStateOf<LocalDate?>(if (ex != null && ex.end > 0) LocalDate.ofEpochDay(ex.end) else null) }
    var rMode by remember { mutableIntStateOf(if (ex == null || !ex.remOn) 0 else if (ex.remEvery >= 1440) 1 else 2) }
    var rAt by remember { mutableIntStateOf(ex?.remFrom ?: 480) }
    var rFrom by remember { mutableIntStateOf(if (ex != null && ex.remEvery < 1440) ex.remFrom else 540) }
    var rTo by remember { mutableIntStateOf(if (ex != null && ex.remEvery < 1440) ex.remTo else 1200) }
    var rEvery by remember { mutableIntStateOf(if (ex != null && ex.remEvery < 1440) ex.remEvery else 180) }
    var rDays by remember { mutableIntStateOf(ex?.remDays ?: 127) }
    val sv = startTxt.replace(',', '.').toDoubleOrNull()
    val tv = targetTxt.replace(',', '.').toDoubleOrNull()
    val numKb = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    EditorPage(if (ex == null) "New target" else "Edit target", onClose) {
        if (ex == null) {
            Sub("Quick start")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TTPLS.forEach { t ->
                    Pill("${t.emoji} ${t.name}", false) {
                        name = t.name; emoji = t.emoji; unit = t.unit; startTxt = fmtNum(t.start); targetTxt = fmtNum(t.target)
                        rMode = 1; rAt = if (t.name.startsWith("Lose")) 450 else 1080
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = emoji, onValueChange = { emoji = it.take(4) }, singleLine = true, modifier = Modifier.width(84.dp))
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("What are you working on?") }, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(value = unit, onValueChange = { unit = it.take(12) }, singleLine = true,
            label = { Text("Unit (kg, reps, km…)") }, modifier = Modifier.fillMaxWidth())
        Surf {
            Title("From → To")
            Spacer(Modifier.height(10.dp))
            Row {
                OutlinedTextField(value = startTxt, onValueChange = { if (ex == null) startTxt = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' || ch == '-' }.take(10) },
                    enabled = ex == null, singleLine = true, keyboardOptions = numKb, label = { Text("I'm at") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(value = targetTxt, onValueChange = { targetTxt = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' || ch == '-' }.take(10) },
                    singleLine = true, keyboardOptions = numKb, label = { Text("Goal") }, modifier = Modifier.weight(1f))
            }
            if (sv != null && tv != null && sv != tv) {
                val st = smartSteps(sv, tv, unit)
                Sub("${if (tv > sv) "Going up" else "Going down"} by ${fmtNum(Math.abs(tv - sv))} ${unit.trim()}. " +
                    "Quick buttons: " + st.joinToString("  ") { "±" + fmtNum(it) } + " and a custom value.", Modifier.padding(top = 10.dp))
            }
        }
        Surf(onClick = {
            val d = end ?: LocalDate.now().plusMonths(1)
            DatePickerDialog(c, { _, y, m, dd -> end = LocalDate.of(y, m + 1, dd) }, d.year, d.monthValue - 1, d.dayOfMonth)
                .apply { datePicker.minDate = System.currentTimeMillis() + 86400000L }.show()
        }) {
            Sub("Deadline (optional)")
            Text(end?.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")) ?: "None — tap to set", fontSize = 20.sp,
                fontWeight = FontWeight.Bold, color = Pal.ink)
            if (end != null) Sub("Tap to change. The card will show if you're on track.", Modifier.padding(top = 4.dp))
        }
        if (end != null) Btn("Remove deadline", filled = false) { end = null }
        Surf {
            Title("Reminders")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("None", rMode == 0) { rMode = 0 }
                Pill("Once a day", rMode == 1) { rMode = 1 }
                Pill("Every few hours", rMode == 2) { rMode = 2 }
            }
            if (rMode == 1) {
                Spacer(Modifier.height(12.dp))
                TimeBtn("Remind me at", rAt, Modifier.fillMaxWidth()) { rAt = it }
            }
            if (rMode == 2) {
                Spacer(Modifier.height(12.dp))
                Row {
                    TimeBtn("From", rFrom, Modifier.weight(1f)) { rFrom = it }
                    Spacer(Modifier.width(12.dp))
                    TimeBtn("Until", rTo, Modifier.weight(1f)) { rTo = it }
                }
                if (rFrom > rTo) Sub("⚠️ This window runs past midnight, until ${hhmm(rTo)} the next day.", Modifier.padding(top = 8.dp), Pal.orange)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60 to "1 hour", 120 to "2 hours", 180 to "3 hours", 240 to "4 hours", 360 to "6 hours").forEach { (m, l) ->
                        Pill(l, rEvery == m) { rEvery = m }
                    }
                }
            }
            if (rMode != 0) {
                Spacer(Modifier.height(14.dp))
                Sub("On these days")
                Spacer(Modifier.height(8.dp))
                DayChips(rDays) { rDays = it }
                Sub("Skipped during your quiet times, once you've logged today, and after you reach the goal.", Modifier.padding(top = 10.dp))
            }
        }
        Btn("Save target") {
            if (name.isBlank()) toast(c, "Give it a name")
            else if (sv == null || tv == null) toast(c, "Enter both numbers")
            else if (sv == tv) toast(c, "Start and goal can't be the same")
            else if (rMode != 0 && rDays == 0) toast(c, "Pick at least one day for reminders")
            else {
                val now = System.currentTimeMillis()
                val e = end?.toEpochDay() ?: 0L
                val rOn = rMode != 0
                val rf = if (rMode == 1) rAt else rFrom
                val rt = if (rMode == 1) rAt else rTo
                val re = if (rMode == 1) 1440 else rEvery
                if (ex == null) Store.saveTrack(c, Track(now.toString(), name.trim(), emoji.ifBlank { "🎯" }, unit.trim(), sv, tv, e, now,
                    listOf(now to sv), rOn, rf, rt, re, rDays))
                else Store.saveTrack(c, ex.copy(name = name.trim(), emoji = emoji.ifBlank { "🎯" }, unit = unit.trim(), target = tv, end = e,
                    remOn = rOn, remFrom = rf, remTo = rt, remEvery = re, remDays = rDays))
                ReminderReceiver.schedule(c)
                onClose()
            }
        }
        if (ex != null) ConfirmBtn("Delete target") { Store.deleteTrack(c, ex.id); ReminderReceiver.schedule(c); onClose() }
    }
}
