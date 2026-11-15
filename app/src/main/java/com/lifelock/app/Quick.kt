package com.lifelock.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.View
import android.widget.RemoteViews

class LifeWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val v = build(c)
        for (id in ids) m.updateAppWidget(id, v)
    }

    companion object {
        /** The counter habit the widget and the Quick log tile add to: your pick, else water, else the first unfinished one. */
        fun primary(c: Context): Habit? {
            val t = Store.today()
            val q = Store.quickHabit(c)
            val due = Store.habits(c).filter { Store.hActive(it, t) && it.kind == 1 }
            return due.firstOrNull { it.id == q }
                ?: due.firstOrNull { it.name.contains("water", ignoreCase = true) }
                ?: due.firstOrNull { !Store.met(it, Store.hLog(c, it.id, t)) }
                ?: due.firstOrNull()
        }

        fun refresh(c: Context) {
            try {
                val m = AppWidgetManager.getInstance(c)
                val ids = m.getAppWidgetIds(ComponentName(c, LifeWidget::class.java))
                if (ids.isNotEmpty()) { val v = build(c); for (id in ids) m.updateAppWidget(id, v) }
            } catch (e: Exception) { }
        }

        private fun bc(c: Context, code: Int, i: Intent) =
            PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_life)
            val t = Store.today()
            val due = Store.habits(c).filter { Store.hActive(it, t) }
            val done = due.count { Store.met(it, Store.hLog(c, it.id, t)) }
            val top = Store.goals(c).maxOfOrNull { Store.liveStreak(it) } ?: 0
            v.setTextViewText(R.id.w_streak, "🔥 $top")
            v.setTextViewText(R.id.w_habits, if (due.isEmpty()) "No habits today" else "$done of ${due.size} habits done")
            v.setProgressBar(R.id.w_bar, 100, if (due.isEmpty()) 0 else 100 * done / due.size, false)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 10, Intent(c, MainActivity::class.java), flags))

            val h = primary(c)
            if (h != null) {
                v.setViewVisibility(R.id.w_b1, View.VISIBLE)
                v.setTextViewText(R.id.w_b1, "${h.emoji} +${h.step}")
                v.setOnClickPendingIntent(R.id.w_b1, bc(c, 11, Intent(c, WidgetActionReceiver::class.java)
                    .setAction("log").putExtra("id", h.id).putExtra("d", h.step)))
            } else v.setViewVisibility(R.id.w_b1, View.GONE)

            val g = Store.goals(c).firstOrNull { t < it.end && it.last != t }
            if (g != null) {
                v.setViewVisibility(R.id.w_b2, View.VISIBLE)
                v.setTextViewText(R.id.w_b2, "✓ ${g.title.take(12)}")
                v.setOnClickPendingIntent(R.id.w_b2, bc(c, 12, Intent(c, WidgetActionReceiver::class.java)
                    .setAction("checkin").putExtra("id", g.id)))
            } else v.setViewVisibility(R.id.w_b2, View.GONE)

            v.setOnClickPendingIntent(R.id.w_b3, PendingIntent.getActivity(c, 13,
                Intent(c, MainActivity::class.java).putExtra("go", "focus"), flags))
            return v
        }
    }
}

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        val intent = i ?: return
        val id = intent.getStringExtra("id") ?: return
        when (intent.action) {
            "log" -> Store.hAdd(c, id, intent.getIntExtra("d", 1))
            "checkin" -> Store.checkIn(c, id)
        }
        LifeWidget.refresh(c)
    }
}

/** Quick Settings tile: one tap adds your quick-log amount (e.g. +250 ml water). */
class QuickLogTile : TileService() {
    override fun onStartListening() { paint() }
    override fun onClick() {
        val h = LifeWidget.primary(this)
        if (h == null) toast(this, "Add a counter habit in LifeLock first")
        else { Store.hAdd(this, h.id, h.step); LifeWidget.refresh(this) }
        paint()
    }
    private fun paint() {
        val t = qsTile ?: return
        val h = LifeWidget.primary(this)
        if (h == null) {
            t.label = "Quick log"; t.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) t.subtitle = "No habit yet"
        } else {
            val v = Store.hLog(this, h.id)
            t.label = "${h.emoji} +${h.step}"
            t.state = if (Store.met(h, v)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) t.subtitle = "$v/${h.target} ${h.unit}".trim()
        }
        t.updateTile()
    }
}

/** Quick Settings tile: checks in your first pending goal. */
class CheckInTile : TileService() {
    override fun onStartListening() { paint() }
    override fun onClick() {
        val t = Store.today()
        val g = Store.goals(this).firstOrNull { t < it.end && it.last != t }
        if (g != null) { Store.checkIn(this, g.id); LifeWidget.refresh(this) } else toast(this, "Nothing left to check in today")
        paint()
    }
    private fun paint() {
        val tile = qsTile ?: return
        val t = Store.today()
        val g = Store.goals(this).firstOrNull { t < it.end && it.last != t }
        tile.label = "Check in"
        tile.state = if (g != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = g?.title ?: "All done"
        tile.updateTile()
    }
}

/** Quick Settings tile: opens LifeLock on the focus-session screen (a confirmation step, so you never lock yourself by accident). */
class FocusTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val left = Store.focusUntil(this) - System.currentTimeMillis()
        tile.label = "Focus"
        tile.state = if (left > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (left > 0) "${left / 60000 + 1} min left" else "Start"
        tile.updateTile()
    }
    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).putExtra("go", "focus").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 14, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        else startActivityAndCollapse(i)
    }
}
