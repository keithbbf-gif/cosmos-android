package com.cosmos.voice

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Thin wrapper around VOSK's SpeechService.
 * - load() is slow (seconds) — call on a background thread.
 * - start()/stop() toggle continuous listening; each finalized utterance is
 *   delivered via onFinal. SpeechService invokes callbacks on the main thread.
 */
class VoiceEngine(
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onErr: (String) -> Unit
) : RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null

    /** Blocking model load; call on Dispatchers.IO. */
    fun load(modelPath: String) {
        model = Model(modelPath)
    }

    val isLoaded: Boolean get() = model != null

    /** Start continuous listening. Throws IOException if the mic cannot open. */
    fun start() {
        val m = model ?: throw IllegalStateException("VOSK model not loaded")
        val recognizer = Recognizer(m, 16000.0f)
        val service = SpeechService(recognizer, 16000.0f)
        speechService = service
        service.startListening(this)
    }

    fun stop() {
        speechService?.stop()
        speechService = null
    }

    private fun extract(json: String?, key: String): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString(key, "")
        } catch (e: Exception) {
            ""
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        onPartial(extract(hypothesis, "partial"))
    }

    override fun onResult(hypothesis: String?) {
        val text = extract(hypothesis, "text")
        if (text.isNotBlank()) onFinal(text)
    }

    override fun onFinalResult(hypothesis: String?) {
        val text = extract(hypothesis, "text")
        if (text.isNotBlank()) onFinal(text)
    }

    override fun onError(exception: Exception?) {
        onErr(exception?.message ?: "unknown voice error")
    }

    override fun onTimeout() {
        onErr("voice timeout")
    }
}
