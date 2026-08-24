package com.cosmos.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Observable UI state (plain Compose state holders, read via .value). */
class AppState {
    val baseUrl = mutableStateOf("http://192.168.1.107:8791")
    val token = mutableStateOf("")
    val connStatus = mutableStateOf("not connected")
    val modelStatus = mutableStateOf("checking")
    val modelProgress = mutableStateOf(0)
    val ttsStatus = mutableStateOf("checking")
    val listening = mutableStateOf(false)
    val partial = mutableStateOf("")
    val sessionId = mutableStateOf<String?>(null)
    val pendingConfirmId = mutableStateOf<String?>(null)
    val pendingTranscript = mutableStateOf<String?>(null)
    val console = mutableStateListOf<String>()
    val showSettings = mutableStateOf(true)

    // ---- driving mode ----
    val drivingMode = mutableStateOf(false)
    val netStatus = mutableStateOf("unknown")
    val queueSize = mutableStateOf(0)

    // ---- feedback (visual phase + haptics toggle) ----
    // Compose-observable mirrors of the @Volatile flags in MainActivity, so the
    // UI can actually recompose on them. Written on the main thread.
    val speaking = mutableStateOf(false)   // TTS is audibly talking
    val thinking = mutableStateOf(false)   // a POST to COSMOS is in flight
    val hapticsOn = mutableStateOf(true)   // settings toggle, persisted
}

/** The one visual phase the mic button + big label reflect. Priority when
 *  several are true at once (driving mode keeps the mic hot): SPEAKING >
 *  THINKING > LISTENING > IDLE. */
enum class VoicePhase { IDLE, LISTENING, THINKING, SPEAKING }

class MainActivity : ComponentActivity() {

    private val state = AppState()

    // Speech OUT. DEFAULT = sherpaTts, the bundled sherpa-onnx offline engine
    // (Piper VITS voice) — speaks with ZERO device-TTS/Google dependency, so a
    // stripped phone still talks. `tts` (android.speech.tts) is an OPTIONAL
    // fallback used only while the bundled voice is downloading/loading, and
    // only if a device engine actually exists (systemTtsOk).
    private var sherpaTts: TtsEngine? = null
    private var tts: TextToSpeech? = null
    @Volatile private var systemTtsOk = false

    private var voice: VoiceEngine? = null

    // Eyes-free haptic cues (tick / double-tick / buzz / confirm pulses).
    private lateinit var haptics: Haptics

    // Offline queue: transcripts that failed to POST, flushed when signal returns.
    private lateinit var queue: OfflineQueue
    @Volatile private var flushing = false

    // Driving-mode background poll (/status every ~20s) + connectivity monitor.
    private var pollJob: Job? = null
    private var connectivity: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastServerUp: Boolean? = null

    // TTS speaking state, written by the TTS engine thread, read on main.
    // currentUtteranceKind: "reply" | "confirm" | "cue" | "" (idle).
    @Volatile private var ttsSpeaking = false
    @Volatile private var currentUtteranceKind = ""

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                log("Mic permission denied — voice input is disabled until granted.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("cosmos", Context.MODE_PRIVATE)
        state.baseUrl.value = prefs.getString("base_url", state.baseUrl.value) ?: state.baseUrl.value
        state.token.value = prefs.getString("token", "") ?: ""

        queue = OfflineQueue(prefs)
        state.queueSize.value = queue.size

        state.hapticsOn.value = prefs.getBoolean("haptics_on", true)
        haptics = Haptics(this)
        haptics.enabled = state.hapticsOn.value

        // Optional fallback engine only — absent on a stripped phone, and that
        // is fine: the bundled sherpa-onnx voice (prepareTtsVoice) is the default.
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                systemTtsOk = true
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        ttsSpeaking = true
                        runOnUiThread { state.speaking.value = true }
                    }

