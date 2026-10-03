package com.example.gametranslator

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object OcrHelper {

    private const val TAG = "OcrHelper"

    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap): String {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val text = visionText.text.trim()
                        Log.d(TAG, "Распознано: $text")
                        cont.resume(text)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Ошибка ML Kit: ${e.message}", e)
                        cont.resume("")
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Исключение: ${e.message}", e)
            ""
        }
    }
}