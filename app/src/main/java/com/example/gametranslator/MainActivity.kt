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

        // 1) Ссылки на View — в самом начале, после setContentView
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val startBtn    = findViewById<Button>(R.id.startButton)
        val stopBtn     = findViewById<Button>(R.id.stopButton)
        val regionBtn   = findViewById<Button>(R.id.regionButton)

        Log.d("MAIN", "apiKeyInput = $apiKeyInput")
        Log.d("MAIN", "startBtn    = $startBtn")
        Log.d("MAIN", "stopBtn     = $stopBtn")
        Log.d("MAIN", "regionBtn   = $regionBtn")

        // 2) SharedPreferences для ключа
        val prefs: SharedPreferences =
            getSharedPreferences("settings", Context.MODE_PRIVATE)

        apiKeyInput.setText(prefs.getString("deepl_key", ""))

        // 3) Кнопка Start — сохранение ключа и запрос проекции экрана
        startBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter DeepL API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("deepl_key", key).apply()

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow overlay", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }

        // 4) Кнопка выбора области (плавающая кнопка)
        regionBtn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Разреши оверлей", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }
            startService(Intent(this@MainActivity, FloatingButtonService::class.java))
            Toast.makeText(this, "Плавающая кнопка ⚙ включена", Toast.LENGTH_LONG).show()
        }

        // 5) Кнопка Stop
        stopBtn.setOnClickListener {
            stopService(Intent(this, ScreenCaptureService::class.java))
            stopService(Intent(this, OverlayService::class.java))
            stopService(Intent(this, FloatingButtonService::class.java))
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
        }

        // 6) Инициализация Manga OCR в фоновом потоке — В САМОМ КОНЦЕ,
        //    чтобы случайно не вложить туда UI-код
        Thread {
            try {
                MangaOcrHelper.initialize(this@MainActivity)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Manga OCR готов",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "unknown"
                Log.e("MAIN", "Ошибка инициализации Manga OCR", e)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Manga OCR: $errorMsg",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
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
            Toast.makeText(this, "Started", Toast.LENGTH_LONG).show()
        }
    }
}