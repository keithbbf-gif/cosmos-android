package com.cosmos.voice

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.random.Random

/**
 * Minimal HTTP client for the COSMOS /api/v1 API.
 * Uses HttpURLConnection (no extra dependency). All calls are blocking —
 * callers must run them on Dispatchers.IO.
 *
 * Reliability:
 *  - every /voice POST carries a client-generated request_id (idempotency key)
 *    so a retried or offline-flushed POST can be deduped server-side
 *  - bounded retry with exponential backoff + jitter for TRANSIENT failures
 *    (I/O errors and 5xx). 4xx is never retried — the request itself is wrong.
 */
object CosmosClient {

    private const val MAX_ATTEMPTS = 3
    private const val BASE_BACKOFF_MS = 600L
    private const val JITTER_MS = 300L

    // ---- authoritative STOP support ----
    // Every open connection is tracked so STOP can sever in-flight HTTP at the
    // socket, and the abort epoch makes the retry loop bail instead of
    // re-sending a request the user just killed.
    private val active = java.util.Collections.synchronizedSet(HashSet<HttpURLConnection>())
    @Volatile private var abortEpoch = 0L

    /** Sever every in-flight request NOW and make pending retries bail.
     *  Called from the authoritative STOP path — safe from any thread. */
    fun abortAll() {
        abortEpoch += 1
        val snapshot = synchronized(active) { active.toList() }
        for (conn in snapshot) {
            try {
                conn.disconnect()
            } catch (e: Exception) {
                // already closed — fine
            }
        }
    }

    fun getStatus(baseUrl: String, token: String): JSONObject =
        request("GET", baseUrl.trimEnd('/') + "/api/v1/status", token, null)

    fun postVoice(baseUrl: String, token: String, body: JSONObject): JSONObject {
        // Idempotency: never send a /voice POST without a request_id. Callers
        // that queue requests generate their own (so the flush re-sends the
        // SAME id); this is the belt-and-braces default for everyone else.
        if (!body.has("request_id")) {
            body.put("request_id", UUID.randomUUID().toString())
        }
        return request("POST", baseUrl.trimEnd('/') + "/api/v1/voice", token, body)
    }

    /** Bounded retry wrapper. Safe for POST because every /voice body carries
     *  a request_id the server can dedupe on. */
    private fun request(
        method: String,
        urlStr: String,
        token: String,
        body: JSONObject?
    ): JSONObject {
        var lastExc: Exception? = null
        val epochAtStart = abortEpoch
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (abortEpoch != epochAtStart) {
                throw IOException("aborted by STOP")
            }
            if (attempt > 0) {
                val backoff = BASE_BACKOFF_MS * (1L shl (attempt - 1)) +
                    Random.nextLong(0, JITTER_MS)
                try {
                    Thread.sleep(backoff)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            try {
                val parsed = requestOnce(method, urlStr, token, body)
                val code = parsed.optInt("http_status", 200)
                if (code in 500..599 && attempt < MAX_ATTEMPTS - 1) {
                    continue // transient server-side failure — retry
                }
                return parsed // success, 4xx (never retried), or final 5xx
            } catch (e: IOException) {
                lastExc = e // transport failure — retry
            }
        }
        throw lastExc ?: IOException("request failed after $MAX_ATTEMPTS attempts")
    }

    private fun requestOnce(
        method: String,
        urlStr: String,
        token: String,
        body: JSONObject?
    ): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        active.add(conn)
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
            active.remove(conn)
            conn.disconnect()
        }
    }
}
