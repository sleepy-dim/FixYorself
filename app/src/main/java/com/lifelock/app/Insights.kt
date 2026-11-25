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
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import androidx.compose.foundation.lazy.LazyListScope

class Insights(val days: List<Long>, val perDay: List<Map<String, Long>>, val unlocks: List<Int>)

fun fmtDur(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }
fun fmtHrs(ms: Long): String = String.format(java.util.Locale.US, "%.1fh", ms / 3600000.0)

object UsageCalc {
    /** Foreground milliseconds per app between [from] and [to], read from Android's own usage records. */
    fun ms(c: Context, pkgs: Set<String>, from: Long, to: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        if (pkgs.isEmpty() || to <= from) return out
        val ev = try { c.getSystemService(UsageStatsManager::class.java).queryEvents(from - 3600000L, to) } catch (e: Exception) { return out }
        val e = UsageEvents.Event()
        var cur = ""
        var curStart = 0L
        fun add(p: String, a: Long, b: Long) {
            if (p !in pkgs) return
            val s = maxOf(a, from)
            val en = minOf(b, to)
            if (en > s) out[p] = (out[p] ?: 0L) + (en - s)
        }
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val pk = e.packageName ?: ""
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (pk != cur) {
                    if (cur.isNotEmpty()) add(cur, curStart, e.timeStamp)
                    cur = pk; curStart = e.timeStamp
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> { if (cur.isNotEmpty()) add(cur, curStart, e.timeStamp); cur = "" }
            }
        }
        if (cur.isNotEmpty()) add(cur, curStart, to)
        return out
    }

    /** Reads the last 7 days of foreground time per app plus unlock counts. Run off the main thread. */
    fun load(c: Context): Insights {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val dayStarts = LongArray(8) { i -> today.minusDays(6L - i).atStartOfDay(zone).toInstant().toEpochMilli() }
        val now = System.currentTimeMillis()
        val pm = c.packageManager
        val homeApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        val counts = HashMap<String, Boolean>()
        fun counted(p: String) = counts.getOrPut(p) { p != c.packageName && p !in homeApps && pm.getLaunchIntentForPackage(p) != null }
        val perDay = List(7) { HashMap<String, Long>() }
        val unlocks = IntArray(7)

        fun add(p: String, a: Long, b: Long) {
            if (b <= a || !counted(p)) return
            for (d in 0..6) {
                val s = maxOf(a, dayStarts[d])
                val e = minOf(b, dayStarts[d + 1])
                if (e > s) perDay[d][p] = (perDay[d][p] ?: 0L) + (e - s)
            }
        }
        fun dayOf(ts: Long): Int { for (d in 0..6) if (ts >= dayStarts[d] && ts < dayStarts[d + 1]) return d; return -1 }

        val ev = try { c.getSystemService(UsageStatsManager::class.java).queryEvents(dayStarts[0], now) } catch (e: Exception) {
            return Insights(List(7) { today.minusDays(6L - it).toEpochDay() }, List(7) { emptyMap<String, Long>() }, List(7) { 0 })
        }
        val e = UsageEvents.Event()
        var cur = ""
        var curStart = 0L
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val pk = e.packageName ?: ""
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (pk != cur) {
                        if (cur.isNotEmpty()) add(cur, curStart, e.timeStamp)
                        cur = pk; curStart = e.timeStamp
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (cur.isNotEmpty()) add(cur, curStart, e.timeStamp)
                    cur = ""
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> { val d = dayOf(e.timeStamp); if (d >= 0) unlocks[d]++ }
            }
        }
        if (cur.isNotEmpty()) add(cur, curStart, now)
        return Insights(List(7) { today.minusDays(6L - it).toEpochDay() }, perDay, unlocks.toList())
    }
}

@Composable
fun SinkRow(p: String, value: Long, top: Long, perDay: Long, week: Boolean, bump: () -> Unit) {
    val c = LocalContext.current
    var ask by remember { mutableStateOf(false) }
    val limited = Store.limits(c).any { it.pkg == p }
    val sug = (((perDay / 60000L) / 2).toInt() / 5 * 5).coerceIn(15, 240)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(p, 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(AppRepo.label(p), color = Pal.ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Bar(if (top > 0) value.toFloat() / top else 0f, Pal.accent, 5.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtDur(value), color = Pal.ink, fontWeight = FontWeight.Bold)
            if (week) Sub("${fmtDur(perDay)}/day")
        }
        if (limited) { Spacer(Modifier.width(8.dp)); Chip("Limited", Pal.green) }
        else if (perDay >= 20 * 60000L) { Spacer(Modifier.width(8.dp)); Pill("Limit", false) { ask = true } }
    }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false }, containerColor = Pal.card,
        title = { Text("Limit ${AppRepo.label(p)} to $sug min a day?", color = Pal.ink) },
        text = { Text("That's about half of what you use it now. Limits are locked for 7 days: you can lower them but not raise or remove them.", color = Pal.muted) },
        confirmButton = { TextButton(onClick = { Store.addLimit(c, p, sug); ask = false; toast(c, "Limit set"); bump() }) { Text("Set limit") } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Not now") } }
    )
}

