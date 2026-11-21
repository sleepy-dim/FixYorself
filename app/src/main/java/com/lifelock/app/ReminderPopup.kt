package com.lifelock.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb

/**
 * The pop-up used by the "Wake the screen" reminder style. It lights the screen over the lock screen with big buttons.
 * Built from plain Views so it opens instantly and uses almost no battery; it closes itself after two minutes.
 */
class ReminderPopup : Activity() {
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Pal.mode = Store.theme(this).let { if (it == 2 || it == 4) it else 1 }
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(s: String, fill: Int, textColor: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = s; textSize = 17f; setTextColor(textColor); gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(fill, 18)
        setPadding(dp(16), dp(18), dp(16), dp(18))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun render() {
        h.removeCallbacksAndMessages(null)
        val type = intent.getStringExtra("type") ?: "g"
        val id = intent.getStringExtra("id") ?: ""
        val title = intent.getStringExtra("title") ?: "Reminder"
        val text = intent.getStringExtra("text") ?: ""
        val amount = intent.getIntExtra("amount", 1)
        val primary = intent.getStringExtra("label") ?: "OK"
        val ink = Pal.ink.toArgb()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(96), dp(28), dp(40))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.glow.toArgb(), Pal.bg.toArgb()))
        }
        root.addView(label("⏰", 56f, ink))
        root.addView(label(title, 28f, ink, true).apply { setPadding(0, dp(16), 0, 0) })
        root.addView(label(text, 17f, Pal.text2.toArgb()).apply { setPadding(0, dp(10), 0, dp(24)) })
        root.addView(button(primary, Pal.accent.toArgb(), 0xFFFFFFFF.toInt()) {
            val nm = getSystemService(NotificationManager::class.java)
            when (type) {
                "h" -> { Store.hAdd(this, id, amount); nm.cancel(Reminders.notifIdFor(type, id)); LifeWidget.refresh(this) }
                "g" -> {
                    if (id.isNotEmpty()) Store.checkIn(this, id)
                    nm.cancel(1); LifeWidget.refresh(this)
                }
                else -> startActivity(Intent(this, MainActivity::class.java).putExtra("go", "targets").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
        })
        root.addView(button("Snooze 10 min", Pal.card2.toArgb(), ink) {
            Reminders.snooze(this, type, id, 10)
            getSystemService(NotificationManager::class.java).cancel(Reminders.notifIdFor(type, id))
            finish()
        })
        root.addView(button("Dismiss", Pal.card.toArgb(), Pal.muted.toArgb()) { finish() })
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Pal.bg.toArgb()); addView(root) })
        h.postDelayed({ finish() }, 120000L)
    }
}
