package com.lifelock.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

/** One reminder stream: window [from,to) (from==to means all day), every N minutes, on the given weekday mask. */
data class Src(val key: String, val from: Int, val to: Int, val every: Int, val days: Int)

object Reminders {
    fun sources(c: Context): List<Src> {
        val r = Store.rem(c)
        val l = mutableListOf<Src>()
        if (r.on) l.add(Src("goal", r.from, r.to, r.every, 127))
        Store.habits(c).filter { it.remOn }.forEach { l.add(Src("h_${it.id}", it.remFrom, it.remTo, it.remEvery, it.days)) }
        return l
    }

    private fun inQuiet(q: List<Quiet>, t: LocalDateTime) = q.any { windowDay(it.days, it.start, it.end, t) != null }
    private fun dayOk(s: Src, t: LocalDateTime) = (s.days and (1 shl (t.dayOfWeek.value - 1))) != 0

    fun nextFor(s: Src, q: List<Quiet>, from: LocalDateTime): LocalDateTime? {
        var t = from.plusMinutes(1).withSecond(0).withNano(0)
        for (i in 0 until 8 * 1440) {
            val m = t.hour * 60 + t.minute
            if (dayOk(s, t) && inRange(m, s.from, s.to) && ((m - s.from + 1440) % 1440) % s.every == 0 && !inQuiet(q, t)) return t
            t = t.plusMinutes(1)
        }
        return null
    }

    /** recompute=true after any settings change; false just fills in anything missing or stale. */
    fun scheduleAll(c: Context, recompute: Boolean) {
        val q = Store.quiets(c)
        val now = LocalDateTime.now()
        val nowMs = System.currentTimeMillis()
        var min = Long.MAX_VALUE
        for (s in sources(c)) {
            var nd = Store.nd(c, s.key)
            if (recompute || nd == 0L || nd < nowMs - 300000L) {
                nd = nextFor(s, q, now)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: 0L
                Store.setNd(c, s.key, nd)
            }
            if (nd > 0L && nd < min) min = nd
        }
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, 1, Intent(c, ReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (min == Long.MAX_VALUE) am.cancel(pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, min, pi)
    }

    /** Called when a reminder is due. Re-checks the rules at delivery time, so late alarms never ping you at a bad time. */
    fun fire(c: Context, s: Src, now: LocalDateTime) {
        if (!inRange(now.hour * 60 + now.minute, s.from, s.to) || !dayOk(s, now) || inQuiet(Store.quiets(c), now)) return
        if (s.key == "goal") { send(c); return }
        val h = Store.habits(c).firstOrNull { "h_${it.id}" == s.key } ?: return
        val t = Store.today()
        if (!Store.hActive(h, t)) return
        val v = Store.hLog(c, h.id, t)
        if (h.kind == 2) { if (v == 0) notifyHabit(c, h, v) } else if (!Store.met(h, v)) notifyHabit(c, h, v)
    }

    private fun channel(c: Context, id: String, name: String): NotificationManager {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH))
        return nm
    }

    fun notifId(id: String) = 100 + (id.hashCode() and 0xFFFF)

    fun notifyHabit(c: Context, h: Habit, v: Int) {
        val nm = channel(c, "habits", "Habit reminders")
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val txt = when (h.kind) {
            0 -> "Time to do it."
            1 -> "$v / ${h.target} ${h.unit} so far"
            else -> "Nothing logged yet today — log it."
        }
        val amount = if (h.kind == 0) 1 else h.step
        val act = PendingIntent.getBroadcast(c, h.id.hashCode(),
            Intent(c, HabitActionReceiver::class.java).putExtra("id", h.id).putExtra("d", amount),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val label = if (h.kind == 0) "Done ✓" else "+${h.step} ${h.unit}".trim()
        nm.notify(notifId(h.id), Notification.Builder(c, "habits").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${h.emoji} ${h.name}").setContentText(txt).setContentIntent(open).setAutoCancel(true)
            .addAction(android.R.drawable.ic_input_add, label, act).build())
    }

    fun send(c: Context, test: Boolean = false) {
        val t = Store.today()
        val all = Store.goals(c)
        val pend = all.filter { t < it.end && it.last != t }
        if (pend.isEmpty() && !test) return
        val best = all.maxOfOrNull { Store.liveStreak(it) } ?: 0
        val nm = channel(c, "remind", "Check-in reminders")
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = if (pend.isEmpty()) "Reminders are working ✅" else "Check in to keep your streak 🔥"
        val text = if (pend.isEmpty()) "You're all checked in." else pend.joinToString { it.title } + (if (best > 0) " · $best-day streak at stake" else "")
        nm.notify(1, Notification.Builder(c, "remind").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        Store.rollover(c)
        val nowMs = System.currentTimeMillis()
        val now = LocalDateTime.now()
        for (s in Reminders.sources(c)) {
            val nd = Store.nd(c, s.key)
            if (nd > 0L && nd <= nowMs + 60000L) { Reminders.fire(c, s, now); Store.setNd(c, s.key, 0L) }
        }
        Reminders.scheduleAll(c, false)
    }

    companion object {
        /** Call after any reminder/habit/quiet-time change. */
        fun schedule(c: Context) = Reminders.scheduleAll(c, true)
        /** Call on app start / boot. */
        fun ensure(c: Context) = Reminders.scheduleAll(c, false)
    }
}

/** Handles the "Done" / "+250 ml" button on a habit notification. */
class HabitActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        val intent = i ?: return
        val id = intent.getStringExtra("id") ?: return
        Store.hAdd(c, id, intent.getIntExtra("d", 1))
        c.getSystemService(NotificationManager::class.java).cancel(Reminders.notifId(id))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        ReminderReceiver.ensure(c)
        Compat.sync(c)
    }
}