fun LazyListScope.insightsItems(c: Context, data: Insights?, range: Int, onRange: (Int) -> Unit, sel: Int, onSel: (Int) -> Unit, bump: () -> Unit) {
    if (!Compat.usageOk(c)) {
        item {
            Surf {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip("📊", Pal.accent2)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Title("Allow usage access")
                        Sub("Android keeps a record of how long each app is open. LifeLock reads it on your phone to show your biggest time sinks. Nothing leaves your device.")
                    }
                }
                Btn("Grant usage access", Modifier.padding(top = 14.dp)) { Perm.open(c, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
        }
        return
    }
    if (data == null) {
        item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pal.accent) } }
        return
    }
    val tot = data.perDay.map { it.values.sum() }
    val week = HashMap<String, Long>()
    data.perDay.forEach { m -> m.forEach { (k, v) -> week[k] = (week[k] ?: 0L) + v } }
    if (tot.sum() == 0L) {
        item { EmptyState("📊", "No usage data yet", "Use your phone for a bit, then come back. Android only records what happened after access was granted.") }
        return
    }
    val s = sel.coerceIn(0, 6)
    val dayTot = tot[s]
    val dayName = if (s == 6) "Today" else LocalDate.ofEpochDay(data.days[s]).format(DateTimeFormatter.ofPattern("EEE d MMM"))
    val others = tot.filterIndexed { i, v -> i != s && v > 0L }
    val avg = if (others.isEmpty()) 0L else others.sum() / others.size

    item {
        Surf(brush = Brush.linearGradient(listOf(Color(0xFF6D4DF6), Color(0xFF3F7CF5)))) {
            Text(dayName.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = Color.White.copy(alpha = 0.75f))
            Text(fmtDur(dayTot), fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            val pct = if (avg > 0) Math.abs(dayTot - avg) * 100 / avg else 0L
            Text(if (avg == 0L) "Not enough other days to compare" else if (dayTot <= avg) "↓ $pct% below your average" else "↑ $pct% above your average",
                fontSize = 14.sp, color = Color.White.copy(alpha = 0.9f))
            Spacer(Modifier.height(8.dp))
            Text("🔓 ${data.unlocks[s]} unlocks", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
    item {
        Surf {
            Title("Last 7 days")
            Sub("Tap a day to see what you used.", Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(12.dp))
            val mx = tot.max().coerceAtLeast(1L)
            Row(Modifier.fillMaxWidth().height(160.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                for (d in 0..6) {
                    val f = tot[d].toFloat() / mx
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                            .background(if (d == s) Pal.accent.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { onSel(d); onRange(0) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom
                    ) {
                        Text(if (tot[d] > 0) fmtHrs(tot[d]) else "", fontSize = 10.sp, color = if (d == s) Pal.ink else Pal.muted)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth(0.6f).height((92 * f).dp.coerceAtLeast(3.dp)).clip(RoundedCornerShape(8.dp))
                            .background(if (d == s) Pal.accent else Pal.accent.copy(alpha = 0.4f)))
                        Spacer(Modifier.height(5.dp))
                        Text(LocalDate.ofEpochDay(data.days[d]).dayOfWeek.name.take(1), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (d == s) Pal.ink else Pal.muted)
                    }
                }
            }
        }
    }
    val wTop = week.maxByOrNull { it.value }
    if (wTop != null) item {
        Surf {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconChip("💡", Pal.orange)
                Spacer(Modifier.width(14.dp))
                Column {
                    Title("Your biggest time sink this week")
                    Sub("${AppRepo.label(wTop.key)} takes ${wTop.value * 100 / week.values.sum()}% of your week, about ${fmtDur(wTop.value / 7)} a day.")
                }
            }
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(dayName, range == 0) { onRange(0) }
            Pill("This week", range == 1) { onRange(1) }
        }
    }
    val src: Map<String, Long> = if (range == 0) data.perDay[s] else week
    val rows = src.entries.sortedByDescending { it.value }.take(10)
    if (rows.isEmpty()) item { Sub("Nothing recorded for ${if (s == 6) "today" else dayName} yet.", Modifier.padding(4.dp)) }
    else item {
        Surf {
            val top = rows.first().value
            rows.forEachIndexed { idx, e ->
                if (idx > 0) Divider1()
                SinkRow(e.key, e.value, top, if (range == 0) e.value else e.value / 7, range == 1, bump)
            }
        }
    }
}
