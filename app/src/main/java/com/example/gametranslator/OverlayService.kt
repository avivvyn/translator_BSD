package com.example.gametranslator

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        private var textView: TextView? = null
        fun updateText(text: String) {
            textView?.post { textView?.text = text }
        }
    }

    private lateinit var windowManager: WindowManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        textView = TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            setPadding(24, 24, 24, 24)
            text = "Waiting..."
        }

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 100
        }

        windowManager.addView(textView, params)
    }

    override fun onDestroy() {
        textView?.let { windowManager.removeView(it) }
        textView = null
        super.onDestroy()
    }
}