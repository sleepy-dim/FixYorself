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
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object Pal {
    var mode by mutableIntStateOf(1)          // 1 Default (navy), 4 Dark (black + purple glow), 2 AMOLED (pure black), 3 Light, 0 System
    var sysDark by mutableStateOf(true)
    val dark: Boolean get() = when (mode) { 1, 2, 4 -> true; 3 -> false; else -> sysDark }

    val bg: Color get() = if (!dark) Color(0xFFF3F4FA) else if (mode == 2 || mode == 4) Color.Black else Color(0xFF0A0B10)
    val nav: Color get() = if (!dark) Color(0xF2FFFFFF) else if (mode == 2) Color.Black else if (mode == 4) Color(0xF2000000) else Color(0xF20D0F16)
    val glow: Color get() = if (!dark) Color(0xFFDAD3FF) else if (mode == 2) Color.Transparent else if (mode == 4) Color(0xFF140E33) else Color(0xFF2A1F63)
    val card: Color get() = if (!dark) Color.White else if (mode == 2) Color(0xFF060607) else if (mode == 4) Color(0xFF0D0E13) else Color(0xFF14161F)
    val card2: Color get() = if (!dark) Color(0xFFECEEF6) else if (mode == 2) Color(0xFF111113) else if (mode == 4) Color(0xFF181A22) else Color(0xFF1D202C)
    val line: Color get() = if (!dark) Color(0x16000000) else if (mode == 2) Color(0x33FFFFFF) else Color(0x1FFFFFFF)
    val muted: Color get() = if (!dark) Color(0xFF6B7287) else Color(0xFF8D94A8)
    val text2: Color get() = if (!dark) Color(0xFF3A4054) else Color(0xFFC7CCDA)
    val ink: Color get() = if (!dark) Color(0xFF12141C) else Color.White

    val accent = Color(0xFF7C5CFF)
    val accent2 = Color(0xFF4F8CFF)
    val green = Color(0xFF2DD4A0)
    val red = Color(0xFFFF6B6B)
    val orange = Color(0xFFFFA24C)
}

@Composable
fun LifeTheme(content: @Composable () -> Unit) {
    val sys = isSystemInDarkTheme()
    SideEffect { if (Pal.sysDark != sys) Pal.sysDark = sys }
    val scheme = if (Pal.dark) darkColorScheme(
        primary = Pal.accent, onPrimary = Color.White, secondary = Pal.green, error = Pal.red,
        background = Pal.bg, onBackground = Pal.ink, surface = Pal.card, onSurface = Pal.ink,
        surfaceVariant = Pal.card2, onSurfaceVariant = Pal.muted, outline = Color(0xFF353A4D), outlineVariant = Pal.line)
    else lightColorScheme(
        primary = Pal.accent, onPrimary = Color.White, secondary = Pal.green, error = Pal.red,
        background = Pal.bg, onBackground = Pal.ink, surface = Pal.card, onSurface = Pal.ink,
        surfaceVariant = Pal.card2, onSurfaceVariant = Pal.muted, outline = Color(0xFFB9BECE), outlineVariant = Pal.line)
    MaterialTheme(colorScheme = scheme, content = content)
}

fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()

// ---------------------------------------------------------------- building blocks
@Composable
fun Surf(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, brush: Brush? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    var m = modifier.fillMaxWidth().clip(shape)
    m = if (brush != null) m.background(brush) else m.background(Pal.card).border(1.dp, Pal.line, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(18.dp), content = content)
}

@Composable
fun Title(t: String, color: Color = Pal.ink, size: Int = 17) =
    Text(t, fontSize = size.sp, fontWeight = FontWeight.SemiBold, color = color)

@Composable
fun Sub(t: String, mod: Modifier = Modifier, color: Color = Pal.muted) =
    Text(t, fontSize = 13.sp, lineHeight = 18.sp, color = color, modifier = mod)

@Composable
fun SectionLabel(t: String) =
    Text(t.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = Pal.muted,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp))

@Composable
fun ScreenTitle(t: String, sub: String? = null) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(t, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Pal.ink)
        if (sub != null) Sub(sub, Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Chip(t: String, color: Color = Pal.accent) =
    Text(t, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 4.dp))

@Composable
fun IconChip(emoji: String, tint: Color = Pal.accent, size: Dp = 46.dp) =
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size.value * 0.48f).sp)
    }

