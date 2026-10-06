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
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class ScreenCaptureService : Service() {

    private val TAG = "CaptureSvc"

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, buildNotification())
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        startCapture()
        startProcessingLoop()

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "capture_channel"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Capture", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Game Translator")
            .setContentText("Захват активен")
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

        Log.d(TAG, "Создаю ImageReader $w x $h, dpi=$dpi")
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
                    if (!MangaOcrHelper.isReady()) {
                        Log.d(TAG, "OCR не готов, ждём...")
                        delay(2000)
                        continue
                    }
                    val bitmap = captureFrame()
                    if (bitmap != null) {
                        Log.d(TAG, "Кадр ${bitmap.width}x${bitmap.height}")
                        val jp = MangaOcrHelper.recognize(bitmap)
                        Log.d(TAG, "OCR результат: '$jp'")
                        if (jp.isNotBlank() && containsJapanese(jp)) {
                            val apiKey = getSharedPreferences("settings", Context.MODE_PRIVATE)
                                .getString("deepl_key", "") ?: ""
                            val ru = TranslateHelper.translate(apiKey, jp)
                            Log.d(TAG, "Перевод: '$ru'")
                            OverlayService.updateText(ru)
                        }
                    } else {
                        Log.d(TAG, "captureFrame вернул null")
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Ошибка цикла: ${e.message}", e)
                }
                delay(3000)
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
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)

            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val left = prefs.getFloat("region_left", -1f)
            val top = prefs.getFloat("region_top", -1f)
            val right = prefs.getFloat("region_right", -1f)
            val bottom = prefs.getFloat("region_bottom", -1f)

            if (left < 0 || top < 0 || right <= left || bottom <= top) return bmp

            val cl = left.toInt().coerceIn(0, w - 1)
            val ct = top.toInt().coerceIn(0, h - 1)
            val cr = right.toInt().coerceIn(cl + 1, w)
            val cb = bottom.toInt().coerceIn(ct + 1, h)
            Bitmap.createBitmap(bmp, cl, ct, cr - cl, cb - ct)
        } finally {
            img.close()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        scope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        super.onDestroy()
    }

    private fun containsJapanese(text: String): Boolean {
        for (c in text) {
            val code = c.code
            if (code in 0x3040..0x30FF) return true
            if (code in 0x4E00..0x9FAF) return true
        }
        return false
    }
}