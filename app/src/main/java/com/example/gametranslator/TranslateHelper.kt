package com.example.gametranslator

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object TranslateHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun translate(apiKey: String, text: String): String {
        if (apiKey.isBlank() || text.isBlank()) return text
        return try {
            val body = FormBody.Builder()
                .add("text", text)
                .add("source_lang", "JA")
                .add("target_lang", "RU")
                .build()

            val request = Request.Builder()
                .url("https://api-free.deepl.com/v2/translate")
                .post(body)
                .header("Authorization", "DeepL-Auth-Key $apiKey")
                .build()

            val response = client.newCall(request).execute()
            val json = response.body?.string() ?: return text
            val obj = JSONObject(json)
            val translations = obj.getJSONArray("translations")
            translations.getJSONObject(0).getString("text")
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}