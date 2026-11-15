package com.lifelock.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

data class Goal(val id: String, val title: String, val end: Long, val apps: List<String>,
                val last: Long, val streak: Int, val best: Int, val created: Long)
data class Limit(val pkg: String, val min: Int, val streak: Int, val ev: Long, val cd: Long)
data class Entry(val pkg: String, val cap: Int)
data class Sched(val id: String, val name: String, val days: Int, val start: Int, val end: Int,
                 val allowOnly: Boolean, val entries: List<Entry>, val created: Long)
data class Quiet(val id: String, val name: String, val days: Int, val start: Int, val end: Int)
data class RemCfg(val on: Boolean, val from: Int, val to: Int, val every: Int)
data class Habit(val id: String, val name: String, val emoji: String, val kind: Int, val target: Int, val unit: String,
                 val step: Int, val days: Int, val remOn: Boolean, val remFrom: Int, val remTo: Int, val remEvery: Int,
                 val created: Long, val streak: Int, val best: Int, val ev: Long)   // kind: 0 = yes/no, 1 = at least, 2 = at most
data class Track(val id: String, val name: String, val emoji: String, val unit: String, val start: Double,
                 val target: Double, val end: Long, val created: Long, val hist: List<Pair<Long, Double>>,
                 val remOn: Boolean = false, val remFrom: Int = 480, val remTo: Int = 480, val remEvery: Int = 1440, val remDays: Int = 127) {
    val cur: Double get() = hist.lastOrNull()?.second ?: start
    val up: Boolean get() = target >= start
}
data class Block(val kind: String, val msg: String, val passKey: String? = null)

fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
fun inRange(m: Int, s: Int, e: Int) = if (s == e) true else if (s < e) m in s until e else (m >= s || m < e)

/** If the recurring window is active at [now], returns the epoch-day it started on, else null. */
fun windowDay(days: Int, start: Int, end: Int, now: LocalDateTime): Long? {
    val m = now.hour * 60 + now.minute
    val d = now.toLocalDate()
    fun has(x: LocalDate) = (days and (1 shl (x.dayOfWeek.value - 1))) != 0
    return when {
        start == end -> if (has(d)) d.toEpochDay() else null
        start < end -> if (m in start until end && has(d)) d.toEpochDay() else null
        m >= start && has(d) -> d.toEpochDay()
        m < end && has(d.minusDays(1)) -> d.toEpochDay() - 1
        else -> null
    }
}

fun daysLabel(d: Int): String {
    val n = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return when (d) {
        127 -> "Every day"
        31 -> "Mon–Fri"
        96 -> "Weekends"
        0 -> "No days"
        else -> n.filterIndexed { i, _ -> (d and (1 shl i)) != 0 }.joinToString(", ")
    }
}