                    override fun onDone(utteranceId: String?) {
                        ttsSpeaking = false
                        currentUtteranceKind = ""
                        // DRIVING: the recognizer must never silently stay dead
                        // after we finish talking — kick it back on if it died.
                        runOnUiThread {
                            state.speaking.value = false
                            if (state.drivingMode.value) restartMicIfNeeded()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        ttsSpeaking = false
                        currentUtteranceKind = ""
                        runOnUiThread { state.speaking.value = false }
                    }
                })
            }
        }

        registerNetworkMonitor()
        prepareTtsVoice()

        state.modelStatus.value = if (ModelManager.isReady(this)) "ready (not loaded)" else "not downloaded"

        setContent {
            MaterialTheme {
                AppScreen(
                    state = state,
                    onConnect = { testConnection() },
                    onMic = { toggleMic() },
                    onConfirm = { confirmPending() },
                    onSave = { saveSettings() },
                    onDriving = { setDrivingMode(!state.drivingMode.value) },
                    onHaptics = { on -> setHaptics(on) }
                )
            }
        }

        log("COSMOS Voice v0.1 — set the server URL, tap Connect, then tap MIC. " +
            "Tap DRIVING MODE (or say \"driving mode on\") for hands-free.")
    }

    override fun onDestroy() {
        pollJob?.cancel()
        netCallback?.let { cb ->
            try {
                connectivity?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // already unregistered — fine
            }
        }
        voice?.stop()
        sherpaTts?.shutdown()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- speech output ----------

    /**
     * Download (first run, ~20 MB, with progress) then load the bundled
     * sherpa-onnx Piper voice. Speaking is gated on this: until ready, the
     * device engine fills in IF one exists; otherwise utterances are dropped
     * with a log (the text still lands in the console either way).
     */
    private fun prepareTtsVoice() {
        state.ttsStatus.value =
            if (TtsModelManager.isReady(this)) "loading..." else "preparing voice..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!TtsModelManager.isReady(this@MainActivity)) {
                    withContext(Dispatchers.Main) {
                        log("Preparing voice (~20 MB download, one time). Needs any internet connection once.")
                    }
                    TtsModelManager.download(this@MainActivity) { pct, phase ->
                        runOnUiThread { state.ttsStatus.value = "$phase $pct%" }
                    }
                }
                val engine = TtsEngine(
                    // Same contract the android.speech.tts listener had: the
                    // flags drive the confirm echo-guard, and onDone kicks the
                    // driving-mode mic back on after we finish talking.
                    onStart = { _ ->
                        ttsSpeaking = true
                        runOnUiThread { state.speaking.value = true }
                    },
                    onDone = { _ ->
                        ttsSpeaking = false
                        currentUtteranceKind = ""
                        runOnUiThread {
                            state.speaking.value = false
                            if (state.drivingMode.value) restartMicIfNeeded()
                        }
                    }
                )
                engine.init(this@MainActivity)
                withContext(Dispatchers.Main) {
                    sherpaTts = engine
                    state.ttsStatus.value = "ready (offline)"
                    log("Offline voice ready (sherpa-onnx, Piper amy). No device TTS engine needed.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    state.ttsStatus.value = "error: ${e.message?.take(80)}"
                    log(
                        "VOICE PREPARE FAILED: ${e.message} — will retry next launch." +
                            if (systemTtsOk) " Using the device TTS engine meanwhile."
                            else " No device TTS engine either; replies are text-only."
                    )
                }
            }
        }
    }

    /**
     * Speak through TTS. kind:
     *  - "reply"   normal answer (interrupts whatever is playing)
     *  - "confirm" the yes/no prompt — barge-in is DISABLED for it and finals
     *              arriving mid-prompt are ignored (echo guard, see below)
     *  - "cue"     short status blip ("Listening.", "Offline.") — queued behind
     *              current speech instead of clobbering it
     * add=true queues behind current speech regardless of kind (used when
     * flushing the offline queue so results read out in order).
     *
     * DEFAULT engine: the bundled sherpa-onnx voice (offline, no device engine
     * needed). android.speech.tts is only the stand-in while the voice model
     * is still downloading, and only when the device actually has an engine.
     */
    private fun speak(text: String, kind: String = "reply", add: Boolean = false) {
        val flush = !(add || kind == "cue") // reply/confirm interrupt; cue/add queue behind

        val engine = sherpaTts
        if (engine != null && engine.isReady) {
            currentUtteranceKind = kind
            ttsSpeaking = true // optimistic; confirmed by onStart, cleared by onDone
            state.speaking.value = true
            engine.speak(text, "$kind-${System.currentTimeMillis()}", flush)
            return
        }

        val t = tts
        if (t != null && systemTtsOk) {
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            currentUtteranceKind = kind
            ttsSpeaking = true // optimistic; confirmed by onStart, cleared by onDone
            state.speaking.value = true
            val r = t.speak(text, mode, null, "$kind-${System.currentTimeMillis()}")
            if (r != TextToSpeech.SUCCESS) {
                // Engine rejected it: clear the flags immediately, otherwise the
                // confirm echo-guard would ignore finals forever (onDone never fires).
                ttsSpeaking = false
                currentUtteranceKind = ""
                state.speaking.value = false
            }
            return
        }

        log("(voice not ready — not spoken: \"${text.take(60)}\")")
    }

    /** Stop whichever engine is talking (barge-in). The caller clears the
     *  speaking flags itself, mirroring the old bare tts.stop() path. */
    private fun stopSpeaking() {
        sherpaTts?.stop()
        tts?.stop()
    }

    /** Short spoken status cue — driving mode only (silent at the desk). */
    private fun cue(text: String) {
        if (state.drivingMode.value) speak(text, kind = "cue")
    }

    // ---------- driving mode ----------

    private fun setDrivingMode(on: Boolean) {
        if (state.drivingMode.value == on) {
            if (on) speak("Driving mode is already on.")
            return
        }
        state.drivingMode.value = on
        if (on) {
            // Eyes-free: screen stays on so Android never pauses us mid-drive.
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            log("DRIVING MODE ON — hands-free, continuous listening, everything spoken.")
            speak("Driving mode on.")
            startPolling()
            ensureMicOn()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pollJob?.cancel()
            pollJob = null
            log("DRIVING MODE OFF.")
            speak("Driving mode off.")
            // Mic stays as-is; the MIC button controls it again.
        }
    }

    /** Poll /status every ~20s while driving: detects signal coming back and
     *  flushes the offline queue. Cancelled when driving mode turns off. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                pingAndFlush()
                delay(20_000)
            }
        }
    }

    private suspend fun pingAndFlush() {
        val base = state.baseUrl.value.trim().trimEnd('/')
        if (base.isBlank()) return
        val up = withContext(Dispatchers.IO) {
            try {
                // Any HTTP answer (even 500) means the server is reachable.
                CosmosClient.getStatus(base, state.token.value.trim())
                true
            } catch (e: Exception) {
                false
            }
        }
        onServerReachable(up)
    }

    /** Main-thread only. Speaks "connected"/"offline" on TRANSITIONS, never
     *  repeats, and triggers a queue flush whenever the server is reachable. */
    private fun onServerReachable(up: Boolean) {
        val was = lastServerUp
        lastServerUp = up
        state.netStatus.value = if (up) "online" else "offline"
        if (was != null && was != up) {
            cue(if (up) "Connected." else "Offline.")
        }
        if (up) tryFlush()
    }

    private fun registerNetworkMonitor() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivity = cm
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Network is back — verify the SERVER is actually reachable
                    // (a bar of LTE is not a reachable COSMOS) and flush.
                    runOnUiThread { lifecycleScope.launch { pingAndFlush() } }
                }

                override fun onLost(network: Network) {
                    runOnUiThread { onServerReachable(false) }
                }
            }
            netCallback = cb
            cm.registerDefaultNetworkCallback(cb)
        } catch (e: Exception) {
            log("Network monitor unavailable: ${e.message}")
        }
    }

    // ---------- settings ----------

    private fun saveSettings() {
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putString("base_url", state.baseUrl.value.trim())
            .putString("token", state.token.value.trim())
            .apply()
    }

    /** Haptics on/off toggle — persisted, default ON. */
    private fun setHaptics(on: Boolean) {
        state.hapticsOn.value = on
        haptics.enabled = on
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putBoolean("haptics_on", on)
            .apply()
        if (on) haptics.micStart() // one tick so the switch itself is felt
        log(if (on) "Haptics ON." else "Haptics OFF.")
    }

    private fun testConnection() {
        saveSettings()
        val base = state.baseUrl.value.trim().trimEnd('/')
        if (base.isBlank()) {
            log("Set the server URL first.")
            return
        }
        state.connStatus.value = "connecting..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val o = CosmosClient.getStatus(base, state.token.value.trim())
                withContext(Dispatchers.Main) {
                    if (o.has("http_status")) {
                        state.connStatus.value = "HTTP ${o.optInt("http_status")}"
                    } else {
                        val ready = o.optBoolean("ready", false)
                        val tree = o.optString("tree_id")
                        var s = "connected · ready=$ready"
                        if (tree.isNotBlank()) s += " · tree=$tree"
                        state.connStatus.value = s
                    }
                    log("STATUS: ${o.toString().take(300)}")
                    onServerReachable(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    state.connStatus.value = "failed: ${e.message?.take(80)}"
                    log("CONNECT FAILED: ${e.message}")
                    onServerReachable(false)
                }
            }
        }
    }

    // ---------- voice model ----------

    private fun downloadModel() {
        val st = state.modelStatus.value
        if (st == "downloading" || st == "unzipping") return
        state.modelStatus.value = "downloading"
        state.modelProgress.value = 0
        log("Downloading voice model (~40 MB, one time). Needs any internet connection once.")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ModelManager.download(this@MainActivity) { pct, phase ->
                    runOnUiThread {
                        state.modelProgress.value = pct
                        state.modelStatus.value = phase
                    }
                }
                withContext(Dispatchers.Main) {
                    state.modelStatus.value = "ready (not loaded)"
                    log("Voice model ready. Tap MIC to start.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    state.modelStatus.value = "error: ${e.message?.take(80)}"
                    log("MODEL DOWNLOAD FAILED: ${e.message}")
                }
            }
        }
    }

    private fun ensureEngineLoaded(onReady: () -> Unit) {
        val v = voice
        if (v != null && v.isLoaded) {
            onReady()
            return
        }
        state.modelStatus.value = "loading model..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val engine = VoiceEngine(
                    onPartial = { p ->
                        runOnUiThread {
                            state.partial.value = p
                            // ================== BARGE-IN ==================
                            // The user started talking while we were talking:
                            // stop the TTS so they can be heard. Exception:
                            // while the CONFIRM prompt is playing we do NOT
                            // barge-in — without guaranteed echo cancellation
                            // the phone can hear its own prompt, and cutting
                            // the prompt off on our own echo (then treating
                            // that echo as a "no") would cancel real actions.
                            if (p.isNotBlank() && ttsSpeaking && currentUtteranceKind != "confirm") {
                                stopSpeaking()
                                ttsSpeaking = false
                                currentUtteranceKind = ""
                                state.speaking.value = false
                            }
                        }
                    },
                    onFinal = { text -> runOnUiThread { onFinalTranscript(text) } },
                    onErr = { msg ->
                        runOnUiThread {
                            log("VOICE ERROR: $msg")
                            if (state.drivingMode.value) {
                                // DRIVING: never let the recognizer die silently.
                                // Tear down, wait a beat, restart.
                                voice?.stop()
                                state.listening.value = false
                                lifecycleScope.launch {
                                    delay(1_000)
                                    restartMicIfNeeded()
                                }
                            } else {
                                state.listening.value = false
                            }
                        }
                    }
                )
                engine.load(ModelManager.modelDir(this@MainActivity).absolutePath)
                withContext(Dispatchers.Main) {
                    voice = engine
                    state.modelStatus.value = "loaded"
                    onReady()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    state.modelStatus.value = "error: ${e.message?.take(80)}"
                    log("MODEL LOAD FAILED: ${e.message}")
                }
            }
        }
    }

    // ---------- mic ----------

    private fun toggleMic() {
        if (state.listening.value) {
            voice?.stop()
            state.listening.value = false
            state.partial.value = ""
            log("Mic off.")
            return
        }
        ensureMicOn()
    }

    /** Turn the mic on if it is off, requesting permission when needed. */
    private fun ensureMicOn() {
        if (state.listening.value) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        if (!ModelManager.isReady(this)) {
            downloadModel()
            return
        }
        ensureEngineLoaded {
            try {
                voice?.start()
                state.listening.value = true
                haptics.micStart() // short tick: "I'm hearing you" — no glance needed
                log("Listening... speak, pause to send. Tap again to stop.")
                cue("Listening.")
            } catch (e: Exception) {
                log("MIC START FAILED: ${e.message}")
                state.listening.value = false
            }
        }
    }

    /** Driving-mode watchdog: if the recognizer is not running, restart it.
     *  Called after every TTS utterance finishes and after voice errors.
     *  Retries every 2s while driving mode stays on; stops retrying the
     *  moment driving mode turns off. */
    private fun restartMicIfNeeded() {
        if (!state.drivingMode.value) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val v = voice ?: return
        if (v.isRunning) {
            state.listening.value = true
            return
        }
        try {
            v.start()
            state.listening.value = true
            log("(mic auto-restarted)")
        } catch (e: Exception) {
            log("MIC RESTART FAILED: ${e.message} — retrying in 2s")
            lifecycleScope.launch {
                delay(2_000)
                restartMicIfNeeded()
            }
        }
    }

    // ---------- transcript handling ----------

    private fun onFinalTranscript(text: String) {
        state.partial.value = ""
        val raw = text.trim()
        if (raw.isBlank()) return
        val norm = VoiceGrammar.normalize(raw)

        // ================= PENDING-CONFIRM STATE MACHINE =================
        // Two states, keyed off state.pendingConfirmId:
        //
        //   IDLE            (pendingConfirmId == null)
        //     a final transcript is a NEW command -> falls through below.
        //
        //   AWAITING_YESNO  (pendingConfirmId != null)
        //     the next final is the ANSWER to "Confirm: ...?" and is NEVER
        //     treated as a new command.
        //       yes-words ("yes"/"confirm"/"do it"/...) -> re-POST the original
        //           transcript with the single-use confirm_id nonce.
        //       anything else -> drop the nonce and say "Cancelled."
        //
        // Echo guard: a final that COMPLETES while the confirm prompt is still
        // being spoken is almost certainly the phone hearing its own prompt
        // (no AEC guarantee on the VOSK mic path). Acting on it would cancel —
        // or worse, confirm — on garbage, so it is discarded.
        // =================================================================
        if (state.pendingConfirmId.value != null) {
            if (ttsSpeaking && currentUtteranceKind == "confirm") {
                log("(ignored while confirm prompt speaking: \"$raw\")")
                return
            }
            log("YOU: $raw")
            if (VoiceGrammar.isYes(norm)) {
                confirmPending()
            } else {
                state.pendingConfirmId.value = null
                state.pendingTranscript.value = null
                log("CANCELLED (heard: \"$raw\"). Nonce dropped.")
                speak("Cancelled.")
            }
            return
        }

        log("YOU: $raw")

        // Voice control of driving mode itself ("driving mode on/off").
        if (VoiceGrammar.isDrivingOff(norm)) {
            setDrivingMode(false)
            return
        }
        if (VoiceGrammar.isDrivingOn(norm)) {
            setDrivingMode(true)
            return
        }

        if (state.drivingMode.value) {
            // Junk gate: road noise, filler words ("huh", "uh huh"), and
            // fragments with no real content and no COSMOS verb are dropped
            // entirely — never sent, never spoken about.
            if (VoiceGrammar.isJunk(norm)) {
                log("(ignored as noise: \"$raw\")")
                return
            }
            // Everything else goes to COSMOS — the SERVER classifies it and
            // returns `kind`; the SPOKEN reply is moderated in handleReply
            // from that classification, never from a client-side verb guess
            // (the guess mis-fired on Vosk output and suppressed real command
            // replies to "Noted."). The verb guess survives only as a
            // best-effort hint that skips the pre-send "Got it, thinking."
            // cue for probable ambient speech.
            send(raw, null, quiet = !VoiceGrammar.startsWithKnownVerb(norm))
        } else {
            send(raw, null, quiet = false)
        }
    }

    // ---------- COSMOS API ----------

    /**
     * POST a transcript to COSMOS.
     *
     * `quiet` is a best-effort CLIENT hint ("this is probably ambient speech")
     * and does exactly one thing: it suppresses the pre-send "Got it,
     * thinking." cue in driving mode. It does NOT moderate the spoken reply —
     * that decision is made in handleReply from the SERVER's `kind`
     * classification, because the client guess (Vosk casing/tokenization) has
     * wrongly quieted real commands before.
     */
    private fun send(transcript: String, confirmId: String?, quiet: Boolean = false) {
        val base = state.baseUrl.value.trim().trimEnd('/')
        if (base.isBlank()) {
            log("Set the server URL first.")
            if (state.drivingMode.value) speak("No server URL is set.")
            return
        }
        if (state.drivingMode.value && !quiet && confirmId == null) {
            cue("Got it, thinking.")
        }
        haptics.sent()             // double-tick: recognized and on its way
        state.thinking.value = true // THINKING phase until the server answers
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject().put("transcript", transcript)
                state.sessionId.value?.let { body.put("session_id", it) }
                if (confirmId != null) body.put("confirm_id", confirmId)
                val resp = CosmosClient.postVoice(base, state.token.value.trim(), body)
                withContext(Dispatchers.Main) {
                    state.thinking.value = false
                    onServerReachable(true)
                    handleReply(transcript, resp)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    state.thinking.value = false
                    log("SEND FAILED: ${e.message}")
                    onServerReachable(false)
                    onSendFailed(transcript, confirmId)
                }
            }
        }
    }

    /**
     * ==================== OFFLINE-QUEUE STATE MACHINE ====================
     * A failed POST never loses the command:
     *  - Plain transcript -> enqueued (persisted to SharedPreferences) and
     *    announced "Saved, no signal..." The queue is flushed IN ORDER on the
     *    next successful /status ping (20s poll while driving) or network-up
     *    event; each flushed reply is spoken as it lands (QUEUE_ADD so they
     *    read out sequentially). An item is removed only AFTER its POST
     *    succeeds, so a flush interrupted by signal loss keeps the remainder.
     *  - Confirm re-POST -> NOT queued. The confirm_id nonce is single-use
     *    and may be stale/expired by the time signal returns; silently firing
     *    a destructive action minutes later is exactly what a driver would
     *    not expect. The user re-issues the command instead.
     * =====================================================================
     */
    private fun onSendFailed(transcript: String, confirmId: String?) {
        if (confirmId != null) {
            log("Confirm re-POST failed — nonce dropped (single-use, not queued).")
            if (state.drivingMode.value) {
                speak("Couldn't reach COSMOS. The confirmation was not sent. Ask again when we're back online.")
            }
        } else {
            queue.add(transcript)
            state.queueSize.value = queue.size
            log("QUEUED offline (${queue.size} pending): \"$transcript\"")
            if (state.drivingMode.value) {
                speak("Saved, no signal. I'll send it when we're back.")
            }
        }
    }

    /** Flush the offline queue in order. One flush at a time; stops (keeping
     *  the remainder) the moment a send fails again. */
    private fun tryFlush() {
        if (flushing || queue.size == 0) return
        flushing = true
        val base = state.baseUrl.value.trim().trimEnd('/')
        if (base.isBlank()) {
            flushing = false
            return
        }
        val n = queue.size
        cue("Back online. Sending $n saved ${if (n == 1) "command" else "commands"}.")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val item = queue.peek() ?: break
                    val body = JSONObject().put("transcript", item)
                    state.sessionId.value?.let { body.put("session_id", it) }
                    val resp = CosmosClient.postVoice(base, state.token.value.trim(), body)
                    withContext(Dispatchers.Main) {
                        queue.removeFirst()
                        state.queueSize.value = queue.size
                        log("FLUSHED: \"$item\"")
                        // add=true: flushed replies queue up behind each other
                        // instead of each one cutting off the last. Flushed
                        // dictation stays silent (server `kind` rule applies).
                        handleReply(item, resp, add = true)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    log("FLUSH stopped, ${queue.size} still queued: ${e.message}")
                }
            } finally {
                flushing = false
            }
        }
    }

    private fun handleReply(
        original: String,
        o: JSONObject,
        add: Boolean = false
    ) {
        // Carry the session forward — the sid IS the conversation.
        o.optString("session_id").takeIf { it.isNotBlank() }?.let { state.sessionId.value = it }

        val spoken = o.optString("spoken")
        // The SERVER's classification of this utterance:
        // "command" | "ask" | "query" | "dictation" | "" (absent/unknown).
        val kind = o.optString("kind")
        val refused = o.optBoolean("refused", false) ||
            (o.has("ok") && !o.optBoolean("ok", false))

        val needsConfirm = o.optBoolean("needs_confirm", false)
        if (needsConfirm) {
            val cid = o.optString("confirm_id")
            if (cid.isNotBlank()) {
                state.pendingConfirmId.value = cid
                state.pendingTranscript.value = original
                // Two strong pulses: a consequential confirm is FELT, not just heard.
                haptics.alert()
                log("NEEDS CONFIRM — say yes/no (or tap CONFIRM). Never auto-run.")
                // Voice confirm: the driver never has to tap. Spoken with kind
                // "confirm" -> barge-in disabled + mid-prompt finals ignored
                // (see the state machine in onFinalTranscript).
                val summary = spoken.ifBlank { original }
                speak(
                    "Confirm: $summary. Say yes to confirm, or no to cancel.",
                    kind = "confirm",
                    add = add
                )
                return
            }
        }

        // Haptic for the reply itself: refusal gets the strong double-pulse,
        // everything else a soft "reply arrived" buzz (fires alongside TTS start).
        if (refused) haptics.alert() else haptics.reply()

        val shown = if (spoken.isNotBlank()) spoken else o.toString().take(400)
        log("COSMOS: $shown")
        if (o.has("ok")) {
            log(if (o.optBoolean("ok", false)) "[ok]" else "[refused]")
        }
        if (o.has("http_status")) {
            log("[http ${o.optInt("http_status")}]")
        }

        // ==================== SPOKEN MODERATION ====================
        // Decided by the SERVER's `kind`, never by a client-side verb guess.
        // Rule: pure dictation is SILENT. kind == "dictation" (and not
        // refused) produces NO TTS at all — no "Noted.", no cue — in every
        // mode (driving, desk, and flushed queue items alike). It is already
        // logged to the console above, which is the only trace it leaves.
        // Everything else — command / ask / query / unknown / refused, and
        // the needs_confirm prompt handled above — speaks IN FULL, as before.
        if (kind == "dictation" && !refused) {
            log("(dictation — noted silently, not spoken)")
            return
        }
        val toSpeak: String? = when {
            spoken.isNotBlank() -> spoken
            state.drivingMode.value && refused -> "COSMOS refused that."
            state.drivingMode.value && o.has("http_status") ->
                "Server error ${o.optInt("http_status")}."
            state.drivingMode.value -> "Done."
            else -> null
        }
        if (toSpeak != null) {
            speak(toSpeak, add = add)
        }
    }

    private fun confirmPending() {
        val cid = state.pendingConfirmId.value ?: return
        val original = state.pendingTranscript.value ?: ""
        state.pendingConfirmId.value = null
        state.pendingTranscript.value = null
        log("CONFIRMING (nonce ${cid.take(8)}...)")
        cue("Confirming.")
        send(original, cid)
    }

    // ---------- console ----------

    private fun log(line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        state.console.add(0, "[$t] $line")
        while (state.console.size > 300) {
            state.console.removeAt(state.console.size - 1)
        }
    }
}

