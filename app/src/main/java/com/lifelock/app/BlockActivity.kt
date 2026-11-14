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
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat

class BlockActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show()
    }

    private fun show() {
        val pkg = intent.getStringExtra("pkg") ?: ""
        val kind = intent.getStringExtra("kind") ?: ""
        val msg = intent.getStringExtra("msg") ?: ""
        val key = intent.getStringExtra("key")
        setContent {
            LifeTheme {
                BlockScreen(pkg, kind, msg, key, { goHome() }, {
                    packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                    finish()
                })
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

@Composable
fun BlockScreen(pkg: String, kind: String, msg: String, key: String?, onHome: () -> Unit, onOpen: () -> Unit) {
    val c = LocalContext.current
    val pm = c.packageManager
    val name = remember(pkg) { try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg } }
    val icon = remember(pkg) { try { pm.getApplicationIcon(pkg).toBitmap(180, 180).asImageBitmap() } catch (e: Exception) { null } }
    var wait by remember { mutableIntStateOf(10) }
    val canPass = key != null && Store.passesLeft(c) > 0
    LaunchedEffect(Unit) { while (wait > 0) { delay(1000); wait-- } }
    BackHandler { onHome() }
    val why = Store.why(c)
    val tint = when (kind) { "focus" -> Pal.accent; "goal" -> Pal.accent; "schedule" -> Pal.accent2; else -> Pal.orange }
    val label = when (kind) { "focus" -> "FOCUS SESSION"; "goal" -> "GOAL"; "schedule" -> "SCHEDULE"; else -> "DAILY LIMIT" }
    val inf = rememberInfiniteTransition(label = "pulse")
    val s by inf.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "s")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2B1858), Pal.bg)))) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(150.dp).graphicsLayer { scaleX = s; scaleY = s }, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(tint.copy(alpha = 0.14f)))
                Box(Modifier.size(118.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)))
                if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)))
            }
            Spacer(Modifier.height(24.dp))
            Chip(label, tint)
            Spacer(Modifier.height(14.dp))
            Text("$name is locked", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(msg, fontSize = 16.sp, lineHeight = 23.sp, color = Pal.text2, textAlign = TextAlign.Center)
            if (why.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("“$why”", fontSize = 18.sp, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.07f)).padding(16.dp))
            }
            Spacer(Modifier.height(32.dp))
            Btn("Go home") { onHome() }
            if (kind == "limit" && canPass) {
                Btn(if (wait > 0) "Think about it… ${wait}s" else "Use a pass (+${Store.passMin(c)} min) · ${Store.passesLeft(c)} left",
                    Modifier.padding(top = 10.dp), filled = false, enabled = wait == 0) {
                    if (key != null && Store.usePass(c, key)) onOpen()
                }
            }
            Text("App ID: $pkg", fontSize = 11.sp, color = Pal.muted, modifier = Modifier.padding(top = 20.dp))
        }
    }
}
