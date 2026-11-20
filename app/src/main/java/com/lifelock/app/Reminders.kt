package com.lifelock.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** One reminder stream: window [from,to) (from==to means all day), every N minutes, on the given weekday mask. */
data class Src(val key: String, val from: Int, val to: Int, val every: Int, val days: Int)

object Reminders {
    @Volatile private var cur: Src? = null
    private fun logN(c: Context, what: String) {
        val n = LocalDateTime.now()
        val s = cur
        Store.logNotif(c, "%02d:%02d  %s".format(n.hour, n.minute, what) +
            (if (s != null) "  [set for ${hhmm(s.from)}–${hhmm(s.to)}, every ${s.every} min]" else ""))
    }
    fun sources(c: Context): List<Src> {
        val r = Store.rem(c)
        val l = mutableListOf<Src>()
        if (r.on) l.add(Src("goal", r.from, r.to, r.every, 127))
        Store.habits(c).filter { it.remOn }.forEach { h ->
            l.add(Src("h_${h.id}", h.remFrom, h.remTo, h.remEvery, if (h.remDays < 0) h.days else h.remDays))
            h.extra.forEachIndexed { i, x -> l.add(Src("h_${h.id}#$i", x.from, x.to, x.every, x.days)) }
        }
        Store.tracks(c).filter { it.remOn }.forEach { l.add(Src("t_${it.id}", it.remFrom, it.remTo, it.remEvery, it.remDays)) }
        return l
    }

    private fun inQuiet(q: List<Quiet>, t: LocalDateTime) = q.any { windowDay(it.days, it.start, it.end, t) != null }
    private fun dayOk(s: Src, t: LocalDateTime) = (s.days and (1 shl (t.dayOfWeek.value - 1))) != 0

    /**
     * Reminders sit on one shared clock grid (every 30 / 60 / 90 min from midnight), so several habits fall on the
     * same minute and the phone wakes once instead of many times. "Once a day" fires at the From time.
     */
    fun nextFor(s: Src, q: List<Quiet>, from: LocalDateTime): LocalDateTime? {
        val base = from.plusMinutes(1).withSecond(0).withNano(0)
        val mins: List<Int> = if (s.every >= 1440) listOf(s.from) else (0 until 1440 step s.every).toList()
        for (dayOff in 0..8) {
            val day = base.toLocalDate().plusDays(dayOff.toLong())
            for (m in mins) {
                val t = day.atStartOfDay().plusMinutes(m.toLong())
                if (t.isBefore(base)) continue
                if (dayOk(s, t) && inRange(m, s.from, s.to) && !inQuiet(q, t)) return t
            }
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
        if (min == Long.MAX_VALUE) am.cancel(pi)
        else if (Store.lowPower(c)) am.set(AlarmManager.RTC, min, pi)          // battery saver: never wakes the phone
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, min, pi)
    }

