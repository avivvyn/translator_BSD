package com.example.gametranslator

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

object OcrHelper {

    private var tess: TessBaseAPI? = null

    private fun init(context: Context) {
        if (tess != null) return
        val tessDir = File(context.filesDir, "tessdata")
        if (!tessDir.exists()) tessDir.mkdirs()
        val langFile = File(tessDir, "jpn.traineddata")
        if (!langFile.exists()) {
            try {
                context.assets.open("jpn.traineddata").use { input ->
                    FileOutputStream(langFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        tess = TessBaseAPI().apply {
            init(context.filesDir.absolutePath, "jpn")
        }
    }

    fun recognize(context: Context, bitmap: Bitmap): String {
        return try {
            init(context)
            tess?.setImage(bitmap)
            tess?.utF8Text?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}