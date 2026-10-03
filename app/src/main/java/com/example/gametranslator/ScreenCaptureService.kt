package com.example.gametranslator

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        // ШАГ 1: Запускаем foreground-сервис с типом mediaProjection ДО всего
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, buildNotification())
        }

        // ШАГ 2: Получаем MediaProjection ПОСЛЕ startForeground
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)

        // ШАГ 3: Регистрируем callback ДО createVirtualDisplay
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        // ШАГ 4: Создаём виртуальный дисплей и начинаем цикл
        startCapture()
        startProcessingLoop()

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "capture_channel"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Capture",
                NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Game Translator")
            .setContentText("Active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private fun startCapture() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenCapture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun startProcessingLoop() {
        scope.launch {
            while (isActive) {
                try {
                    val bitmap = captureFrame()
                    if (bitmap != null) {
                        val jpText = OcrHelper.recognize(bitmap)
                        if (jpText.isNotBlank()) {
                            val apiKey = getSharedPreferences("settings", Context.MODE_PRIVATE)
                                .getString("deepl_key", "") ?: ""
                            val ruText = TranslateHelper.translate(apiKey, jpText)
                            OverlayService.updateText(ruText)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(2000)
            }
        }
    }

    private fun captureFrame(): Bitmap? {
        val img = imageReader?.acquireLatestImage() ?: return null
        return try {
            val planes = img.planes
            val buffer = planes[0].buffer
            val ps = planes[0].pixelStride
            val rs = planes[0].rowStride
            val pad = rs - ps * img.width
            val w = img.width + pad / ps
            val h = img.height
            val config = Bitmap.Config.ARGB_8888
            val bmp = Bitmap.createBitmap(w, h, config)
            bmp.copyPixelsFromBuffer(buffer)
            val full = Bitmap.createBitmap(bmp, 0, 0, w, h)

            // Обрезка по сохранённой области
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val left = prefs.getFloat("region_left", -1f)
            val top = prefs.getFloat("region_top", -1f)
            val right = prefs.getFloat("region_right", -1f)
            val bottom = prefs.getFloat("region_bottom", -1f)

            if (left < 0 || top < 0 || right <= left || bottom <= top) {
                return full
            }

            val cropLeft = left.toInt().coerceIn(0, w - 1)
            val cropTop = top.toInt().coerceIn(0, h - 1)
            val cropRight = right.toInt().coerceIn(cropLeft + 1, w)
            val cropBottom = bottom.toInt().coerceIn(cropTop + 1, h)
            val cropW = cropRight - cropLeft
            val cropH = cropBottom - cropTop

            Bitmap.createBitmap(full, cropLeft, cropTop, cropW, cropH)
        } finally {
            img.close()
        }
    }
    override fun onDestroy() {
        scope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        super.onDestroy()
    }
}