    /** One alarm a day, just after midnight: closes yesterday's streaks and refreshes the widget. */
    fun scheduleDaily(c: Context) {
        val t = java.time.LocalDate.now().plusDays(1).atTime(0, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(c, 2, Intent(c, DailyReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = c.getSystemService(AlarmManager::class.java)
        if (Store.lowPower(c)) am.set(AlarmManager.RTC, t, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
    }

    /** Called when a reminder is due. Re-checks the rules at delivery time, so late alarms never ping you at a bad time. */
    fun fire(c: Context, s: Src, now: LocalDateTime) {
        cur = s
        if (!inRange(now.hour * 60 + now.minute, s.from, s.to) || !dayOk(s, now) || inQuiet(Store.quiets(c), now)) return
        if (s.key == "goal") { send(c); return }
        if (s.key.startsWith("t_")) {
            val tr = Store.tracks(c).firstOrNull { "t_${it.id}" == s.key } ?: return
            val reached = if (tr.up) tr.cur >= tr.target else tr.cur <= tr.target
            val loggedToday = tr.hist.size > 1 &&
                Instant.ofEpochMilli(tr.hist.last().first).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() == Store.today()
            if (!reached && !loggedToday) notifyTrack(c, tr)
            return
        }
        val hid = s.key.removePrefix("h_").substringBefore('#')
        val h = Store.habits(c).firstOrNull { it.id == hid } ?: return
        val t = Store.today()
        if (!Store.hActive(h, t)) return
        val v = Store.hLog(c, h.id, t)
        if (h.kind == 1 && h.target == 0) {
            // open-ended ("walk whenever"): keep reminding, but skip if you logged one recently
            val due = if (s.every >= 1440) v == 0 else System.currentTimeMillis() - Store.hLast(c, h.id) >= s.every * 60000L
            if (due) notifyHabit(c, h, v)
        } else if (h.kind == 2) { if (v == 0) notifyHabit(c, h, v) }
        else if (!Store.met(h, v)) notifyHabit(c, h, v)
    }

    private fun channel(c: Context, id: String, name: String, imp: Int = NotificationManager.IMPORTANCE_HIGH): NotificationManager {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(id, name, imp))
        return nm
    }

    fun notifId(id: String) = 100 + (id.hashCode() and 0xFFFF)

    fun notifyHabit(c: Context, h: Habit, v: Int) {
        Store.bump(c, "notifs")
        logN(c, h.name)
        val nm = channel(c, "habits", "Habit reminders")
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val txt = when (h.kind) {
            0 -> "Time to do it."
            1 -> if (h.target == 0) "$v ${h.unit} so far today".trim() else "$v / ${h.target} ${h.unit} so far"
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

    fun notifyTrack(c: Context, tr: Track) {
        Store.bump(c, "notifs")
        logN(c, tr.name)
        val nm = channel(c, "targets", "Target reminders", NotificationManager.IMPORTANCE_DEFAULT)
        val open = PendingIntent.getActivity(c, 20, Intent(c, MainActivity::class.java).putExtra("go", "targets"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val txt = "Log today's value · now ${fmtNum(tr.cur)} ${tr.unit}, goal ${fmtNum(tr.target)}".replace("  ", " ")
        nm.notify(300 + (tr.id.hashCode() and 0xFFFF), Notification.Builder(c, "targets").setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${tr.emoji} ${tr.name}").setContentText(txt).setContentIntent(open).setAutoCancel(true).build())
    }

    fun send(c: Context, test: Boolean = false) {
        val t = Store.today()
        val all = Store.goals(c)
        val pend = all.filter { t < it.end && it.last != t }
        if (pend.isEmpty() && !test) return
        Store.bump(c, "notifs")
        logN(c, if (test) "test notification" else "goal check-in")
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
        Store.bump(c, "alarms")
        val nowMs = System.currentTimeMillis()
        val now = LocalDateTime.now()
        for (s in Reminders.sources(c)) {
            val nd = Store.nd(c, s.key)
            if (nd > 0L && nd <= nowMs + 60000L) { Reminders.fire(c, s, now); Store.setNd(c, s.key, 0L) }
        }
        Reminders.scheduleAll(c, false)
        LifeWidget.refresh(c)
        Store.flushUsage(c, true)
    }

    companion object {
        /** Call after any reminder/habit/quiet-time change. */
        fun schedule(c: Context) { Reminders.scheduleAll(c, true); Reminders.scheduleDaily(c) }
        /** Call on app start / boot. */
        fun ensure(c: Context) { Reminders.scheduleAll(c, false); Reminders.scheduleDaily(c) }
    }
}

/** Handles the "Done" / "+250 ml" button on a habit notification. */
class HabitActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        val intent = i ?: return
        val id = intent.getStringExtra("id") ?: return
        Store.hAdd(c, id, intent.getIntExtra("d", 1))
        c.getSystemService(NotificationManager::class.java).cancel(Reminders.notifId(id))
        LifeWidget.refresh(c)
    }
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        Store.rollover(c)
        LifeWidget.refresh(c)
        Reminders.scheduleDaily(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        ReminderReceiver.ensure(c)
        Compat.sync(c)
    }
}