@Composable
fun Divider1() = Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.line))

@Composable
fun Btn(t: String, modifier: Modifier = Modifier, filled: Boolean = true, color: Color = Pal.accent,
        enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    var m = modifier.fillMaxWidth().height(52.dp).clip(shape)
    m = if (filled) {
        val to = lerp(color, Pal.accent2, if (color == Pal.accent) 1f else 0.25f)
        m.background(Brush.horizontalGradient(if (enabled) listOf(color, to) else listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.35f))))
    } else m.background(Pal.card2).border(1.dp, Pal.line, shape)
    Box(m.clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        val tc = if (filled) Color.White else Pal.ink
        Text(t, color = if (enabled) tc else tc.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
fun ModeCard(emoji: String, title: String, desc: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(if (on) Pal.accent.copy(alpha = 0.14f) else Pal.card2)
        .border(1.5.dp, if (on) Pal.accent else Pal.line, shape).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconChip(emoji, if (on) Pal.accent else Pal.muted, 42.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Pal.ink, fontWeight = FontWeight.Bold)
            Sub(desc)
        }
        if (on) Text("✓", color = Pal.accent, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
    }
}

/** A delete button that asks first. */
@Composable
fun ConfirmBtn(t: String, onConfirm: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    Btn(t, filled = false) { ask = true }
    if (ask) AlertDialog(
        onDismissRequest = { ask = false }, containerColor = Pal.card,
        title = { Text("$t?", color = Pal.ink) },
        text = { Text("This can't be undone.", color = Pal.muted) },
        confirmButton = { TextButton(onClick = { ask = false; onConfirm() }) { Text("Yes, delete", color = Pal.red) } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } }
    )
}

@Composable
fun Pill(t: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(t, color = if (on) Color.White else Pal.text2, fontSize = 14.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier.clip(shape).background(if (on) Pal.accent else Pal.card2)
            .border(1.dp, if (on) Color.Transparent else Pal.line, shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp))
}

@Composable
fun DayChips(mask: Int, onChange: (Int) -> Unit) {
    val names = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        names.forEachIndexed { i, n ->
            val on = (mask and (1 shl i)) != 0
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (on) Pal.accent else Pal.card2)
                .border(1.dp, if (on) Color.Transparent else Pal.line, CircleShape)
                .clickable { onChange(mask xor (1 shl i)) }, contentAlignment = Alignment.Center) {
                Text(n, color = if (on) Color.White else Pal.muted, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun DayDots(mask: Int, color: Color = Pal.accent) {
    val names = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, n ->
            val on = (mask and (1 shl i)) != 0
            Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) color.copy(alpha = 0.9f) else Pal.card2), contentAlignment = Alignment.Center) {
                Text(n, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else Pal.muted)
            }
        }
    }
}

@Composable
fun TimeBtn(label: String, min: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val c = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(Pal.card2).border(1.dp, Pal.line, shape)
        .clickable { TimePickerDialog(c, { _, hh, mm -> onPick(hh * 60 + mm) }, min / 60, min % 60, true).show() }
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Pal.muted)
        Text(hhmm(min), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Pal.ink)
    }
}

@Composable
fun RoundBtn(t: String, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(Pal.card2).border(1.dp, Pal.line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(t, color = Pal.ink, fontSize = 20.sp) }
}

@Composable
fun Stepper(label: String, value: Int, min: Int, max: Int, suffix: String = "", step: Int = 1, zero: String = "None", onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Pal.text2, modifier = Modifier.weight(1f))
        RoundBtn("−") { onChange((value - step).coerceAtLeast(min)) }
        Text(if (value == 0 && suffix.isNotEmpty()) zero else "$value$suffix", color = Pal.ink, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.width(84.dp))
        RoundBtn("+") { onChange((value + step).coerceAtMost(max)) }
    }
}

@Composable
fun Ring(progress: Float, dia: Dp, stroke: Dp = 10.dp, color: Color = Pal.accent, track: Color = Pal.card2,
         content: @Composable BoxScope.() -> Unit = {}) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(800), label = "ring")
    Box(Modifier.size(dia), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val arc = Size(size.width - w, size.height - w)
            val tl = Offset(w / 2, w / 2)
            drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = tl, size = arc, style = Stroke(width = w))
            if (p > 0.001f) drawArc(color = color, startAngle = -90f, sweepAngle = 360f * p, useCenter = false, topLeft = tl, size = arc,
                style = Stroke(width = w, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
fun Bar(p: Float, color: Color = Pal.accent, h: Dp = 8.dp) {
    val a by animateFloatAsState(p.coerceIn(0f, 1f), tween(600), label = "bar")
    Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(h)).background(Pal.card2)) {
        Box(Modifier.fillMaxWidth(a.coerceAtLeast(0.001f)).fillMaxHeight().clip(RoundedCornerShape(h))
            .background(Brush.horizontalGradient(listOf(color, lerp(color, Color.White, 0.3f)))))
    }
}

