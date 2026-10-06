package com.example.gametranslator

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQUEST_CAPTURE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MAIN", "!!!!! onCreate STARTED !!!!!")
        setContentView(R.layout.activity_main)

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val startBtn    = findViewById<Button>(R.id.startButton)
        val stopBtn     = findViewById<Button>(R.id.stopButton)
        val regionBtn   = findViewById<Button>(R.id.regionButton)

        val prefs: SharedPreferences = getSharedPreferences("settings", Context.MODE_PRIVATE)
        apiKeyInput.setText(prefs.getString("deepl_key", ""))

        // === Инициализация OCR в фоне ===
        Log.d("MAIN", ">>> Запускаю Thread для MangaOcrHelper.initialize")
        Thread {
            Log.d("MAIN", ">>> Thread начал работу")
            try {
                MangaOcrHelper.initialize(applicationContext)
                Log.d("MAIN", ">>> MangaOcrHelper.initialize ЗАВЕРШЁН УСПЕШНО")
                runOnUiThread {
                    Toast.makeText(applicationContext, "Manga OCR готов", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                Log.e("MAIN", ">>> ОШИБКА инициализации OCR: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(applicationContext, "OCR ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()

        // === Кнопки ===
        startBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Введи DeepL API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("deepl_key", key).apply()

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Разреши оверлей", Toast.LENGTH_LONG).show()
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                return@setOnClickListener
            }

            if (!MangaOcrHelper.isReady()) {
                Toast.makeText(this, "OCR ещё грузится, подожди 30 сек", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }

        regionBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Разреши оверлей", Toast.LENGTH_LONG).show()
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                return@setOnClickListener
            }
            startService(Intent(this@MainActivity, FloatingButtonService::class.java))
            Toast.makeText(this, "Плавающая кнопка включена", Toast.LENGTH_LONG).show()
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            stopService(Intent(this, OverlayService::class.java))
            stopService(Intent(this, FloatingButtonService::class.java))
            Toast.makeText(this, "Остановлено", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            ContextCompat.startForegroundService(this, intent)
            startService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Запущено", Toast.LENGTH_LONG).show()
        }
    }
}