package com.cosmos.voice

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client for the COSMOS /api/v1 API.
 * Uses HttpURLConnection (no extra dependency). All calls are blocking —
 * callers must run them on Dispatchers.IO.
 */
object CosmosClient {

    fun getStatus(baseUrl: String, token: String): JSONObject =
        request("GET", baseUrl.trimEnd('/') + "/api/v1/status", token, null)

    fun postVoice(baseUrl: String, token: String, body: JSONObject): JSONObject =
        request("POST", baseUrl.trimEnd('/') + "/api/v1/voice", token, body)

    private fun request(
        method: String,
        urlStr: String,
        token: String,
        body: JSONObject?
    ): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/json")
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val parsed = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject().put("raw", text.take(500))
            }
            if (code !in 200..299) {
                parsed.put("http_status", code)
            }
            return parsed
        } finally {
            conn.disconnect()
        }
    }
}
