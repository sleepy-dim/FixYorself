package com.lifelock.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import java.time.LocalDateTime

class GuardService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var fg = ""
    private var last = System.currentTimeMillis()
    private var lastBlock = 0L
    private var essAt = 0L
    private var ess: Set<String> = emptySet()
    private val launchable = HashMap<String, Boolean>()

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = (now - last).coerceIn(0L, 10000L)
            last = now
            val pm = getSystemService(PowerManager::class.java)
            if (fg.isNotEmpty() && pm.isInteractive)
                Store.trackKeys(this@GuardService, fg, LocalDateTime.now()).forEach { Store.addUsed(this@GuardService, it, dt) }
            check(fg)
            h.postDelayed(this, 2000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        last = System.currentTimeMillis()
        Store.rollover(this)
        h.removeCallbacks(tick)
        h.post(tick)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p != packageName && !isUserApp(p)) return   // system overlays never count as "the current app"
        fg = p
        check(p)
    }

    override fun onInterrupt() {}
    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }

    /** Only apps with a launcher icon can be blocked. Keyboards, system UI, dialogs etc. are ignored. */
    private fun isUserApp(p: String) = launchable.getOrPut(p) { packageManager.getLaunchIntentForPackage(p) != null }

    private fun check(p: String) {
        if (p.isEmpty() || p == packageName || !isUserApp(p)) return
        val r = Store.blockReason(this, p, essentials(), Store.focusApps(this), LocalDateTime.now()) ?: return
        val now = System.currentTimeMillis()
        if (now - lastBlock < 1500) return
        lastBlock = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({
            startActivity(Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("pkg", p).putExtra("kind", r.kind).putExtra("msg", r.msg).putExtra("key", r.passKey))
        }, 250)
    }

    /** Never blocked by focus / allow-only schedules: home screen, Google app, calls, SMS, keyboards. */
    private fun essentials(): Set<String> {
        val n = System.currentTimeMillis()
        if (n - essAt < 60000 && ess.isNotEmpty()) return ess
        val s = mutableSetOf(packageName, "android", "com.android.systemui",
            "com.google.android.googlequicksearchbox", "com.google.android.apps.nexuslauncher",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.android.phone", "com.android.server.telecom", "com.android.incallui",
            "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.samsung.android.incallui",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            // Samsung One UI: home screen, Samsung Free, edge panel, Bixby, contacts, always-on display, DeX launcher
            "com.sec.android.app.launcher", "com.samsung.android.app.spage", "com.samsung.android.app.cocktailbarservice",
            "com.samsung.android.app.taskedge", "com.samsung.android.bixby.agent", "com.samsung.android.bixby.service",
            "com.samsung.android.app.contacts", "com.samsung.android.contacts", "com.samsung.android.app.aodservice",
            "com.sec.android.app.desktoplauncher", "com.samsung.android.incallui", "com.samsung.android.app.telephonyui")
        getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { s.add(it) }
        Telephony.Sms.getDefaultSmsPackage(this)?.let { s.add(it) }
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { s.add(it.packageName) }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).forEach { s.add(it.activityInfo.packageName) }
        ess = s; essAt = n
        return s
    }
}