@Composable
fun CircleCheck(on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).background(if (on) Pal.green else Color.Transparent)
        .border(2.dp, if (on) Pal.green else Pal.muted.copy(alpha = 0.5f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { if (on) Text("✓", color = Color.White, fontWeight = FontWeight.ExtraBold) }
}

@Composable
fun RoundAction(t: String, onClick: () -> Unit) {
    Text(t, color = Pal.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Pal.accent.copy(alpha = 0.16f)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp))
}

@Composable
fun EmptyState(emoji: String, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 46.sp)
        Spacer(Modifier.height(10.dp))
        Title(title)
        Spacer(Modifier.height(4.dp))
        Text(body, color = Pal.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp))
    }
}

@Composable
fun EditorPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Pal.ink) }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Pal.ink)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

// ---------------------------------------------------------------- installed apps (icons + names)
class AppInfo(val label: String, val pkg: String, val icon: ImageBitmap)

object AppRepo {
    private var list: List<AppInfo>? = null
    private val map = ConcurrentHashMap<String, AppInfo>()
    fun get(p: String): AppInfo? = map[p]
    fun label(p: String): String = map[p]?.label ?: p
    fun clear() { list = null; map.clear() }

    suspend fun load(c: Context): List<AppInfo> = withContext(Dispatchers.Default) {
        val cached = list
        if (cached != null) cached else {
            val pm = c.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val r = pm.queryIntentActivities(i, 0)
                .filter { it.activityInfo.packageName != c.packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm).toBitmap(120, 120).asImageBitmap()) }
                .sortedBy { it.label.lowercase() }
            r.forEach { map[it.pkg] = it }
            list = r
            r
        }
    }
}

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val info = AppRepo.get(pkg)
    if (info != null) {
        Image(bitmap = info.icon, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(Pal.card2), contentAlignment = Alignment.Center) {
            Text(pkg.substringAfterLast('.').take(1).uppercase(), color = Pal.ink, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AppsRow(pkgs: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), verticalAlignment = Alignment.CenterVertically) {
        pkgs.take(6).forEach { p ->
            Box(Modifier.border(2.dp, Pal.card, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))) { AppIcon(p, 30.dp) }
        }
        if (pkgs.size > 6) Text("  +${pkgs.size - 6}", color = Pal.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AppPicker(title: String, multi: Boolean, initial: Set<String>, onDone: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current
    val apps by produceState<List<AppInfo>?>(null) { value = AppRepo.load(c) }
    var q by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Pal.bg) {
            Column {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = Pal.ink) }
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Pal.ink, modifier = Modifier.weight(1f))
                    if (multi) Text("Done (${sel.size})", color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(Pal.accent)
                            .clickable { onDone(sel) }.padding(horizontal = 16.dp, vertical = 9.dp))
                }
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pal.accent, unfocusedBorderColor = Pal.line,
                        focusedContainerColor = Pal.card, unfocusedContainerColor = Pal.card),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                )
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Pal.accent) }
                } else {
                    val shown = list.filter { it.label.contains(q.trim(), ignoreCase = true) }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                        items(shown, key = { it.pkg }) { a ->
                            val on = a.pkg in sel
                            val shape = RoundedCornerShape(16.dp)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape)
                                    .background(if (on) Pal.accent.copy(alpha = 0.14f) else Pal.card)
                                    .border(1.dp, if (on) Pal.accent else Pal.line, shape)
                                    .clickable { if (multi) { sel = if (on) sel - a.pkg else sel + a.pkg } else { onDone(setOf(a.pkg)) } }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppIcon(a.pkg, 44.dp)
                                Spacer(Modifier.width(14.dp))
                                Text(a.label, color = Pal.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                if (multi) Checkbox(checked = on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = Pal.accent))
                            }
                        }
                    }
                }
            }
        }
    }
}
