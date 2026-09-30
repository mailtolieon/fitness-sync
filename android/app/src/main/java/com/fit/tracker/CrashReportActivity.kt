package com.fit.tracker

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast

/**
 * CrashReportActivity: Standalone crash handler screen.
 * Runs in a separate process (:crash) without external dependencies.
 * If any uncaught exception happens anywhere in the app, this screen displays
 * the full error log and lets the user copy it instead of showing Samsung "keeps stopping".
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val errorDetails = intent.getStringExtra("error_details")
            ?: "Unknown error (no details provided)"

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            setBackgroundColor(Color.WHITE)
        }

        val titleView = TextView(this).apply {
            text = "Fitness Sync - Diagnostic Crash Log"
            textSize = 20f
            setTextColor(Color.parseColor("#D32F2F"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 16)
        }

        val subtitleView = TextView(this).apply {
            text = "The application encountered an unexpected runtime exception. The complete diagnostic stack trace is captured below. Please tap 'Copy Error Log' and share it with the developer."
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, 24)
        }

        val copyButton = Button(this).apply {
            text = "📋 Copy Full Error Log"
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setTextColor(Color.WHITE)
            setPadding(32, 24, 32, 24)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Fitness Sync Crash Log", errorDetails)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@CrashReportActivity, "Copied crash log to clipboard!", Toast.LENGTH_LONG).show()
            }
        }

        val restartButton = Button(this).apply {
            text = "🔄 Try Restarting App"
            setPadding(32, 24, 32, 24)
            setOnClickListener {
                val restartIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (restartIntent != null) {
                    restartIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(restartIntent)
                }
                finish()
            }
        }

        val spacer = Space(this).apply {
            minimumHeight = 24
        }

        val logView = TextView(this).apply {
            text = errorDetails
            textSize = 11f
            setTextColor(Color.parseColor("#212121"))
            typeface = Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val scrollView = ScrollView(this).apply {
            addView(logView)
        }

        rootLayout.addView(titleView)
        rootLayout.addView(subtitleView)
        rootLayout.addView(copyButton)
        rootLayout.addView(Space(this).apply { minimumHeight = 12 })
        rootLayout.addView(restartButton)
        rootLayout.addView(spacer)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }
}
