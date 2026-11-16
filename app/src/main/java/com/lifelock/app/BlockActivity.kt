package com.lifelock.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb

/**
 * The lock screen. Deliberately built from plain Views instead of Compose: it opens in a few
 * milliseconds, uses very little memory, and has no animation, so showing it costs almost no battery.
 */
class BlockActivity : Activity() {
    private var timer: CountDownTimer? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Pal.mode = Store.theme(this).let { if (it == 2 || it == 4) it else 1 }
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    override fun onBackPressed() = goHome()

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(s: String, fill: Int, text: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = s; textSize = 15f; setTextColor(text); gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(fill, 16)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        setOnClickListener { onClick() }
    }

    private fun render() {
        timer?.cancel()
        val pkg = intent.getStringExtra("pkg") ?: ""
        val kind = intent.getStringExtra("kind") ?: ""
        val msg = intent.getStringExtra("msg") ?: ""
        val key = intent.getStringExtra("key")
        val pm = packageManager
        val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
        val ink = Pal.ink.toArgb()
        val tint = when (kind) { "focus", "goal" -> Pal.accent; "schedule" -> Pal.accent2; else -> Pal.orange }.toArgb()
        val tag = when (kind) { "focus" -> "FOCUS SESSION"; "goal" -> "GOAL"; "schedule" -> "SCHEDULE"; else -> "DAILY LIMIT" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(72), dp(28), dp(40))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.glow.toArgb(), Pal.bg.toArgb()))
        }
        try {
            root.addView(ImageView(this).apply {
                setImageDrawable(pm.getApplicationIcon(pkg))
                layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
            })
        } catch (e: Exception) { }
        root.addView(label(tag, 12f, tint, true).apply {
            background = rounded((tint and 0x00FFFFFF) or 0x30000000, 10)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) }
        })
        root.addView(label("$name is locked", 28f, ink, true).apply { setPadding(0, dp(14), 0, 0) })
        root.addView(label(msg, 16f, Pal.text2.toArgb()).apply { setPadding(0, dp(10), 0, 0); setLineSpacing(0f, 1.2f) })
        val why = Store.why(this)
        if (why.isNotEmpty()) root.addView(label("“$why”", 18f, ink, true).apply {
            background = rounded((ink and 0x00FFFFFF) or 0x12000000, 18)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) }
        })
        root.addView(button("Go home", Pal.accent.toArgb(), 0xFFFFFFFF.toInt()) { goHome() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(32)
        })

        if (kind == "limit" && key != null && Store.passesLeft(this) > 0) {
            val pass = button("Think about it… 10s", Pal.card2.toArgb(), ink) { }
            pass.alpha = 0.5f
            root.addView(pass)
            timer = object : CountDownTimer(10000, 1000) {
                override fun onTick(ms: Long) { pass.text = "Think about it… ${ms / 1000 + 1}s" }
                override fun onFinish() {
                    pass.alpha = 1f
                    pass.text = "Use a pass (+${Store.passMin(this@BlockActivity)} min) · ${Store.passesLeft(this@BlockActivity)} left"
                    pass.setOnClickListener {
                        if (Store.usePass(this@BlockActivity, key)) {
                            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                            finish()
                        }
                    }
                }
            }.start()
        }
        root.addView(label("App ID: $pkg", 11f, Pal.muted.toArgb()).apply { setPadding(0, dp(20), 0, 0) })
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Pal.bg.toArgb()); addView(root) })
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
