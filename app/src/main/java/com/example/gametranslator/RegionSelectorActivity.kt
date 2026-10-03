package com.example.gametranslator

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class RegionSelectorActivity : AppCompatActivity() {

    private var selLeft = 0f
    private var selTop = 0f
    private var selRight = 0f
    private var selBottom = 0f

    private var dragMode = NONE
    private var lastX = 0f
    private var lastY = 0f
    private val touchSlop = 80f

    private lateinit var overlay: RegionOverlayView

    companion object {
        private const val NONE = 0
        private const val MOVE = 1
        private const val LEFT = 2
        private const val TOP = 3
        private const val RIGHT = 4
        private const val BOTTOM = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()

        // Загружаем сохранённую рамку, если есть
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        selLeft = prefs.getFloat("region_left", screenW * 0.05f)
        selTop = prefs.getFloat("region_top", screenH * 0.65f)
        selRight = prefs.getFloat("region_right", screenW * 0.95f)
        selBottom = prefs.getFloat("region_bottom", screenH * 0.95f)

        val root = FrameLayout(this)

        val bg = View(this)
        bg.setBackgroundColor(0x99000000.toInt())
        root.addView(bg, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        overlay = RegionOverlayView(this)
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val doneBtn = Button(this).apply {
            text = "Готово"
            setOnClickListener { saveAndFinish() }
        }
        root.addView(doneBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            bottomMargin = 80
            leftMargin = 60
        })

        val cancelBtn = Button(this).apply {
            text = "Отмена"
            setOnClickListener { finish() }
        }
        root.addView(cancelBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            bottomMargin = 80
            rightMargin = 60
        })

        setContentView(root)

        // Обработчик таскания — на overlay
        overlay.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }
    }

    private fun handleTouch(event: MotionEvent) {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
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
                overlay.invalidate()  // ← ГЛАВНОЕ ИСПРАВЛЕНИЕ
            }
        }
    }

    private fun saveAndFinish() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("region_left", selLeft)
            .putFloat("region_top", selTop)
            .putFloat("region_right", selRight)
            .putFloat("region_bottom", selBottom)
            .apply()
        finish()
    }

    inner class RegionOverlayView(context: Context) : View(context) {
        private val borderPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        private val cornerPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            canvas.drawRect(selLeft, selTop, selRight, selBottom, borderPaint)

            val size = 40f
            canvas.drawRect(selLeft - size/2, selTop - size/2, selLeft + size/2, selTop + size/2, cornerPaint)
            canvas.drawRect(selRight - size/2, selTop - size/2, selRight + size/2, selTop + size/2, cornerPaint)
            canvas.drawRect(selLeft - size/2, selBottom - size/2, selLeft + size/2, selBottom + size/2, cornerPaint)
            canvas.drawRect(selRight - size/2, selBottom - size/2, selRight + size/2, selBottom + size/2, cornerPaint)
        }
    }
}