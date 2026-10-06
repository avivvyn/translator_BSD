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
import java.util.concurrent.atomic.AtomicBoolean

object MangaOcrHelper {

    private const val TAG = "MangaOcr"

    private var env: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var decoderSession: OrtSession? = null

    // vocab.txt: id -> строка символа. Загружается из assets при initialize.
    private var vocab: List<String> = emptyList()

    private const val IMAGE_SIZE = 224
    private const val MAX_LENGTH = 200
    private const val DECODER_START_TOKEN = 2L
    private const val EOS_TOKEN = 3L
    private const val PAD_TOKEN = 0L

    // Mean/Std из preprocessor_config.json (0.5 / 0.5)
    private val MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val STD  = floatArrayOf(0.5f, 0.5f, 0.5f)

    private val initialized = AtomicBoolean(false)

    /** Готов ли OCR к работе. Проверяется в ScreenCaptureService. */
    fun isReady(): Boolean = initialized.get()

    /**
     * Инициализация: копирует .onnx в filesDir (если ещё не скопированы),
     * загружает vocab.txt и создаёт ORT-сессии.
     */
    fun initialize(context: Context) {
        Log.d(TAG, ">>> initialize() ВЫЗВАН")

        if (initialized.get()) {
            Log.d(TAG, ">>> уже инициализирован")
            return
        }

        synchronized(this) {
            if (initialized.get()) return

            try {
                Log.d(TAG, ">>> OrtEnvironment.getEnvironment()")
                env = OrtEnvironment.getEnvironment()

                Log.d(TAG, ">>> Загружаю vocab.txt из assets")
                vocab = context.assets.open("vocab.txt").bufferedReader(Charsets.UTF_8)
                    .useLines { it.toList() }
                Log.d(TAG, ">>> vocab.txt загружен, размер = ${vocab.size}")

                Log.d(TAG, ">>> Копирую encoder_model.onnx")
                val encoderFile = copyAssetToFiles(context, "encoder_model.onnx")
                Log.d(TAG, ">>> encoder_model.onnx готов: ${encoderFile.length()} байт")

                Log.d(TAG, ">>> Копирую decoder_model.onnx")
                val decoderFile = copyAssetToFiles(context, "decoder_model.onnx")
                Log.d(TAG, ">>> decoder_model.onnx готов: ${decoderFile.length()} байт")

                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }

                Log.d(TAG, ">>> Создаю encoderSession")
                encoderSession = env!!.createSession(encoderFile.absolutePath, options)
                Log.d(TAG, ">>> encoderSession создан")
                Log.d(TAG, ">>> encoder inputs  = ${encoderSession!!.inputNames}")
                Log.d(TAG, ">>> encoder outputs = ${encoderSession!!.outputNames}")

                Log.d(TAG, ">>> Создаю decoderSession")
                decoderSession = env!!.createSession(decoderFile.absolutePath, options)
                Log.d(TAG, ">>> decoderSession создан")
                Log.d(TAG, ">>> decoder inputs  = ${decoderSession!!.inputNames}")
                Log.d(TAG, ">>> decoder outputs = ${decoderSession!!.outputNames}")

                initialized.set(true)
                Log.d(TAG, ">>> Manga OCR ГОТОВ К РАБОТЕ")
            } catch (e: Throwable) {
                Log.e(TAG, ">>> ОШИБКА ИНИЦИАЛИЗАЦИИ: ${e.message}", e)
                initialized.set(false)
                throw e
            }
        }
    }

    /**
     * Копирует большой .onnx из assets во внутреннюю папку. Стримит чанками, чтобы не словить OOM.
     */
    private fun copyAssetToFiles(context: Context, name: String): File {
        val target = File(context.filesDir, name)
        // Если уже скопирован и размер совпадает — переиспользуем
        val assetSize = context.assets.openFd(name).use { it.length }
        if (target.exists() && target.length() == assetSize) {
            Log.d(TAG, "$name уже скопирован ранее, размер совпадает")
            return target
        }

        Log.d(TAG, "Копирую $name ($assetSize байт)...")
        context.assets.open(name).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024) // 64 КБ
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                output.flush()
                Log.d(TAG, "Скопировано $name, всего $total байт")
            }
        }
        return target
    }

    /**
     * Распознаёт японский текст с bitmap. Возвращает строку (или "" при ошибке).
     */
    fun recognize(bitmap: Bitmap): String {
        if (!initialized.get()) {
            Log.e(TAG, "recognize(): ещё не инициализирован")
            return ""
        }

        return try {
            val pixels = preprocess(bitmap)

            // 1) Encoder forward
            val encInputName = encoderSession!!.inputNames.first() // должно быть "pixel_values"
            Log.d(TAG, "encoder input name = $encInputName")

            val inputBuffer = FloatBuffer.allocate(pixels.size)
            inputBuffer.put(pixels)
            inputBuffer.rewind()

            val encoderInput = OnnxTensor.createTensor(
                env, inputBuffer,
                longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
            )

            val encoderResult = encoderSession!!.run(mapOf(encInputName to encoderInput))
            Log.d(TAG, "encoder output names = ${encoderResult.map { it.key }}")

            // 2) Greedy decoding
            val tokens = generateTokens(encoderResult)

            encoderInput.close()
            encoderResult.close()

            val text = decodeTokens(tokens)
            Log.d(TAG, "recognize() -> tokens=$tokens text='$text'")
            text
        } catch (e: Throwable) {
            Log.e(TAG, "recognize() ошибка: ${e.message}", e)
            ""
        }
    }

    /**
     * Препроцессинг: resize до 224×224, нормализация (x/255 - 0.5) / 0.5, CHW.
     */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        val out = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        val planeSize = IMAGE_SIZE * IMAGE_SIZE
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f

            out[i]             = (r - MEAN[0]) / STD[0]
            out[planeSize + i]     = (g - MEAN[1]) / STD[1]
            out[2 * planeSize + i] = (b - MEAN[2]) / STD[2]
        }
        return out
    }

    /**
     * Greedy-генерация токенов. Без past_key_values — медленно, но надёжно.
     */
    private fun generateTokens(encoderResult: OrtSession.Result): List<Long> {
        // Берём первый выход encoder'а (обычно last_hidden_state)
        val encoderOut = encoderResult[0] as OnnxTensor
        Log.d(TAG, "encoder output shape = ${encoderOut.info.shape.contentToString()}")

        val tokens = mutableListOf(DECODER_START_TOKEN)
        val decoderInputNames = decoderSession!!.inputNames
        Log.d(TAG, "decoder input names = $decoderInputNames")

        // Ищем имя входа для encoder_hidden_states
        val encoderStatesName = decoderInputNames.firstOrNull {
            it.contains("encoder_hidden", ignoreCase = true) || it == "encoder_hidden_states"
        } ?: decoderInputNames.firstOrNull { it != "input_ids" }

        // Имя входа для input_ids
        val inputIdsName = decoderInputNames.firstOrNull {
            it.contains("input_ids", ignoreCase = true)
        } ?: decoderInputNames.first()

        Log.d(TAG, "inputIdsName=$inputIdsName, encoderStatesName=$encoderStatesName")

        for (step in 0 until MAX_LENGTH) {
            val longBuf = LongBuffer.allocate(tokens.size)
            longBuf.put(tokens.toLongArray())
            longBuf.rewind()

            val inputIds = OnnxTensor.createTensor(
                env, longBuf, longArrayOf(1, tokens.size.toLong())
            )

            val inputs = mutableMapOf<String, OnnxTensor>()
            inputs[inputIdsName] = inputIds
            if (encoderStatesName != null) {
                inputs[encoderStatesName] = encoderOut
            }

            val outputs = decoderSession!!.run(inputs)
            val logitsTensor = outputs[0].value as OnnxTensor
            val shape = logitsTensor.info.shape
            Log.d(TAG, "step=$step logits shape=${shape.contentToString()}")

            val logitsBuf = logitsTensor.floatBuffer
            val vocabSize = shape.last().toInt()
            val seqLen = if (shape.size >= 2) shape[shape.size - 2].toInt() else 1
            val lastIdx = (seqLen - 1) * vocabSize

            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (v in 0 until vocabSize) {
                val value = logitsBuf.get(lastIdx + v)
                if (value > maxVal) {
                    maxVal = value
                    maxIdx = v
                }
            }

            inputIds.close()
            outputs.close()

            val nextToken = maxIdx.toLong()
            Log.d(TAG, "step=$step nextToken=$nextToken")
            if (nextToken == EOS_TOKEN || nextToken == PAD_TOKEN) break
            tokens.add(nextToken)
        }

        return tokens
    }

    /**
     * Преобразует список id в строку через vocab.txt.
     */
    private fun decodeTokens(tokens: List<Long>): String {
        val sb = StringBuilder()
        for (t in tokens) {
            // Пропускаем служебные
            if (t == DECODER_START_TOKEN || t == EOS_TOKEN || t == PAD_TOKEN) continue
            val idx = t.toInt()
            if (idx in vocab.indices) {
                val s = vocab[idx]
                if (s.startsWith("[") || s.startsWith("<")) continue // спец-токены
                sb.append(s)
            }
        }
        return sb.toString()
    }
}