// ============================== UI ==============================

@Composable
fun AppScreen(
    state: AppState,
    onConnect: () -> Unit,
    onMic: () -> Unit,
    onConfirm: () -> Unit,
    onSave: () -> Unit,
    onDriving: () -> Unit,
    onHaptics: (Boolean) -> Unit
) {
    // One visual phase drives the mic button, pulse ring, spinner, and the
    // big glanceable label. Priority: SPEAKING > THINKING > LISTENING > IDLE.
    val phase = when {
        state.speaking.value -> VoicePhase.SPEAKING
        state.thinking.value -> VoicePhase.THINKING
        state.listening.value -> VoicePhase.LISTENING
        else -> VoicePhase.IDLE
    }
    val listenColor = Color(0xFFB3261E)  // red — listening
    val thinkColor = Color(0xFFF9A825)   // amber — waiting on the server
    val speakColor = Color(0xFF1565C0)   // blue — TTS talking

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {

            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "COSMOS Voice",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { state.showSettings.value = !state.showSettings.value }) {
                    Text(if (state.showSettings.value) "hide setup" else "setup")
                }
            }
            Text(
                text = "server: ${state.connStatus.value} · net: ${state.netStatus.value}" +
                    (if (state.queueSize.value > 0) " · queued: ${state.queueSize.value}" else ""),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "voice model: ${state.modelStatus.value}" +
                    "  ·  voice out: ${state.ttsStatus.value}" +
                    (state.sessionId.value?.let { "  ·  session ${it.take(8)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall
            )

            // Big top-level DRIVING MODE toggle (also voice: "driving mode on/off")
            Button(
                onClick = onDriving,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.drivingMode.value) Color(0xFF2E7D32)
                    else Color(0xFF49454F)
                )
            ) {
                Text(
                    text = if (state.drivingMode.value) "DRIVING MODE ON" else "DRIVING MODE OFF",
                    fontSize = 18.sp
                )
            }

            // Settings
            if (state.showSettings.value) {
                OutlinedTextField(
                    value = state.baseUrl.value,
                    onValueChange = { state.baseUrl.value = it; onSave() },
                    label = { Text("Server base URL (http://host:8791)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = state.token.value,
                    onValueChange = { state.token.value = it; onSave() },
                    label = { Text("Bearer token (blank = no auth)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Text(
                        text = "Haptic feedback (vibration cues)",
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.hapticsOn.value,
                        onCheckedChange = onHaptics
                    )
                }
                Button(
                    onClick = onConnect,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Connect / Test")
                }
            }

            // Model download progress
            val ms = state.modelStatus.value
            if (ms == "downloading" || ms == "unzipping") {
                Text(
                    text = "Preparing voice model ($ms ${state.modelProgress.value}%)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                LinearProgressIndicator(
                    progress = state.modelProgress.value / 100f,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }

            // Live partial transcript
            Text(
                text = if (state.partial.value.isBlank()) " " else state.partial.value,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )

            // Big mic button (tap to toggle) — the button IS the status light:
            //   IDLE      neutral (theme primary)
            //   LISTENING red + breathing pulse ring
            //   THINKING  amber + spinner around the button
            //   SPEAKING  blue + faster pulse ring
            val micColor = animateColorAsState(
                targetValue = when (phase) {
                    VoicePhase.LISTENING -> listenColor
                    VoicePhase.THINKING -> thinkColor
                    VoicePhase.SPEAKING -> speakColor
                    VoicePhase.IDLE -> MaterialTheme.colorScheme.primary
                },
                label = "micColor"
            )
            Box(
                modifier = Modifier.fillMaxWidth().height(150.dp),
                contentAlignment = Alignment.Center
            ) {
                // Breathing pulse ring while listening (slow) or speaking (faster).
                // Composed only in those phases, so it costs nothing when idle.
                if (phase == VoicePhase.LISTENING || phase == VoicePhase.SPEAKING) {
                    val period = if (phase == VoicePhase.LISTENING) 900 else 550
                    val pulse = rememberInfiniteTransition(label = "pulse")
                    val ringScale = pulse.animateFloat(
                        initialValue = 1.0f,
                        targetValue = 1.22f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = period),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "ringScale"
                    )
                    val ringAlpha = pulse.animateFloat(
                        initialValue = 0.35f,
                        targetValue = 0.08f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = period),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "ringAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(124.dp)
                            .scale(ringScale.value)
                            .background(
                                micColor.value.copy(alpha = ringAlpha.value),
                                CircleShape
                            )
                    )
                }
                Button(
                    onClick = onMic,
                    shape = CircleShape,
                    modifier = Modifier.size(120.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = micColor.value)
                ) {
                    Text(
                        text = if (state.listening.value) "STOP" else "MIC",
                        fontSize = 20.sp
                    )
                }
                // Spinner ringing the button while a POST is in flight.
                if (phase == VoicePhase.THINKING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(136.dp),
                        color = thinkColor,
                        strokeWidth = 4.dp
                    )
                }
            }

            // Big glanceable state label — readable while driving.
            Text(
                text = when (phase) {
                    VoicePhase.LISTENING -> "Listening…"
                    VoicePhase.THINKING -> "Thinking…"
                    VoicePhase.SPEAKING -> "Speaking…"
                    VoicePhase.IDLE -> "Tap to talk"
                },
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = when (phase) {
                    VoicePhase.LISTENING -> listenColor
                    VoicePhase.THINKING -> thinkColor
                    VoicePhase.SPEAKING -> speakColor
                    VoicePhase.IDLE -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )

            // Confirm (single-use nonce, never auto-run). Voice ("yes"/"no")
            // always works when pending; the button is the fallback.
            if (state.pendingConfirmId.value != null) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7D5260))
                ) {
                    Text("CONFIRM — run it (or say YES / NO)")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider()

            // Console (newest first)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.console) { line ->
                    Text(
                        text = line,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
