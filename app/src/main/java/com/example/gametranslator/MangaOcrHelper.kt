package com.example.gametranslator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

object MangaOcrHelper {

    private const val TAG = "MangaOcr"

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    private const val IMAGE_SIZE = 224
    private const val MAX_LENGTH = 300
    private const val DECODER_START_TOKEN = 2L      // <s>
    private const val EOS_TOKEN = 3L                // </s>
    private const val PAD_TOKEN = 0L
    private const val NUM_LAYERS = 2
    private const val NUM_HEADS = 12
    private const val HEAD_DIM = 64                 // 768 / 12

    fun initialize(context: Context) {
        if (encoderSession != null) return

        Log.d(TAG, "Инициализация Manga OCR...")
        env = OrtEnvironment.getEnvironment()

        val encoderFile = copyAssetToFiles(context, "encoder_model.onnx")
        val decoderFile = copyAssetToFiles(context, "decoder_model.onnx")

        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        encoderSession = env!!.createSession(encoderFile.absolutePath, options)
        decoderSession = env!!.createSession(decoderFile.absolutePath, options)

        Log.d(TAG, "Manga OCR готов")
    }

    /**
     * Копирует файл из assets во внутреннюю папку. Большие .onnx нельзя открыть напрямую из assets.
     */
    private fun copyAssetToFiles(context: Context, name: String): File {
        val target = File(context.filesDir, name)
        if (target.exists() && target.length() > 0) return target

        Log.d(TAG, "Копирую $name в filesDir...")
        context.assets.open(name).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, bufferSize = 8192)
            }
        }
        Log.d(TAG, "Скопирован $name (${target.length()} байт)")
        return target
    }

    /**
     * Распознаёт японский текст с картинки.
     */
    fun recognize(bitmap: Bitmap): String {
        if (encoderSession == null || decoderSession == null) {
            Log.e(TAG, "Сессия не инициализирована")
            return ""
        }

        return try {
            val pixelValues = preprocess(bitmap)

            // 1) Прогон encoder
            val encoderInput = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(pixelValues),
                longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
            )
            val encoderOutputs = encoderSession!!.run(
                mapOf("pixel_values" to encoderInput)
            )
            val encoderHiddenStates = encoderOutputs[0]

            // 2) Генерация токенов
            val generatedTokens = generateTokens(encoderOutputs)

            // 3) Освобождение
            encoderInput.close()
            encoderOutputs.close()

            Log.d(TAG, "Токены: $generatedTokens")
            decodeTokens(generatedTokens)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка распознавания: ${e.message}", e)
            ""
        }
    }

    private fun preprocess(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        val floatArray = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)

        // ImageNet нормализация
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f

            floatArray[i] = (r - mean[0]) / std[0]
            floatArray[IMAGE_SIZE * IMAGE_SIZE + i] = (g - mean[1]) / std[1]
            floatArray[2 * IMAGE_SIZE * IMAGE_SIZE + i] = (b - mean[2]) / std[2]
        }
        return floatArray
    }

    private fun generateTokens(encoderOutputs: ai.onnxruntime.OrtSession.Result): List<Long> {
        val tokens = mutableListOf(DECODER_START_TOKEN)

        // Кэш attention для всех слоёв
        val pastKeys = Array(NUM_LAYERS) {
            OnnxTensor.createTensor(
                env, FloatBuffer.allocate(0),
                longArrayOf(1, NUM_HEADS.toLong(), 0, HEAD_DIM.toLong())
            )
        }
        val pastValues = Array(NUM_LAYERS) {
            OnnxTensor.createTensor(
                env, FloatBuffer.allocate(0),
                longArrayOf(1, NUM_HEADS.toLong(), 0, HEAD_DIM.toLong())
            )
        }

        for (step in 0 until MAX_LENGTH) {
            val inputIds = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(tokens.toLongArray()),
                longArrayOf(1, tokens.size.toLong())
            )

            val inputs = mutableMapOf<String, OnnxTensor>()
            inputs["input_ids"] = inputIds
            inputs["encoder_hidden_states"] = encoderOutputs[0] as OnnxTensor
            for (i in 0 until NUM_LAYERS) {
                inputs["past_key_values.$i.encoder.key"] = pastKeys[i]
                inputs["past_key_values.$i.encoder.value"] = pastValues[i]
                inputs["past_key_values.$i.decoder.key"] = pastValues[i]
                inputs["past_key_values.$i.decoder.value"] = pastValues[i]
            }

            val outputs = decoderSession!!.run(inputs)
            val logits = outputs[0].value as OnnxTensor

            // Берём argmax последнего токена
            val logitsArray = logits.floatBuffer
            val vocabSize = 6144
            val seqLen = tokens.size
            val lastIdx = (seqLen - 1) * vocabSize

            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (v in 0 until vocabSize) {
                val value = logitsArray.get(lastIdx + v)
                if (value > maxVal) {
                    maxVal = value
                    maxIdx = v
                }
            }

            val nextToken = maxIdx.toLong()
            inputIds.close()
            logits.close()
            outputs.close()

            if (nextToken == EOS_TOKEN || nextToken == PAD_TOKEN) break
            tokens.add(nextToken)
        }

        pastKeys.forEach { it.close() }
        pastValues.forEach { it.close() }
        return tokens
    }

    private fun decodeTokens(tokens: List<Long>): String {
        // TODO: загрузка vocab.txt и маппинг id → символ
        return tokens.joinToString(" ") { it.toString() }
    }
}