private fun JSONArray.objs(): List<JSONObject> = List(length()) { getJSONObject(it) }
private fun JSONArray.strs(): List<String> = List(length()) { getString(it) }

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("lifelock", Context.MODE_PRIVATE)
    fun today(): Long = LocalDate.now().toEpochDay()

    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Any>>()
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(key: String, raw: String, parse: () -> T): T {
        val p = parsed[key]
        if (p != null && p.first == raw) return p.second as T
        val v = parse()
        parsed[key] = raw to v
        return v
    }

    // ---- goals ----
    fun goals(c: Context): List<Goal> = cached("goals", sp(c).getString("goals", "[]") ?: "[]") { parseGoals(c) }
    private fun parseGoals(c: Context): List<Goal> = JSONArray(sp(c).getString("goals", "[]")).objs().map {
        Goal(it.getString("id"), it.getString("t"), it.getLong("e"), it.getJSONArray("a").strs(),
            it.getLong("l"), it.getInt("s"), it.getInt("b"), it.getLong("c"))
    }
    fun saveGoals(c: Context, l: List<Goal>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("t", it.title).put("e", it.end).put("a", JSONArray(it.apps))
                .put("l", it.last).put("s", it.streak).put("b", it.best).put("c", it.created))
        }
        sp(c).edit().putString("goals", a.toString()).apply()
    }
    fun addGoal(c: Context, title: String, end: Long, apps: List<String>) {
        val now = System.currentTimeMillis()
        saveGoals(c, goals(c) + Goal(now.toString(), title, end, apps, 0L, 0, 0, now))
    }
    fun checkIn(c: Context, id: String) {
        val t = today()
        saveGoals(c, goals(c).map { g ->
            if (g.id == id && g.last != t) {
                val s = if (g.last == t - 1) g.streak + 1 else 1
                g.copy(streak = s, best = maxOf(g.best, s), last = t)
            } else g
        })
    }
    fun liveStreak(g: Goal) = if (g.last >= today() - 1) g.streak else 0
    fun canDelete(g: Goal) = today() >= g.end || System.currentTimeMillis() - g.created < 600000L
    fun deleteGoal(c: Context, id: String) = saveGoals(c, goals(c).filter { it.id != id })

    // ---- daily limits ----
    fun limits(c: Context): List<Limit> = cached("limits", sp(c).getString("limits", "[]") ?: "[]") { parseLimits(c) }
    private fun parseLimits(c: Context): List<Limit> = JSONArray(sp(c).getString("limits", "[]")).objs().map {
        Limit(it.getString("p"), it.getInt("m"), it.getInt("s"), it.getLong("e"), it.getLong("c"))
    }
    fun saveLimits(c: Context, l: List<Limit>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("p", it.pkg).put("m", it.min).put("s", it.streak).put("e", it.ev).put("c", it.cd)) }
        sp(c).edit().putString("limits", a.toString()).apply()
    }
    /** Create, or lower. Never raise. */
    fun addLimit(c: Context, pkg: String, min: Int): Boolean {
        val l = limits(c)
        val ex = l.firstOrNull { it.pkg == pkg }
        if (ex == null) {
            saveLimits(c, l + Limit(pkg, min, 0, today() - 1, today()))
        } else {
            if (min > ex.min) return false
            saveLimits(c, l.map { if (it.pkg == pkg) it.copy(min = min) else it })
        }
        return true
    }
    /** Locked for 7 days after creation. */
    fun removeLimit(c: Context, pkg: String): Boolean {
        val x = limits(c).firstOrNull { it.pkg == pkg } ?: return true
        if (today() - x.cd < 7) return false
        saveLimits(c, limits(c).filter { it.pkg != pkg })
        return true
    }

    // ---- usage (generic keyed counters) ----
    fun gKey(p: String, d: Long = today()) = "g_${p}_$d"
    private val usageMem = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val usageDirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun used(c: Context, key: String): Long = usageMem[key] ?: sp(c).getLong("u_$key", 0L)
    fun addUsed(c: Context, key: String, ms: Long) { usageMem[key] = used(c, key) + ms; usageDirty.add(key) }
    /** Writes pending usage to disk in a single batch. Called on screen-off, app switch and every few minutes. */
    private val diagDirty = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /**
     * Saves real usage whenever it changed. Diagnostic counters are only saved when [all] is true
     * (screen off, service stop, alarms, the slow timer), so ordinary app switches cause no disk writes.
     */
    fun flushUsage(c: Context, all: Boolean = false) {
        if (usageDirty.isEmpty() && !(all && diagDirty.isNotEmpty())) return
        val ed = sp(c).edit()
        for (k in usageDirty.toList()) { ed.putLong("u_$k", usageMem[k] ?: 0L); usageDirty.remove(k) }
        if (all) for (k in diagDirty.toList()) { ed.putLong("u_$k", usageMem[k] ?: 0L); diagDirty.remove(k) }
        ed.apply()
    }

    /** Milliseconds until a time-limited allowance for [p] runs out (Long.MAX_VALUE if none applies). */
    fun remainingMs(c: Context, p: String, now: LocalDateTime): Long {
        var best = Long.MAX_VALUE
        val l = limits(c).firstOrNull { it.pkg == p }
        if (l != null) { val k = gKey(p); best = minOf(best, (l.min + extra(c, k)) * 60000L - used(c, k)) }
        for (s in scheds(c)) {
            val wd = windowDay(s.days, s.start, s.end, now) ?: continue
            val e = s.entries.firstOrNull { it.pkg == p } ?: continue
            if (e.cap > 0) { val k = "s_${s.id}_${p}_$wd"; best = minOf(best, (e.cap + extra(c, k)) * 60000L - used(c, k)) }
        }
        return best
    }

    /** Milliseconds until the next schedule start/end or focus end. Long.MAX_VALUE if nothing time-based is configured. */
    fun nextChangeMs(c: Context, now: LocalDateTime): Long {
        var best = Long.MAX_VALUE
        val fu = focusUntil(c) - System.currentTimeMillis()
        if (fu > 0) best = fu
        val today = now.toLocalDate()
        for (s in scheds(c)) {
            for (d in 0..7) {
                val day = today.plusDays(d.toLong())
                for (m in intArrayOf(s.start, s.end)) {
                    val t = day.atStartOfDay().plusMinutes(m.toLong())
                    if (t.isAfter(now)) { val ms = java.time.Duration.between(now, t).toMillis(); if (ms < best) best = ms }
                }
            }
        }
        return best
    }
    fun extra(c: Context, key: String) = sp(c).getInt("x_$key", 0)
    fun bump(c: Context, name: String) { val k = "diag_${name}_${today()}"; usageMem[k] = used(c, k) + 1L; diagDirty.add(k) }
    fun diag(c: Context, name: String, day: Long) = used(c, "diag_${name}_$day")

    fun rollover(c: Context) {
        val t = today()
        var ch = false
        val out = limits(c).map { x ->
            var ev = x.ev
            var st = x.streak
            if (t - ev > 14) { ev = t - 14; ch = true }
            while (ev < t - 1) {
                val d = ev + 1
                val ok = used(c, gKey(x.pkg, d)) <= x.min * 60000L && extra(c, gKey(x.pkg, d)) == 0
                st = if (ok) st + 1 else 0
                ev = d; ch = true
            }
            x.copy(streak = st, ev = ev)
        }
        if (ch) saveLimits(c, out)
        habitRollover(c)
        val s = sp(c)
        if (s.getLong("clean", 0L) != t) {
            val ed = s.edit()
            for (k in s.all.keys) {
                if (k.startsWith("u_") || k.startsWith("x_")) {
                    val d = k.substringAfterLast('_').toLongOrNull()
                    if (d != null && d < t - 14) ed.remove(k)
                }
            }
            ed.putLong("clean", t).apply()
        }
    }

    // ---- schedules ----
    fun scheds(c: Context): List<Sched> = cached("scheds", sp(c).getString("scheds", "[]") ?: "[]") { parseScheds(c) }
    private fun parseScheds(c: Context): List<Sched> = JSONArray(sp(c).getString("scheds", "[]")).objs().map { o ->
        Sched(o.getString("id"), o.getString("n"), o.getInt("d"), o.getInt("s"), o.getInt("e"), o.getBoolean("a"),
            o.getJSONArray("en").objs().map { Entry(it.getString("p"), it.getInt("c")) }, o.getLong("c"))
    }
    private fun writeScheds(c: Context, l: List<Sched>) {
        val a = JSONArray()
        l.forEach { s ->
            val en = JSONArray()
            s.entries.forEach { en.put(JSONObject().put("p", it.pkg).put("c", it.cap)) }
            a.put(JSONObject().put("id", s.id).put("n", s.name).put("d", s.days).put("s", s.start).put("e", s.end)
                .put("a", s.allowOnly).put("en", en).put("c", s.created))
        }
        sp(c).edit().putString("scheds", a.toString()).apply()
    }
    fun saveSched(c: Context, s: Sched) {
        val l = scheds(c)
        writeScheds(c, if (l.any { it.id == s.id }) l.map { if (it.id == s.id) s else it } else l + s)
    }
    fun deleteSched(c: Context, id: String) = writeScheds(c, scheds(c).filter { it.id != id })
    fun schedActive(s: Sched, now: LocalDateTime = LocalDateTime.now()) = windowDay(s.days, s.start, s.end, now) != null
    /** Active schedules can't be edited or removed (except in the first 10 minutes after creating). */
    fun schedLocked(s: Sched) = schedActive(s) && System.currentTimeMillis() - s.created > 600000L

    // ---- quiet periods & reminder config ----
    fun quiets(c: Context): List<Quiet> = cached("quiets", sp(c).getString("quiets", "[]") ?: "[]") { parseQuiets(c) }
    private fun parseQuiets(c: Context): List<Quiet> = JSONArray(sp(c).getString("quiets", "[]")).objs().map {
        Quiet(it.getString("id"), it.getString("n"), it.getInt("d"), it.getInt("s"), it.getInt("e"))
    }
    private fun writeQuiets(c: Context, l: List<Quiet>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("d", it.days).put("s", it.start).put("e", it.end)) }
        sp(c).edit().putString("quiets", a.toString()).apply()
    }
    fun saveQuiet(c: Context, q: Quiet) {
        val l = quiets(c)
        writeQuiets(c, if (l.any { it.id == q.id }) l.map { if (it.id == q.id) q else it } else l + q)
    }
    fun deleteQuiet(c: Context, id: String) = writeQuiets(c, quiets(c).filter { it.id != id })
    fun rem(c: Context): RemCfg {
        val s = sp(c)
        return RemCfg(s.getBoolean("ron", true), s.getInt("rf", 480), s.getInt("rt", 1320), s.getInt("re", 60))
    }
    fun saveRem(c: Context, r: RemCfg) =
        sp(c).edit().putBoolean("ron", r.on).putInt("rf", r.from).putInt("rt", r.to).putInt("re", r.every).apply()

    // ---- passes / misc ----
    private fun weekStart() = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay()
    fun passN(c: Context) = sp(c).getInt("pn", 3)
    fun passMin(c: Context) = sp(c).getInt("pm", 10)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun why(c: Context) = sp(c).getString("why", "") ?: ""
    fun setWhy(c: Context, s: String) = sp(c).edit().putString("why", s).apply()
    fun passesLeft(c: Context): Int {
        val s = sp(c)
        if (s.getLong("pw", 0L) != weekStart()) s.edit().putLong("pw", weekStart()).putInt("pu", 0).apply()
        return passN(c) - s.getInt("pu", 0)
    }
    fun usePass(c: Context, key: String): Boolean {
        if (passesLeft(c) <= 0) return false
        sp(c).edit().putInt("pu", sp(c).getInt("pu", 0) + 1).putInt("x_$key", extra(c, key) + passMin(c)).apply()
        return true
    }

    // ---- focus ----
    fun focusUntil(c: Context) = sp(c).getLong("fu", 0L)
    fun startFocus(c: Context, min: Int) = sp(c).edit().putLong("fu", System.currentTimeMillis() + min * 60000L).apply()
    fun focusApps(c: Context): Set<String> = cached("fa", sp(c).getString("fa", null) ?: "d") { parseFocus(c) }
    private fun parseFocus(c: Context): Set<String> =
        JSONArray(sp(c).getString("fa", null) ?: "[\"com.whatsapp\",\"com.whatsapp.w4b\"]").strs().toSet()
    fun setFocusApps(c: Context, s: Set<String>) = sp(c).edit().putString("fa", JSONArray(s.toList()).toString()).apply()

    // ---- habits ----
    fun habits(c: Context): List<Habit> = cached("habits", sp(c).getString("habits", "[]") ?: "[]") { parseHabits(c) }
    private fun parseHabits(c: Context): List<Habit> = JSONArray(sp(c).getString("habits", "[]")).objs().map {
        Habit(it.getString("id"), it.getString("n"), it.getString("em"), it.getInt("k"), it.getInt("t"), it.getString("u"),
            it.getInt("st"), it.getInt("d"), it.getBoolean("ro"), it.getInt("rf"), it.getInt("rt"), it.getInt("re"),
            it.getLong("c"), it.getInt("s"), it.getInt("b"), it.getLong("e"))
    }
    private fun writeHabits(c: Context, l: List<Habit>) {
        val a = JSONArray()
        l.forEach {
            a.put(JSONObject().put("id", it.id).put("n", it.name).put("em", it.emoji).put("k", it.kind).put("t", it.target)
                .put("u", it.unit).put("st", it.step).put("d", it.days).put("ro", it.remOn).put("rf", it.remFrom)
                .put("rt", it.remTo).put("re", it.remEvery).put("c", it.created).put("s", it.streak).put("b", it.best).put("e", it.ev))
        }
        sp(c).edit().putString("habits", a.toString()).apply()
    }
    fun saveHabit(c: Context, h: Habit) {
        val l = habits(c)
        writeHabits(c, if (l.any { it.id == h.id }) l.map { if (it.id == h.id) h else it } else l + h)
    }
    fun deleteHabit(c: Context, id: String) = writeHabits(c, habits(c).filter { it.id != id })
    fun hLog(c: Context, id: String, d: Long = today()) = sp(c).getInt("hl_${id}_$d", 0)
    fun hSet(c: Context, id: String, v: Int) = sp(c).edit().putInt("hl_${id}_${today()}", v.coerceAtLeast(0)).apply()
    fun hAdd(c: Context, id: String, delta: Int) = hSet(c, id, hLog(c, id) + delta)
    fun hActive(h: Habit, d: Long) = (h.days and (1 shl (LocalDate.ofEpochDay(d).dayOfWeek.value - 1))) != 0
    fun met(h: Habit, v: Int) = when (h.kind) { 0 -> v >= 1; 1 -> v >= h.target; else -> v in 1..h.target }
    fun hStreak(c: Context, h: Habit) =
        h.streak + (if (h.kind != 2 && hActive(h, today()) && met(h, hLog(c, h.id))) 1 else 0)
    private fun habitRollover(c: Context) {
        val t = today()
        var ch = false
        val out = habits(c).map { h ->
            var ev = h.ev
            var st = h.streak
            var b = h.best
            if (t - ev > 60) { ev = t - 60; ch = true }
            while (ev < t - 1) {
                val d = ev + 1
                if (hActive(h, d)) { st = if (met(h, hLog(c, h.id, d))) st + 1 else 0; b = maxOf(b, st) }
                ev = d; ch = true
            }
            h.copy(streak = st, best = b, ev = ev)
        }
        if (ch) writeHabits(c, out)
    }
    fun nd(c: Context, k: String) = sp(c).getLong("nd_$k", 0L)
    fun setNd(c: Context, k: String, v: Long) = sp(c).edit().putLong("nd_$k", v).apply()

    // ---- targets: numeric goals (weight, push-ups, savings...) ----
    fun tracks(c: Context): List<Track> = cached("tracks", sp(c).getString("tracks", "[]") ?: "[]") { parseTracks(c) }
    private fun parseTracks(c: Context): List<Track> = JSONArray(sp(c).getString("tracks", "[]")).objs().map { o ->
        val h = o.getJSONArray("h")
        Track(o.getString("id"), o.getString("n"), o.getString("em"), o.getString("u"), o.getDouble("s"), o.getDouble("t"),
            o.getLong("e"), o.getLong("c"), List(h.length()) { i -> val a = h.getJSONArray(i); a.getLong(0) to a.getDouble(1) },
            o.optBoolean("ro", false), o.optInt("rf", 480), o.optInt("rt", 480), o.optInt("re", 1440), o.optInt("rd", 127))
    }
    private fun writeTracks(c: Context, l: List<Track>) {
        val a = JSONArray()
        l.forEach { t ->
            val h = JSONArray()
            t.hist.forEach { h.put(JSONArray().put(it.first).put(it.second)) }
            a.put(JSONObject().put("id", t.id).put("n", t.name).put("em", t.emoji).put("u", t.unit).put("s", t.start)
                .put("t", t.target).put("e", t.end).put("c", t.created).put("ro", t.remOn).put("rf", t.remFrom).put("rt", t.remTo)
                .put("re", t.remEvery).put("rd", t.remDays).put("h", h))
        }
        sp(c).edit().putString("tracks", a.toString()).apply()
    }
    fun saveTrack(c: Context, t: Track) {
        val l = tracks(c)
        writeTracks(c, if (l.any { it.id == t.id }) l.map { if (it.id == t.id) t else it } else l + t)
    }
    fun deleteTrack(c: Context, id: String) = writeTracks(c, tracks(c).filter { it.id != id })
    fun trackSet(c: Context, id: String, v: Double) {
        val r = Math.round(v * 100.0) / 100.0
        writeTracks(c, tracks(c).map { if (it.id == id) it.copy(hist = (it.hist + (System.currentTimeMillis() to r)).takeLast(400)) else it })
    }
    fun trackAdd(c: Context, id: String, d: Double) {
        val t = tracks(c).firstOrNull { it.id == id } ?: return
        trackSet(c, id, t.cur + d)
    }
    fun trackUndo(c: Context, id: String) =
        writeTracks(c, tracks(c).map { if (it.id == id && it.hist.size > 1) it.copy(hist = it.hist.dropLast(1)) else it })

    // ---- apps where blocking pauses (compatibility mode) ----
    fun pauseApps(c: Context): Set<String> = cached("pa", sp(c).getString("pa", "[]") ?: "[]") {
        JSONArray(sp(c).getString("pa", "[]")).strs().toSet()
    }
    fun setPauseApps(c: Context, s: Set<String>) = sp(c).edit().putString("pa", JSONArray(s.toList()).toString()).apply()
    fun pausedPkg(c: Context) = sp(c).getString("paused", "") ?: ""
    fun setPaused(c: Context, p: String) = sp(c).edit().putString("paused", p).apply()
    /** True if any goal / limit / schedule touches this app, so it can't be exempted from blocking. */
    fun ruleTouches(c: Context, p: String) =
        goals(c).any { p in it.apps } || limits(c).any { it.pkg == p } || scheds(c).any { s -> s.entries.any { it.pkg == p } }

    fun theme(c: Context) = sp(c).getInt("theme", 1)
    fun lowPower(c: Context) = sp(c).getBoolean("lowpower", false)
    fun setLowPower(c: Context, v: Boolean) = sp(c).edit().putBoolean("lowpower", v).apply()
    fun quickHabit(c: Context) = sp(c).getString("qh", "") ?: ""
    fun setQuickHabit(c: Context, id: String) = sp(c).edit().putString("qh", id).apply()

    // ---- rule engine ----
    fun trackKeys(c: Context, p: String, now: LocalDateTime): List<String> {
        val r = mutableListOf<String>()
        if (limits(c).any { it.pkg == p }) r.add(gKey(p))
        for (s in scheds(c)) {
            val wd = windowDay(s.days, s.start, s.end, now) ?: continue
            val e = s.entries.firstOrNull { it.pkg == p } ?: continue
            if (e.cap > 0) r.add("s_${s.id}_${p}_$wd")
        }
        return r
    }

    fun blockReason(c: Context, p: String, ess: Set<String>, focusApps: Set<String>, now: LocalDateTime): Block? {
        val ms = System.currentTimeMillis()
        val fu = focusUntil(c)
        if (fu > ms && p !in ess && p !in focusApps)
            return Block("focus", "Focus session · ${(fu - ms) / 60000 + 1} min left.\nOnly your allowed apps, calls and SMS work.")
        val t = today()
        val g = goals(c).firstOrNull { t < it.end && p in it.apps }
        if (g != null) return Block("goal", "Goal: ${g.title}\nBlocked until ${LocalDate.ofEpochDay(g.end)}")
        for (s in scheds(c)) {
            val wd = windowDay(s.days, s.start, s.end, now) ?: continue
            val e = s.entries.firstOrNull { it.pkg == p }
            if (e == null) {
                if (s.allowOnly && p !in ess) return Block("schedule", "${s.name}\nOnly your allowed apps work until ${hhmm(s.end)}.")
                continue
            }
            if (!s.allowOnly && e.cap == 0) return Block("schedule", "${s.name}\nThis app is blocked until ${hhmm(s.end)}.")
            if (e.cap > 0) {
                val key = "s_${s.id}_${p}_$wd"
                if (used(c, key) >= (e.cap + extra(c, key)) * 60000L)
                    return Block("limit", "${s.name}\nYou've used your ${e.cap} min for today.", key)
            }
        }
        val l = limits(c).firstOrNull { it.pkg == p }
        if (l != null) {
            val key = gKey(p)
            if (used(c, key) >= (l.min + extra(c, key)) * 60000L)
                return Block("limit", "Daily limit reached (${l.min} min).", key)
        }
        return null
    }
}
