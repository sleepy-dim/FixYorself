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

@Composable
fun OnbPage(emoji: String, title: String, body: String) {
    Text(emoji, fontSize = 64.sp)
    Spacer(Modifier.height(16.dp))
    Text(title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Pal.ink, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(body, fontSize = 16.sp, lineHeight = 24.sp, color = Pal.text2, textAlign = TextAlign.Center)
}

/** Shown once on first launch. Plain language, four short steps, permissions explained at the end. */
@Composable
fun Onboarding(now: Long, ver: Int, onDone: () -> Unit) {
    val c = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    BackHandler(enabled = step > 0) { step-- }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text("Skip", color = Pal.muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onDone() }.padding(8.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            when (step) {
                0 -> OnbPage("🌟", "Welcome to LifeLock", "Build good habits, reach your goals, and keep distracting apps in check. Setup takes about a minute.")
                1 -> OnbPage("🎯", "Track what matters", "Habits: water, walks, workouts.\nTargets: weight, push-ups, savings.\nGoals: “no sweets until next month”.")
                2 -> OnbPage("🔒", "Take back your phone", "Limits: 30 minutes of Instagram a day.\nBlock apps at certain hours.\nSchedules for study or work, and focus sessions.")
                else -> {
                    OnbPage("⚙️", "Turn on blocking", "LifeLock needs a few permissions to block apps. You can skip this and finish later from the Today screen.")
                    Spacer(Modifier.height(16.dp))
                    Surf {
                        SetupRow(Perm.guard(c), "Blocking engine (Accessibility)") { Perm.open(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        SetupRow(Perm.overlay(c), "Show the lock screen over apps") {
                            Perm.open(c, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")))
                        }
                        SetupRow(Perm.notif(c), "Notifications") { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        SetupRow(Compat.usageOk(c), "Usage access (exact time limits, optional)") {
                            Perm.open(c, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                        Sub("If Accessibility is greyed out: App info → ⋮ menu → Allow restricted settings.", Modifier.padding(top = 14.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            for (i in 0..3) Box(Modifier.padding(4.dp).size(if (i == step) 22.dp else 8.dp, 8.dp).clip(CircleShape)
                .background(if (i == step) Pal.accent else Pal.card2))
        }
        Btn(if (step < 3) "Next" else "Get started") { if (step < 3) step++ else onDone() }
        if (step > 0) Btn("Back", Modifier.padding(top = 10.dp), filled = false) { step-- }
    }
}
