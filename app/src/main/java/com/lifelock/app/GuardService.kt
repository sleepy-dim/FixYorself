package com.lifelock.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import java.time.LocalDateTime

class GuardService : AccessibilityService() {
    companion object {
        @Volatile var inst: GuardService? = null
        @Volatile var events = 0
        @Volatile var ticks = 0
        @Volatile var startedAt = 0L
    }

    private val h = Handler(Looper.getMainLooper())
    private var fg = ""
    private var last = System.currentTimeMillis()
    private var lastBlock = 0L
    private var lastChk = 0L
    private var sysAt = 0L
    private var syncPkg = ""
    private var syncAt = 0L
    private var sysOk = false
    private var blockWin = 0L
    private var blockN = 0
    private var essAt = 0L
    private var ess: Set<String> = emptySet()
    private val launchable = HashMap<String, Boolean>()
    private var screenOn = true
    private var rxOn = false
    /** All reads of Android's usage records happen here, never on the thread that draws the screen. */
    private val bg = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * No fixed polling. The timer is only set for the exact moment something can change:
     * a schedule starting or ending, a focus session ending, or a time allowance running out
     * for the app in front (capped at 5 min so usage is saved regularly). Never runs with the screen off.
     */
    private val tick = Runnable {
        ticks++
        Store.bump(this, "ticks")
        if (sysUsage() && fg.isNotEmpty() && screenOn) {
            syncFg(fg, true) { check(fg); Store.flushUsage(this, true); planNext() }
        } else {
            accrue()
            check(fg)
            Store.flushUsage(this, true)
            planNext()
        }
    }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_OFF) {
                accrue(); Store.flushUsage(this@GuardService, true); screenOn = false; h.removeCallbacks(tick)
            } else if (i?.action == Intent.ACTION_SCREEN_ON) {
                screenOn = true; last = System.currentTimeMillis()
                h.postDelayed({ if (sysUsage()) syncFg(fg, true) { check(fg); planNext() } else check(fg) }, 600L)   // catches schedules that started while the screen was off
                planNext()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
        events = 0; ticks = 0; startedAt = System.currentTimeMillis()
        Store.setPaused(this, "")
        WatchService.cancel()
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        last = System.currentTimeMillis()
        if (!rxOn) {
            ContextCompat.registerReceiver(this, screenRx,
                IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
                ContextCompat.RECEIVER_NOT_EXPORTED)
            rxOn = true
        }
        bg.execute {
            try { Store.rollover(this) } catch (e: Exception) { }
            val f = Compat.lastForeground(this)
            h.post {
                if (fg.isEmpty()) fg = f
                if (fg.isNotEmpty()) {
                    if (sysUsage()) syncFg(fg, true) { check(fg); planNext() } else { check(fg); planNext() }
                }
            }
        }
        planNext()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p != packageName && !isUserApp(p)) return   // system overlays never count as "the current app"
        events++
        Store.bump(this, "events")
        if (!Store.hasRules(this)) { fg = p; return }
        if (p != fg) {
            accrue(); Store.flushUsage(this); fg = p
            if (sysUsage()) syncFg(p, false) { if (fg == p) { check(p); planNext() } }
            check(p)
            planNext()
        } else if (System.currentTimeMillis() - lastChk > 3000L) {
            check(p)   // same app: keyboards, popups and dialogs also fire events, so re-check at most every 3 s
        }
    }

    override fun onInterrupt() {}

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) AppRepo.clear()
    }

    override fun onDestroy() {
        Store.flushUsage(this, true)
        bg.shutdown()
        h.removeCallbacks(tick)
        if (rxOn) { try { unregisterReceiver(screenRx) } catch (e: Exception) { }; rxOn = false }
        inst = null
        super.onDestroy()
    }

    private fun planNext() {
        h.removeCallbacks(tick)
        if (!screenOn) return
        val now = LocalDateTime.now()
        var wait = Store.nextChangeMs(this, now)
        if (fg.isNotEmpty()) {
            val rem = Store.remainingMs(this, fg, now)
            if (rem != Long.MAX_VALUE) wait = minOf(wait, if (sysUsage()) rem else rem.coerceAtMost(300000L))   // with Android's numbers one exact timer is enough
        }
        if (wait == Long.MAX_VALUE) return                      // nothing time-based: stay completely idle
        h.postDelayed(tick, wait.coerceAtLeast(1500L) + 300L)
    }

    /** Adds the time since the last check to whichever app was in front. */
    /** True when Usage access is granted, so Android's own numbers are used (cached for a minute). */
    private fun sysUsage(): Boolean {
        val n = System.currentTimeMillis()
        if (n - sysAt > 60000L) { sysOk = Compat.usageOk(this); sysAt = n }
        return sysOk
    }

    /** Reads Android's usage numbers for [p]; skipped if it was just done (switching in and out of an app is common). */
    private fun syncFg(p: String, force: Boolean, then: () -> Unit = {}) {
        val n = System.currentTimeMillis()
        if (!force && p == syncPkg && n - syncAt < 20000L) return
        syncPkg = p; syncAt = n
        bg.execute {
            try { Store.syncUsage(this, p) } catch (e: Exception) { }
            h.post(then)
        }
    }

    private fun accrue() {
        val now = System.currentTimeMillis()
        val dt = (now - last).coerceIn(0L, 900000L)
        last = now
        if (fg.isEmpty() || !screenOn) return
        if (sysUsage()) syncFg(fg, true)          // Android measured it: no counting of our own
        else Store.trackKeys(this, fg, LocalDateTime.now()).forEach { Store.addUsed(this, it, dt) }
    }

    private fun isUserApp(p: String) = launchable.getOrPut(p) { packageManager.getLaunchIntentForPackage(p) != null }

    /** Called by compatibility mode: remember we're paused, then switch this service off. */
    fun pauseFor(p: String) {
        WatchService.begin(p)
        disableSelf()
    }

    private fun check(p: String) {
        lastChk = System.currentTimeMillis()
        if (p.isEmpty() || p == packageName || !isUserApp(p)) return
        val r = Store.blockReason(this, p, essentials(), Store.focusApps(this), LocalDateTime.now())
        if (r == null) {
            if (p in Store.pauseApps(this) && Compat.ready(this)) pauseFor(p)
            return
        }
        val now = System.currentTimeMillis()
        if (now - blockWin > 30000L) { blockWin = now; blockN = 0 }
        if (now - lastBlock < (if (blockN >= 4) 8000L else 1500L)) return   // back off if something keeps reopening a blocked app
        lastBlock = now
        blockN++
        Store.bump(this, "blocks")
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({
            startActivity(Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("pkg", p).putExtra("kind", r.kind).putExtra("msg", r.msg).putExtra("key", r.passKey))
        }, 250)
    }

    /** Never blocked by focus / allow-only schedules: home screen, Google app, calls, SMS, keyboards, Samsung system apps. */
    private fun essentials(): Set<String> {
        val n = System.currentTimeMillis()
        if (n - essAt < 60000 && ess.isNotEmpty()) return ess
        val s = mutableSetOf(packageName, "android", "com.android.systemui",
            "com.google.android.googlequicksearchbox", "com.google.android.apps.nexuslauncher",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.android.phone", "com.android.server.telecom", "com.android.incallui",
            "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            "com.sec.android.app.launcher", "com.samsung.android.app.spage", "com.samsung.android.app.cocktailbarservice",
            "com.samsung.android.app.taskedge", "com.samsung.android.bixby.agent", "com.samsung.android.bixby.service",
            "com.samsung.android.app.contacts", "com.samsung.android.contacts", "com.samsung.android.app.aodservice",
            "com.sec.android.app.desktoplauncher", "com.samsung.android.app.telephonyui")
        getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { s.add(it) }
        Telephony.Sms.getDefaultSmsPackage(this)?.let { s.add(it) }
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { s.add(it.packageName) }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).forEach { s.add(it.activityInfo.packageName) }
        ess = s; essAt = n
        return s
    }
}
