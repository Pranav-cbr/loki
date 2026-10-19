package com.loki.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to YOUR backend (see /backend). The app never holds an AI provider key,
 * only a revocable per-device token.
 */
class CloudAi {
    suspend fun ask(prompt: String, s: LokiSettings): String? = withContext(Dispatchers.IO) {
        if (!s.backendUrl.startsWith("https://")) return@withContext null
        try {
            val c = (URL(s.backendUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (s.backendToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${s.backendToken}")
            }
            c.outputStream.use {
                it.write(JSONObject().put("prompt", prompt).put("lang", s.language).toString().toByteArray())
            }
            if (c.responseCode !in 200..299) return@withContext null
            JSONObject(c.inputStream.bufferedReader().readText()).optString("reply").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
