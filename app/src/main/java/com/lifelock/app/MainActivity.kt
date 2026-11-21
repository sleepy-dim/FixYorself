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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {
    private val startKey = mutableIntStateOf(0)
    private val launch = mutableStateOf<String?>(null)
    private val visible = mutableStateOf(false)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Pal.mode = Store.theme(this)
        Pal.sysDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        launch.value = intent?.getStringExtra("go")
        ReminderReceiver.ensure(this)
        setContent { LifeTheme { Root(startKey.intValue, visible.value, launch.value) { launch.value = null } } }
    }

    override fun onStart() {
        super.onStart()
        Pal.mode = Store.theme(this)
        Compat.sync(this)
        visible.value = true
        startKey.intValue++
    }

    /** Free the app-icon cache while in the background; the blocker keeps running without it. */
    override fun onStop() {
        super.onStop()
        visible.value = false
        AppRepo.clear()
        LifeWidget.refresh(this)
    }

    /** Widget and Quick Settings tile taps arrive here (singleTask) with a "go" extra. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launch.value = intent.getStringExtra("go")
    }
}

sealed interface Page {
    object NewGoal : Page
    object NewLimit : Page
    class LimitEdit(val pkg: String) : Page
    object FocusSetup : Page
    class SchedEdit(val id: String?) : Page
    class QuietEdit(val id: String?) : Page
    class HabitEdit(val id: String?) : Page
    class TargetEdit(val id: String?) : Page
}

@Composable
fun Root(startKey: Int, visible: Boolean, launch: String?, consumed: () -> Unit) {
    val c = LocalContext.current
    var ver by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tab by remember { mutableIntStateOf(0) }
    var sub by remember { mutableIntStateOf(0) }
    var lsub by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<Page?>(null) }
    var onb by remember {
        mutableStateOf(!Store.onboarded(c) && Store.goals(c).isEmpty() && Store.habits(c).isEmpty() &&
            Store.limits(c).isEmpty() && Store.scheds(c).isEmpty() && Store.tracks(c).isEmpty())
    }
    LaunchedEffect(launch) {
        if (launch == "focus") { page = Page.FocusSetup; consumed() }
        else if (launch == "targets") { page = null; tab = 1; sub = 1; consumed() }
    }

    // Runs every time the app comes to the front: refresh day rollover, icons and permission status.
    LaunchedEffect(startKey) {
        Store.rollover(c)
        AppRepo.load(c)
        withContext(Dispatchers.Default) { Store.syncUsage(c) }
        ver++
    }
    // Slow clock. Runs ONLY while the app is on screen (it used to keep ticking in the background).
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        now = System.currentTimeMillis()
        var day = Store.today()
        while (true) {
            delay(if (Store.focusUntil(c) > System.currentTimeMillis()) 1000L else 30000L)
            now = System.currentTimeMillis()
            if (Store.today() != day) { day = Store.today(); Store.rollover(c); ver++ }
        }
    }
    // Status-bar icons follow the theme.
    val dark = Pal.dark
    LaunchedEffect(dark) {
        (c as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
    }

    val bump: () -> Unit = { ver++ }
    val close: () -> Unit = { page = null; ver++ }
    val go: (Page) -> Unit = { page = it }

    Box(Modifier.fillMaxSize().background(Pal.bg)) {
        Box(Modifier.fillMaxWidth().height(420.dp).background(Brush.verticalGradient(listOf(Pal.glow, Color.Transparent))))
        val p = page
        if (onb) {
            Onboarding(now, ver) { Store.setOnboarded(c); onb = false }
        } else if (p != null) {
            when (p) {
                is Page.NewGoal -> GoalEditor(close)
                is Page.NewLimit -> LimitEditor(null, close)
                is Page.LimitEdit -> LimitEditor(p.pkg, close)
                is Page.FocusSetup -> FocusEditor(close)
                is Page.SchedEdit -> SchedEditor(p.id, close)
                is Page.QuietEdit -> QuietEditor(p.id, close)
                is Page.HabitEdit -> HabitEditor(p.id, close)
                is Page.TargetEdit -> TargetEditor(p.id, close)
            }
        } else {
            val tabs: List<Pair<String, ImageVector>> = listOf(
                "Today" to Icons.Default.Home, "Progress" to Icons.Default.CheckCircle, "Limits" to Icons.Default.Lock,
                "Schedules" to Icons.Default.DateRange, "Settings" to Icons.Default.Settings)
            val fab: Pair<String, Page>? = when (tab) {
                0 -> "New goal" to Page.NewGoal
                1 -> if (sub == 0) "New habit" to Page.HabitEdit(null) else "New target" to Page.TargetEdit(null)
                2 -> if (lsub == 0) "Limit an app" to Page.NewLimit else null
                3 -> "New schedule" to Page.SchedEdit(null)
                else -> null
            }
            Scaffold(
                containerColor = Color.Transparent,
                floatingActionButton = {
                    if (fab != null) ExtendedFloatingActionButton(
                        onClick = { page = fab.second },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text(fab.first, fontWeight = FontWeight.SemiBold) },
                        containerColor = Pal.accent, contentColor = Color.White, shape = RoundedCornerShape(20.dp)
                    )
                },
                bottomBar = {
                    Column {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.line))
                        NavigationBar(containerColor = Pal.nav, tonalElevation = 0.dp) {
                            tabs.forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(
                                    selected = tab == i, onClick = { tab = i },
                                    icon = { Icon(icon, null) },
                                    label = { Text(label, fontSize = 11.sp, maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Pal.accent, selectedTextColor = Pal.ink,
                                        indicatorColor = Pal.accent.copy(alpha = 0.18f),
                                        unselectedIconColor = Pal.muted, unselectedTextColor = Pal.muted)
                                )
                            }
                        }
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (tab) {
                        0 -> TodayScreen(ver, now, go, bump)
                        1 -> HabitsScreen(ver, now, sub, { sub = it }, go, bump)
                        2 -> LimitsScreen(ver, now, lsub, { lsub = it }, go, bump)
                        3 -> SchedulesScreen(ver, now, go, bump)
                        else -> SettingsScreen(ver, now, go, bump)
                    }
                }
            }
        }
    }
}
