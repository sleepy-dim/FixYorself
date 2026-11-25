package com.lifelock.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
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
        if (r.on && Store.goals(c).any { it.remOn && it.end > Store.today() }) l.add(Src("goal", r.from, r.to, r.every, 127))
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
        val mins: List<Int> = if (s.every >= 1440) listOf(s.from) else (0 until 1440 step s.every.coerceAtLeast(1)).toList()
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
        else if (Store.deferAlarms(c)) am.set(AlarmManager.RTC, min, pi)          // battery saver: never wakes the phone
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, min, pi)
    }

    /** One alarm a day, just after midnight: closes yesterday's streaks and refreshes the widget. */
    fun scheduleDaily(c: Context) {
        val t = java.time.LocalDate.now().plusDays(1).atTime(0, 5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(c, 2, Intent(c, DailyReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = c.getSystemService(AlarmManager::class.java)
        if (Store.deferAlarms(c)) am.set(AlarmManager.RTC, t, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
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

    /** One channel per style, because a channel's sound can't be changed after it exists. */
    private fun chan(c: Context, base: String, label: String, st: Int): String {
        val id = "${base}_s$st"
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            for (old in listOf("remind", "habits", "targets")) nm.deleteNotificationChannel(old)
            val ch = NotificationChannel(id, label + (if (st == 0) " (gentle)" else if (st == 1) " (loud)" else " (wakes screen)"),
                NotificationManager.IMPORTANCE_HIGH)
            if (st >= 1) {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ch.setSound(uri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                ch.enableVibration(true)
                ch.vibrationPattern = longArrayOf(0, 600, 250, 600, 250, 900)
            }
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        return id
    }

    /** Adds the "this is a reminder" hints, the optional screen-waking pop-up and a time-out, then builds. */
    private fun decorate(c: Context, b: Notification.Builder, popup: Intent?, code: Int, st: Int): Notification {
        b.setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PUBLIC)
        if (st == 2 && popup != null) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent())
                b.setFullScreenIntent(PendingIntent.getActivity(c, code, popup, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT), true)
        }
        if (st >= 1) b.setTimeoutAfter(5 * 60 * 1000L)
        return b.build()
    }

    private fun popupIntent(c: Context, type: String, id: String, title: String, text: String, label: String, amount: Int = 1) =
        Intent(c, ReminderPopup::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("type", type).putExtra("id", id).putExtra("title", title).putExtra("text", text)
            .putExtra("label", label).putExtra("amount", amount)

    private fun snoozeAction(c: Context, type: String, id: String) = PendingIntent.getBroadcast(c, ("s$type$id").hashCode(),
        Intent(c, SnoozeReceiver::class.java).setAction("snooze").putExtra("type", type).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun notifId(id: String) = 100 + (id.hashCode() and 0xFFFF)
    fun notifIdFor(type: String, id: String) = when (type) { "h" -> notifId(id); "t" -> 300 + (id.hashCode() and 0xFFFF); else -> 1 }

    /** Re-shows a reminder after [minutes] (wakes the phone unless battery-saver reminders are on and the style is gentle). */
    fun snooze(c: Context, type: String, id: String, minutes: Int) {
        val pi = PendingIntent.getBroadcast(c, ("f$type$id").hashCode(),
            Intent(c, SnoozeReceiver::class.java).setAction("fire").putExtra("type", type).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val at = System.currentTimeMillis() + minutes * 60000L
        val am = c.getSystemService(AlarmManager::class.java)
        if (Store.deferAlarms(c)) am.set(AlarmManager.RTC, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun notifyHabit(c: Context, h: Habit, v: Int) {
        Store.bump(c, "notifs")
        logN(c, h.name)
        val nm = c.getSystemService(NotificationManager::class.java)
        val st = if (h.remStyle >= 0) h.remStyle else Store.style(c)
        val cid = chan(c, "habits", "Habit reminders", st)
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
        val b = Notification.Builder(c, cid).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${h.emoji} ${h.name}").setContentText(txt).setContentIntent(open).setAutoCancel(true)
            .addAction(android.R.drawable.ic_input_add, label, act)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 10 min", snoozeAction(c, "h", h.id))
        nm.notify(notifId(h.id), decorate(c, b, popupIntent(c, "h", h.id, "${h.emoji} ${h.name}", txt, label, amount), h.id.hashCode(), st))
    }

    fun notifyTrack(c: Context, tr: Track) {
        Store.bump(c, "notifs")
        logN(c, tr.name)
        val nm = c.getSystemService(NotificationManager::class.java)
        val st = if (tr.remStyle >= 0) tr.remStyle else Store.style(c)
        val cid = chan(c, "targets", "Target reminders", st)
        val open = PendingIntent.getActivity(c, 20, Intent(c, MainActivity::class.java).putExtra("go", "targets"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val txt = "Log today's value · now ${fmtNum(tr.cur)} ${tr.unit}, goal ${fmtNum(tr.target)}".replace("  ", " ")
        val b = Notification.Builder(c, cid).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${tr.emoji} ${tr.name}").setContentText(txt).setContentIntent(open).setAutoCancel(true)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze 10 min", snoozeAction(c, "t", tr.id))
        nm.notify(notifIdFor("t", tr.id), decorate(c, b, popupIntent(c, "t", tr.id, "${tr.emoji} ${tr.name}", txt, "Log it"), 300 + tr.id.hashCode(), st))
    }

    fun send(c: Context, test: Boolean = false) {
        val t = Store.today()
        val all = Store.goals(c)
        val pend = all.filter { t < it.end && it.last != t && it.remOn }
        if (pend.isEmpty() && !test) return
        Store.bump(c, "notifs")
        logN(c, if (test) "test notification" else "goal check-in")
        val best = all.maxOfOrNull { Store.liveStreak(it) } ?: 0
        val nm = c.getSystemService(NotificationManager::class.java)
        val st = pend.maxOfOrNull { if (it.remStyle >= 0) it.remStyle else Store.style(c) } ?: Store.style(c)
        val cid = chan(c, "remind", "Check-in reminders", st)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = if (pend.isEmpty()) "Reminders are working ✅" else "Check in to keep your streak 🔥"
        val text = if (pend.isEmpty()) "You're all checked in." else pend.joinToString { it.title } + (if (best > 0) " · $best-day streak at stake" else "")
        val b = Notification.Builder(c, cid).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true)
        nm.notify(1, decorate(c, b, popupIntent(c, "g", pend.firstOrNull()?.id ?: "", title, text, if (pend.isEmpty()) "OK" else "Check in"), 1, st))
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        val pr = goAsync()
        Thread {
            try { work(c) } catch (e: Exception) { } finally { pr.finish() }
        }.start()
    }

    private fun work(c: Context) {
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

/** Handles "Snooze 10 min" (schedules it) and the snoozed alarm itself (shows the reminder again if it is still needed). */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        val intent = i ?: return
        val type = intent.getStringExtra("type") ?: return
        val id = intent.getStringExtra("id") ?: return
        if (intent.action == "snooze") {
            c.getSystemService(NotificationManager::class.java).cancel(Reminders.notifIdFor(type, id))
            Reminders.snooze(c, type, id, 10)
            return
        }
        val t = Store.today()
        when (type) {
            "h" -> Store.habits(c).firstOrNull { it.id == id }?.let { h ->
                val v = Store.hLog(c, h.id, t)
                val open = h.kind == 1 && h.target == 0
                if (Store.hActive(h, t) && (open || (h.kind == 2 && v == 0) || (h.kind != 2 && !Store.met(h, v)))) Reminders.notifyHabit(c, h, v)
            }
            "t" -> Store.tracks(c).firstOrNull { it.id == id }?.let { Reminders.notifyTrack(c, it) }
            else -> Reminders.send(c)
        }
    }
}

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        val pr = goAsync()
        Thread {
            try { Store.rollover(c); LifeWidget.refresh(c); Reminders.scheduleDaily(c) } catch (e: Exception) { } finally { pr.finish() }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        ReminderReceiver.ensure(c)
        Compat.sync(c)
    }
}
