package com.example.gametranslator

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var buttonView: TextView
    private var frameView: FrameOverlayView? = null

    // Размеры экрана
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        // === Плавающая кнопка ⚙ ===
        buttonView = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xAA333333.toInt())
            setPadding(30, 30, 30, 30)
        }

        val btnType = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            btnType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        buttonView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = btnParams.x
                    initialY = btnParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (kotlin.math.abs(dx) > 15 || kotlin.math.abs(dy) > 15) isDragging = true
                    if (isDragging) {
                        btnParams.x = initialX + dx.toInt()
                        btnParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(buttonView, btnParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) toggleFrameOverlay()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(buttonView, btnParams)
    }

    private fun toggleFrameOverlay() {
        if (frameView != null) {
            closeFrameOverlay()
        } else {
            openFrameOverlay()
        }
    }

    private fun openFrameOverlay() {
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        // Загружаем сохранённую область (или значение по умолчанию — низ экрана)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val left = prefs.getFloat("region_left", screenW * 0.05f)
        val top = prefs.getFloat("region_top", screenH * 0.65f)
        val right = prefs.getFloat("region_right", screenW * 0.95f)
        val bottom = prefs.getFloat("region_bottom", screenH * 0.95f)

        frameView = FrameOverlayView(this, left, top, right, bottom) {
            closeFrameOverlay()
        }

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(frameView, params)
    }

    private fun closeFrameOverlay() {
        frameView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { }
        }
        frameView = null
    }

    override fun onDestroy() {
        closeFrameOverlay()
        if (::buttonView.isInitialized) {
            try { windowManager.removeView(buttonView) } catch (e: Exception) { }
        }
        super.onDestroy()
    }

    // ============================================================
    //  Оверлей с рамкой — прямо поверх игры
    // ============================================================
    inner class FrameOverlayView(
        context: Context,
        left: Float, top: Float, right: Float, bottom: Float,
        private val onDone: () -> Unit
    ) : View(context) {

        private var selLeft = left
        private var selTop = top
        private var selRight = right
        private var selBottom = bottom

        private var dragMode = NONE
        private var lastX = 0f
        private var lastY = 0f
        private val touchSlop = 80f

        // Кнопки управления
        private var doneBtnRect = RectF()
        private var cancelBtnRect = RectF()

        private val framePaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        private val fillPaint = Paint().apply {
            color = 0x2200FF00
            style = Paint.Style.FILL
        }
        private val handlePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val btnPaint = Paint().apply {
            color = 0xCC222222.toInt()
            style = Paint.Style.FILL
        }
        private val btnTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 60f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        companion object {
            const val NONE = 0
            const val MOVE = 1
            const val LEFT = 2
            const val TOP = 3
            const val RIGHT = 4
            const val BOTTOM = 5
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val w = width.toFloat()
            val h = height.toFloat()

            // Затемнение фона
            val darkPaint = Paint().apply { color = 0x66000000 }
            canvas.drawRect(0f, 0f, w, h, darkPaint)

            // Прозрачное «окно» в области рамки
            canvas.drawRect(selLeft, selTop, selRight, selBottom, fillPaint)

            // Зелёная рамка
            canvas.drawRect(selLeft, selTop, selRight, selBottom, framePaint)

            // Уголки (маркеры для перетаскивания)
            val hs = 40f
            canvas.drawRect(selLeft - hs/2, selTop - hs/2, selLeft + hs/2, selTop + hs/2, handlePaint)
            canvas.drawRect(selRight - hs/2, selTop - hs/2, selRight + hs/2, selTop + hs/2, handlePaint)
            canvas.drawRect(selLeft - hs/2, selBottom - hs/2, selLeft + hs/2, selBottom + hs/2, handlePaint)
            canvas.drawRect(selRight - hs/2, selBottom - hs/2, selRight + hs/2, selBottom + hs/2, handlePaint)

            // Кнопка ✓ (снизу слева)
            doneBtnRect.set(60f, h - 200f, 260f, h - 60f)
            canvas.drawRoundRect(doneBtnRect, 20f, 20f, btnPaint)
            canvas.drawText("✓", doneBtnRect.centerX(), doneBtnRect.centerY() + 22f, btnTextPaint)

            // Кнопка ✕ (снизу справа)
            cancelBtnRect.set(w - 260f, h - 200f, w - 60f, h - 60f)
            canvas.drawRoundRect(cancelBtnRect, 20f, 20f, btnPaint)
            canvas.drawText("✕", cancelBtnRect.centerX(), cancelBtnRect.centerY() + 22f, btnTextPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Проверяем нажатие на кнопки
                    if (doneBtnRect.contains(x, y)) {
                        saveRegion()
                        onDone()
                        return true
                    }
                    if (cancelBtnRect.contains(x, y)) {
                        onDone()
                        return true
                    }
                    // Иначе — таскаем рамку
                    lastX = x
                    lastY = y
                    dragMode = when {
                        kotlin.math.abs(x - selLeft) < touchSlop -> LEFT
                        kotlin.math.abs(x - selRight) < touchSlop -> RIGHT
                        kotlin.math.abs(y - selTop) < touchSlop -> TOP
                        kotlin.math.abs(y - selBottom) < touchSlop -> BOTTOM
                        x > selLeft && x < selRight && y > selTop && y < selBottom -> MOVE
                        else -> NONE
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lastX
                    val dy = y - lastY
                    when (dragMode) {
                        MOVE -> {
                            selLeft += dx; selRight += dx
                            selTop += dy; selBottom += dy
                        }
                        LEFT -> selLeft = (selLeft + dx).coerceAtMost(selRight - 100f)
                        RIGHT -> selRight = (selRight + dx).coerceAtLeast(selLeft + 100f)
                        TOP -> selTop = (selTop + dy).coerceAtMost(selBottom - 100f)
                        BOTTOM -> selBottom = (selBottom + dy).coerceAtLeast(selTop + 100f)
                    }
                    lastX = x
                    lastY = y
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    dragMode = NONE
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun saveRegion() {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat("region_left", selLeft)
                .putFloat("region_top", selTop)
                .putFloat("region_right", selRight)
                .putFloat("region_bottom", selBottom)
                .apply()
        }
    }
}