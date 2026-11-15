package com.lifelock.app

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Helpers for "compatibility mode": switching the accessibility service off inside chosen apps and back on afterwards. */
object Compat {
    const val CMD = "adb shell pm grant com.lifelock.app android.permission.WRITE_SECURE_SETTINGS"

    fun secureOk(c: Context) = c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun usageOk(c: Context): Boolean {
        val ao = c.getSystemService(AppOpsManager::class.java)
        return ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun ready(c: Context) = secureOk(c) && usageOk(c) && WatchService.alive

    /** Starts or stops the helper service depending on whether compatibility mode is set up and has apps. */
    fun sync(c: Context) {
        val want = Store.pauseApps(c).isNotEmpty() && secureOk(c) && usageOk(c)
        try {
            if (want) ContextCompat.startForegroundService(c, Intent(c, WatchService::class.java))
            else c.stopService(Intent(c, WatchService::class.java))
        } catch (e: Exception) { }
    }

    /** Turns the blocking service back on (needs the one-time ADB permission). */
    fun enableGuard(c: Context) {
        try {
            val cn = ComponentName(c, GuardService::class.java).flattenToString()
            val cr = c.contentResolver
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (cur.split(':').none { it.startsWith(c.packageName + "/") })
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isBlank()) cn else "$cur:$cn")
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: Exception) { warn(c) }
    }

    /** Last app that came to the foreground in the past hour (needs usage access), or "" if unknown. */
    fun lastForeground(c: Context): String {
        if (!usageOk(c)) return ""
        return try {
            val now = System.currentTimeMillis()
            val ev = c.getSystemService(UsageStatsManager::class.java).queryEvents(now - 3600000L, now)
            val e = UsageEvents.Event()
            var p = ""
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) p = e.packageName ?: p
                else if (e.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) p = ""
            }
            p
        } catch (e: Exception) { "" }
    }

    /** Pauses blocking, then opens the app a moment later so it never sees the accessibility service. */
    fun openPaused(c: Context, p: String) {
        if (!ready(c)) { toast(c, "Finish the compatibility setup first: both permissions and the helper service."); return }
        GuardService.inst?.pauseFor(p)
        Handler(Looper.getMainLooper()).postDelayed({
            c.packageManager.getLaunchIntentForPackage(p)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, 700L)
    }

    private fun warn(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("warn", "Blocking paused", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, 2, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
        nm.notify(8, Notification.Builder(c, "warn").setSmallIcon(R.drawable.ic_stat).setContentTitle("LifeLock blocking is off")
            .setContentText("Tap to turn LifeLock Guard back on.").setContentIntent(pi).setAutoCancel(true).build())
    }
}

/**
 * Idle until blocking is paused. While paused it checks once a second which app is in front
 * and switches the blocker back on as soon as you leave (or the screen turns off).
 */
class WatchService : Service() {
    companion object {
        @Volatile var alive = false
        @Volatile private var inst: WatchService? = null
        fun begin(pkg: String) { inst?.beginPause(pkg) }
        fun cancel() { inst?.cancelPause() }
    }

    private val h = Handler(Looper.getMainLooper())
    private var target = ""
    private var fg = ""
    private var since = 0L
    private var lastQ = 0L
    private var away = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        alive = true; inst = this
        goForeground()
        // If we were killed mid-pause, make sure blocking comes back.
        if (Store.pausedPkg(this).isNotEmpty()) restore()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        if (target.isNotEmpty()) restore()
        alive = false; inst = null
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch", "Compatibility mode", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "watch").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("LifeLock compatibility mode")
            .setContentText("Ready. Blocking pauses only in the apps you chose.")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, n)
    }

    fun beginPause(pkg: String) {
        target = pkg; fg = pkg; away = 0
        since = System.currentTimeMillis(); lastQ = since
        Store.setPaused(this, pkg)
        h.removeCallbacks(poll)
        h.postDelayed(poll, 1200L)
    }

    fun cancelPause() { target = ""; h.removeCallbacks(poll) }

    private fun restore() {
        target = ""
        h.removeCallbacks(poll)
        Store.flushUsage(this, true)
        Store.setPaused(this, "")
        Compat.enableGuard(this)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (target.isEmpty()) return
            val now = System.currentTimeMillis()
            var screenOff = false
            try {
                val ev = getSystemService(UsageStatsManager::class.java).queryEvents(lastQ - 1000L, now)
                val e = UsageEvents.Event()
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e)
                    if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) fg = e.packageName ?: fg
                    else if (e.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) screenOff = true
                }
            } catch (ex: Exception) { }
            lastQ = now
            Store.bump(this@WatchService, "polls")
            away = if (fg != target) away + 1 else 0
            val off = screenOff || !getSystemService(PowerManager::class.java).isInteractive
            if (off || away >= 2 || now - since > 3600000L) { restore(); return }
            h.postDelayed(this, 1500L)
        }
    }
}
