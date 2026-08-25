package com.cosmos.voice

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * CONTROL CHANNEL — the remote kill switch.
 *
 * MainActivity polls GET /api/v1/control?client_id=<id> every ~3 seconds and
 * obeys the returned flags IMMEDIATELY:
 *   { "mic_off": true }     -> authoritative STOP (same path as the red button)
 *   { "pause": true }       -> stop sending /voice POSTs (drop with a log)
 *   { "clear_queue": true } -> empty the local offline queue
 *
 * FAIL-SAFE BY CONSTRUCTION: a fetch error (server down, no route, bad JSON)
 * does NOTHING. The control channel can only ever turn things OFF — no control
 * response can start the mic, resume sending, or replay the queue. The absence
 * of the channel leaves the phone exactly as the user last set it.
 *
 * Blocking — call on Dispatchers.IO. Short timeouts so a dead server never
 * wedges the poll loop past one interval.
 */
object ControlClient {

    private const val CONNECT_TIMEOUT_MS = 3_000
    private const val READ_TIMEOUT_MS = 3_000

    /** One control fetch. Throws on any transport problem; returns the parsed
     *  JSON body (2xx) or a {"http_status": n} envelope otherwise. */
    fun fetch(baseUrl: String, token: String, clientId: String): JSONObject {
        val url = baseUrl.trimEnd('/') + "/api/v1/control?client_id=" +
            URLEncoder.encode(clientId, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val parsed = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            if (code !in 200..299) parsed.put("http_status", code)
            return parsed
        } finally {
            conn.disconnect()
        }
    }
}
