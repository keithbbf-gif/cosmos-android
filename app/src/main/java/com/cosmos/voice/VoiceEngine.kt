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

    /** True while a SpeechService is live. Used by driving mode's auto-restart
     *  to tell "still listening" apart from "died silently". */
    val isRunning: Boolean get() = speechService != null

    /** Start continuous listening. Throws IOException if the mic cannot open.
     *  Idempotent: a second start() while running is a no-op, so the driving-mode
     *  auto-restart path can call it defensively.
     *
     *  grammarJson: a VOSK grammar — a JSON array of allowed phrases plus
     *  "[unk]" (see VoiceGrammar.commandGrammarJson). Non-null = COMMAND mode:
     *  the recognizer can only ever emit those phrases (or [unk]), which kills
     *  the small-model free-form hallucination ("let's look at that it looks
     *  like to me") on marginal mic audio. Null = OPEN dictation. Switching
     *  modes requires stop() then start() with the other value — the grammar
     *  is baked into the Recognizer at construction. */
    fun start(grammarJson: String? = null) {
        if (speechService != null) return
        val m = model ?: throw IllegalStateException("VOSK model not loaded")
        val recognizer =
            if (grammarJson != null) Recognizer(m, 16000.0f, grammarJson)
            else Recognizer(m, 16000.0f)
        val service = SpeechService(recognizer, 16000.0f)
        speechService = service
        service.startListening(this)
    }

    fun stop() {
        speechService?.let {
            it.stop()
            // Release the AudioRecord too — without this, repeated stop/start
            // cycles (driving mode restarts after every error) can exhaust
            // audio inputs and make the next start() fail.
            try {
                it.shutdown()
            } catch (e: Exception) {
                // best-effort release
            }
        }
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

    // ---- final-result dedupe ----
    // One utterance can surface through BOTH onResult (continuous decode) and
    // onFinalResult (fired when recognition pauses/stops) — without dedupe a
    // single spoken command reaches onFinal twice and POSTs twice. Suppress an
    // identical final arriving within the dedupe window; a genuinely repeated
    // command more than 2s later still goes through.
    private var lastFinalText = ""
    private var lastFinalAtMs = 0L

    private fun deliverFinal(text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (text == lastFinalText && now - lastFinalAtMs < DEDUPE_WINDOW_MS) return
        lastFinalText = text
        lastFinalAtMs = now
        onFinal(text)
    }

    override fun onResult(hypothesis: String?) {
        deliverFinal(extract(hypothesis, "text"))
    }

    override fun onFinalResult(hypothesis: String?) {
        deliverFinal(extract(hypothesis, "text"))
    }

    override fun onError(exception: Exception?) {
        onErr(exception?.message ?: "unknown voice error")
    }

    override fun onTimeout() {
        onErr("voice timeout")
    }

    private companion object {
        const val DEDUPE_WINDOW_MS = 2_000L
    }
}
