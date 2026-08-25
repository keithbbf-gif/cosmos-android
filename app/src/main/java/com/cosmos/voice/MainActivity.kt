package com.cosmos.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ======================== THE MIC STATE MACHINE ========================
 *
 * MASTER MIC TOGGLE (the authoritative control):
 *   MIC OFF  truly off. Vosk stopped, AudioRecord released, the userStopped
 *            hard gate set. NO watchdog, retry, endpoint, reconnect, or
 *            control message may start the mic. Remote mic_off and STOP
 *            (button / notification / spoken) all land here.
 *   MIC ON   an explicit user tap on the toggle — the ONLY thing that
 *            clears the stop gate. What ON means depends on the MODE:
 *
 *   MicMode.WAKE (DEFAULT — the hands-free driving mode)
 *            Vosk runs continuously and decodes LOCALLY. An utterance is
 *            only SENT to COSMOS when it starts with the wake word
 *            ("cosmos" / "hey cosmos"); the wake word is stripped and the
 *            remainder is the command. EVERYTHING ELSE IS DROPPED ON THE
 *            PHONE — never sent, never queued, never spends. After a
 *            reply, a ~10s follow-up window accepts ONE utterance without
 *            the wake word (natural back-and-forth), then gating resumes.
 *            SUB-MODE (requireWake, settings toggle, default ON): turning
 *            "Require Cosmos trigger word" OFF makes WAKE an OPEN listener —
 *            every utterance that passes the junk gate is sent, no wake word.
 *            This changes ONLY which utterances are sent; master OFF, the
 *            late-final drop, send()'s stop-gate, and the remote control
 *            channel are identical in both sub-modes.
 *   MicMode.TAP  tap-to-talk: one tap captures ONE utterance, auto-
 *            endpoints on Vosk final / ~1s silence, then mic idle.
 *   MicMode.PTT  push-to-talk: hold the mic button; release sends.
 *
 * MicState is the REALITY of the mic right now:
 *   OFF            closed (master off, or TAP/PTT idle between captures)
 *   LISTENING_T2T  one-utterance capture (TAP mode)
 *   LISTENING_PTT  held capture (PTT mode)
 *   LISTENING_WAKE continuous local decode, wake-word gated (WAKE mode)
 *
 * STOP (big red button, notification action, remote mic_off, spoken "stop")
 * is AUTHORITATIVE: master OFF from any state; kills recognizer, TTS,
 * in-flight HTTP, and the local queue; sets userStopped so every watchdog/
 * retry is a no-op until the user explicitly toggles MIC ON again.
 * The old "CONTINUOUS mode that sent every utterance" is REMOVED — the
 * wake-word gate replaces it (that mode was the ambient-capture flood).
 * =======================================================================
 */
enum class MicState { OFF, LISTENING_T2T, LISTENING_PTT, LISTENING_WAKE }

/** How the mic captures while the master toggle is ON. Persisted. */
enum class MicMode { WAKE, TAP, PTT }

/** Observable UI state (plain Compose state holders, read via .value). */
class AppState {
    val baseUrl = mutableStateOf("http://192.168.1.107:8791")
    val token = mutableStateOf("")
    val connStatus = mutableStateOf("not connected")
    val modelStatus = mutableStateOf("checking")
    val modelProgress = mutableStateOf(0)
    val ttsStatus = mutableStateOf("checking")
    val micState = mutableStateOf(MicState.OFF)
    val partial = mutableStateOf("")
    val sessionId = mutableStateOf<String?>(null)
    val pendingConfirmId = mutableStateOf<String?>(null)
    val pendingTranscript = mutableStateOf<String?>(null)
    val console = mutableStateListOf<String>()
    val showSettings = mutableStateOf(true)

    // ---- network / queue ----
    val netStatus = mutableStateOf("unknown")
    val queueSize = mutableStateOf(0)
    // Default OFF = send-now-or-discard. ON = bounded offline queue
    // (5 items / 120s TTL, backlog dropped on fresh start).
    val offlineQueueOn = mutableStateOf(false)
    // Remote control channel said "pause": no /voice POSTs until it clears.
    val pausedByControl = mutableStateOf(false)

    // ---- feedback (visual phase + haptics toggle) ----
    val speaking = mutableStateOf(false)   // TTS is audibly talking
    val thinking = mutableStateOf(false)   // a POST to COSMOS is in flight
    val hapticsOn = mutableStateOf(true)   // settings toggle, persisted

    // ---- recognition mode ----
    // false = COMMAND mode (grammar-constrained VOSK — no hallucinated
    // sentences); true = DICTATE mode (open recognition for one utterance).
    val dictateMode = mutableStateOf(false)
    // Settings toggle: force the built-in phone mic even when a Bluetooth
    // headset is connected (BT SCO narrowband mics wreck recognition).
    val phoneMicOn = mutableStateOf(true)

    // ---- stream / session / speech ----
    // The COSMOS stream every payload is tagged with (project/stream field).
    val stream = mutableStateOf("plumbing")
    // Server-side reply verbosity: "brief" | "normal" | "full".
    val verbosity = mutableStateOf("normal")
    // TTS rate multiplier, 0.5..2.0.
    val speechRate = mutableStateOf(1.0f)

    // ---- master mic + mode ----
    // The single authoritative MIC ON / MIC OFF toggle. Persisted as a
    // remembered choice, but the mic NEVER auto-starts on launch/reboot —
    // turning it ON is always an explicit user tap in this session.
    val masterOn = mutableStateOf(false)
    // WAKE (default, hands-free) | TAP | PTT. Persisted.
    val micMode = mutableStateOf(MicMode.WAKE)
    // Follow-up window open: ONE utterance is accepted without the wake word.
    val followUp = mutableStateOf(false)
    // WAKE mode only: TRUE (default) = only "Cosmos ..." utterances are sent;
    // FALSE = OPEN listening — while MIC is ON, every utterance that passes
    // the junk gate is sent, no trigger word needed. TAP/PTT are unaffected.
    // Gates only WHICH utterances are sent — never whether the mic can be
    // stopped: master OFF / STOP / remote control are untouched. Persisted.
    val requireWake = mutableStateOf(true)
    // How tolerant the wake match is: STRICT = exact "cosmos" only; NORMAL
    // (default) also accepts close Vosk mishearings ("cosmo", "cosmic", ...).
    // Tighten to STRICT if road chatter false-triggers. Persisted.
    val wakeSensitivity = mutableStateOf(WakeSensitivity.NORMAL)
}

/** The one visual phase the mic button + big label reflect. Priority when
 *  several are true at once (WAKE mode keeps the mic hot): SPEAKING >
 *  THINKING > LISTENING > IDLE. */
enum class VoicePhase { IDLE, LISTENING, THINKING, SPEAKING }

class MainActivity : ComponentActivity() {

    private val state = AppState()

    // Speech OUT. DEFAULT = sherpaTts, the bundled sherpa-onnx offline engine
    // (Piper VITS voice). `tts` (android.speech.tts) is an OPTIONAL fallback
    // used only while the bundled voice is downloading/loading.
    private var sherpaTts: TtsEngine? = null
    private var tts: TextToSpeech? = null
    @Volatile private var systemTtsOk = false

    private var voice: VoiceEngine? = null

    // Eyes-free haptic cues (tick / double-tick / buzz / confirm pulses).
    private lateinit var haptics: Haptics

    // Offline queue (only fed when the offline-queue toggle is ON).
    private lateinit var queue: OfflineQueue
    @Volatile private var flushing = false

    // Stable per-install id, sent as client_id in every request and used by
    // the control channel to address this phone.
    private lateinit var clientId: String

    // Network jobs (sends + flushes) live in their own scope so the
    // authoritative STOP can cancel them without touching the UI scope.
    private val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Continuous-mode background poll (/status every ~20s) + connectivity monitor.
    private var pollJob: Job? = null
    private var controlJob: Job? = null
    private var connectivity: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var lastServerUp: Boolean? = null

    // TTS speaking state, written by the TTS engine thread, read on main.
    // currentUtteranceKind: "reply" | "confirm" | "cue" | "" (idle).
    @Volatile private var ttsSpeaking = false
    @Volatile private var currentUtteranceKind = ""
    // Last normal reply, for SAY AGAIN.
    @Volatile private var lastSpokenReply: String? = null

    // Client-side pending-confirm expiry: a stray "yes" minutes later must
    // never fire a stale nonce, even if the server would still accept it.
    private var confirmExpireJob: Job? = null

    // T2T endpointing: watches for ~1s of silence after speech and closes the
    // capture (Vosk's own final usually lands first; this is the belt).
    private var endpointJob: Job? = null
    @Volatile private var lastVoiceActivityMs = 0L

    // Remote control "pause": drop sends until the flag clears.
    @Volatile private var controlPaused = false

    // ==================== USER-INTENT MIC FLAG ====================
    // true after the user hits STOP (button, notification, spoken "stop", or
    // remote mic_off): the mic must STAY off. Every watchdog / restart timer /
    // deferred start NO-OPs while this is set — a hard stop is a hard stop.
    // Cleared ONLY by the explicit MIC ON toggle (setMasterMic(true)).
    @Volatile private var userStopped = false

    // What the user asked the mic to become when the permission prompt fired;
    // consumed by the grant callback (the grant IS the user action).
    private var pendingStart: MicState? = null

    // True while the mic button is physically held (PTT). Guards the async
    // engine-load path: if the finger lifted before the mic was ready, the
    // deferred start must NOT open a mic nobody is holding.
    @Volatile private var pttHeld = false

    // True while WAKE-mode hands-free listening is live (the mic is hot but
    // every utterance without the wake word is dropped on the phone).
    private val handsFree: Boolean
        get() = state.micState.value == MicState.LISTENING_WAKE

    // ---- follow-up window (WAKE mode) ----
    // After a spoken reply, ONE utterance within this window is accepted
    // without the wake word. Also opened (shorter) by a bare "cosmos".
    @Volatile private var followUpUntilMs = 0L
    private var followUpJob: Job? = null

    // Audible wake-heard tick (media-routed — reaches BT headphones).
    private var toneGen: ToneGenerator? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val target = pendingStart ?: micTargetForMode()
            pendingStart = null
            if (granted) {
                if (!userStopped) startListening(target)
            } else {
                // Master ON requires the permission — revert the toggle so the
                // UI never claims a mic it cannot open.
                if (state.masterOn.value && state.micMode.value == MicMode.WAKE) {
                    setMasterMic(false, "permission denied")
                }
                log("Mic permission denied — voice input is disabled until granted.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("cosmos", Context.MODE_PRIVATE)
        state.baseUrl.value = prefs.getString("base_url", state.baseUrl.value) ?: state.baseUrl.value
        // BEARER IS MEMORY-ONLY: never persisted. Scrub any token an older
        // build left in prefs.
        if (prefs.contains("token")) prefs.edit().remove("token").apply()

        clientId = prefs.getString("client_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("client_id", it).apply()
        }

        queue = OfflineQueue(prefs)
        state.queueSize.value = queue.size
        queue.loadError?.let { log("OFFLINE QUEUE: $it") }
        if (queue.droppedAtLoad > 0) {
            log("OFFLINE QUEUE: dropped ${queue.droppedAtLoad} stale item(s) from a " +
                "previous run — voice is ephemeral, old transcripts never replay.")
        }
        state.offlineQueueOn.value = prefs.getBoolean("offline_queue_on", false)

        state.hapticsOn.value = prefs.getBoolean("haptics_on", true)
        haptics = Haptics(this)
        haptics.enabled = state.hapticsOn.value

        // Default ON: BT SCO narrowband headset mics (TOZO etc.) wreck VOSK
        // recognition — keep INPUT on the phone mic; TTS OUTPUT stays on the
        // headset over A2DP.
        state.phoneMicOn.value = prefs.getBoolean("phone_mic", true)

        // Master mic + mode. The MODE is restored; the master toggle is only
        // REMEMBERED — the app always comes up MIC OFF, and the user's
        // explicit tap on the toggle is what opens the mic. Never on launch,
        // never on reboot, never on reconnect.
        state.micMode.value = try {
            MicMode.valueOf(prefs.getString("mic_mode", MicMode.WAKE.name) ?: MicMode.WAKE.name)
        } catch (e: Exception) {
            MicMode.WAKE
        }
        val rememberedMicOn = prefs.getBoolean("master_mic_on", false)
        // Trigger-word requirement (WAKE mode). Default TRUE — open listening
        // is always an explicit opt-in, never how the app comes up unasked.
        state.requireWake.value = prefs.getBoolean("require_wake", true)
        // Wake sensitivity (fuzzy wake match). Default NORMAL; STRICT is the
        // opt-in tightening for when road chatter false-triggers.
        state.wakeSensitivity.value = try {
            WakeSensitivity.valueOf(
                prefs.getString("wake_sensitivity", WakeSensitivity.NORMAL.name)
                    ?: WakeSensitivity.NORMAL.name
            )
        } catch (e: Exception) {
            WakeSensitivity.NORMAL
        }
        VoiceGrammar.wakeSensitivity = state.wakeSensitivity.value

        state.stream.value = prefs.getString("stream", "plumbing") ?: "plumbing"
        state.verbosity.value = prefs.getString("verbosity", "normal") ?: "normal"
        state.speechRate.value = prefs.getFloat("speech_rate", 1.0f)

        // Notification STOP action -> the same authoritative stop as the button.
        VoiceService.onStopAction = { performStop("notification") }

        // Optional fallback engine only — absent on a stripped phone, and that
        // is fine: the bundled sherpa-onnx voice (prepareTtsVoice) is the default.
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                systemTtsOk = true
                tts?.language = Locale.US
                tts?.setSpeechRate(state.speechRate.value)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        ttsSpeaking = true
                        runOnUiThread {
                            state.speaking.value = true
                            updateMicService()
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        ttsSpeaking = false
                        currentUtteranceKind = ""
                        runOnUiThread { onTtsFinished(utteranceId ?: "") }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        ttsSpeaking = false
                        currentUtteranceKind = ""
                        runOnUiThread {
                            state.speaking.value = false
                            updateMicService()
                        }
                    }
                })
            }
        }

        registerNetworkMonitor()
        prepareTtsVoice()
        startControlPolling()

        state.modelStatus.value = if (ModelManager.isReady(this)) "ready (not loaded)" else "not downloaded"

        setContent {
            MaterialTheme {
                AppScreen(
                    state = state,
                    buildName = BuildConfig.VERSION_NAME,
                    onConnect = { testConnection() },
                    onMicTap = { onMicTap() },
                    onPttStart = { onPttStart() },
                    onPttRelease = { onPttRelease() },
                    onStop = { performStop("STOP button") },
                    onConfirm = { confirmPending() },
                    onSave = { saveSettings() },
                    onMasterToggle = { setMasterMic(!state.masterOn.value, "MIC toggle") },
                    onMode = { m -> setMicMode(m) },
                    onHaptics = { on -> setHaptics(on) },
                    onDictate = { setDictateMode(!state.dictateMode.value) },
                    onPhoneMic = { on -> setPhoneMic(on) },
                    onRequireWake = { on -> setRequireWake(on) },
                    onWakeSensitivity = { s -> setWakeSensitivity(s) },
                    onOfflineQueue = { on -> setOfflineQueue(on) },
                    onStream = { s -> setStream(s) },
                    onBootUp = { bootUp() },
                    onNewSession = { newSession() },
                    onVerbosity = { v -> setVerbosity(v) },
                    onSpeechRate = { r -> setSpeechRate(r) },
                    onSayAgain = { sayAgain() }
                )
            }
        }

        log("COSMOS Voice v${BuildConfig.VERSION_NAME} — MIC ON = hands-free: say " +
            "\"Cosmos ...\" and only that is sent; everything else is decoded on the " +
            "phone and dropped. STOP always wins.")
        if (rememberedMicOn) {
            log("Mic was ON last session — tap MIC ON to resume (it never auto-starts).")
        }
    }

    override fun onDestroy() {
        VoiceService.onStopAction = null
        pollJob?.cancel()
        controlJob?.cancel()
        endpointJob?.cancel()
        followUpJob?.cancel()
        try {
            toneGen?.release()
        } catch (e: Exception) {
            // best-effort
        }
        toneGen = null
        netScope.cancel()
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
        VoiceService.stop(this)
        super.onDestroy()
    }

    // ==================== AUTHORITATIVE STOP ====================

    /**
     * From ANY state -> OFF. Kills the recognizer, the TTS, every in-flight
     * HTTP request, and the local queue; sets userStopped so every watchdog,
     * retry timer, and deferred start is a NO-OP until the user explicitly
     * starts the mic again. Nothing can override this.
     */
    private fun performStop(source: String) {
        userStopped = true
        pttHeld = false
        pendingStart = null
        // STOP and MIC OFF are the SAME state: the master toggle reads OFF
        // after any stop, however it arrived (button, voice, remote mic_off).
        if (state.masterOn.value) {
            state.masterOn.value = false
            getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
                .putBoolean("master_mic_on", false)
                .apply()
        }
        closeFollowUp()
        endpointJob?.cancel()
        pollJob?.cancel()
        pollJob = null
        // DELIBERATE: controlJob (the remote-control poll) is NOT cancelled —
        // the phone must keep hearing remote resume/kill after a local STOP.
        // Only onDestroy tears it down.
        confirmExpireJob?.cancel()
        state.pendingConfirmId.value = null
        state.pendingTranscript.value = null

        // Mic + recognizer.
        voice?.stop()
        state.micState.value = MicState.OFF
        state.partial.value = ""

        // Voice out.
        stopSpeaking()
        ttsSpeaking = false
        currentUtteranceKind = ""
        state.speaking.value = false

        // In-flight HTTP: sever sockets AND cancel the coroutines around them.
        CosmosClient.abortAll()
        netScope.coroutineContext.cancelChildren()
        flushing = false
        state.thinking.value = false

        // Local queue: a hard stop empties the backlog — nothing replays later.
        val cleared = queue.clear()
        state.queueSize.value = 0

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateMicService()
        haptics.alert()
        log("STOP ($source) — mic OFF, speech stopped, requests aborted" +
            (if (cleared > 0) ", $cleared queued item(s) discarded" else "") +
            ". Nothing restarts until you start it.")
    }

    /** The mic foreground service runs ONLY while actually capturing or
     *  speaking; its notification names the state and carries a STOP action.
     *  It can never keep the mic alive past STOP — performStop turns both
     *  conditions false and this tears the service down. */
    private fun updateMicService() {
        val st = state.micState.value
        val speaking = state.speaking.value
        val needed = st != MicState.OFF || speaking
        val label = when {
            st == MicState.LISTENING_WAKE ->
                if (state.requireWake.value)
                    "Hands-free — only \"Cosmos ...\" is sent. Tap STOP to end."
                else "OPEN listening — everything you say is sent. Tap STOP to end."
            st == MicState.LISTENING_PTT -> "Listening (hold-to-talk)"
            st == MicState.LISTENING_T2T -> "Listening (one utterance)"
            speaking -> "Speaking"
            else -> ""
        }
        try {
            if (needed) VoiceService.start(this, label) else VoiceService.stop(this)
        } catch (e: Exception) {
            // API 31+ refuses an FGS start from the background — the mic still
            // works while the activity is up; log it instead of crashing.
            log("MIC SERVICE: ${e.message?.take(80)}")
        }
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
                    onStart = { _ ->
                        ttsSpeaking = true
                        runOnUiThread {
                            state.speaking.value = true
                            updateMicService()
                        }
                    },
                    onDone = { id ->
                        ttsSpeaking = false
                        currentUtteranceKind = ""
                        runOnUiThread { onTtsFinished(id) }
                    }
                )
                engine.init(this@MainActivity)
                engine.speed = state.speechRate.value
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
     */
    private fun speak(text: String, kind: String = "reply", add: Boolean = false) {
        val flush = !(add || kind == "cue") // reply/confirm interrupt; cue/add queue behind
        if (kind == "reply") lastSpokenReply = text

        val engine = sherpaTts
        if (engine != null && engine.isReady) {
            currentUtteranceKind = kind
            ttsSpeaking = true // optimistic; confirmed by onStart, cleared by onDone
            state.speaking.value = true
            updateMicService()
            engine.speak(text, "$kind-${System.currentTimeMillis()}", flush)
            return
        }

        val t = tts
        if (t != null && systemTtsOk) {
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            currentUtteranceKind = kind
            ttsSpeaking = true // optimistic; confirmed by onStart, cleared by onDone
            state.speaking.value = true
            updateMicService()
            val r = t.speak(text, mode, null, "$kind-${System.currentTimeMillis()}")
            if (r != TextToSpeech.SUCCESS) {
                // Engine rejected it: clear the flags immediately, otherwise the
                // confirm echo-guard would ignore finals forever (onDone never fires).
                ttsSpeaking = false
                currentUtteranceKind = ""
                state.speaking.value = false
                updateMicService()
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

    /** Barge-in: the user acted (tap / hold / speech) while TTS was talking —
     *  silence it immediately. Exception handled by callers: the confirm
     *  prompt is never barged (echo guard). */
    private fun bargeInStopTts() {
        if (!ttsSpeaking) return
        stopSpeaking()
        ttsSpeaking = false
        currentUtteranceKind = ""
        state.speaking.value = false
        updateMicService()
    }

    /** Short spoken status cue — hands-free mode only (silent at the desk). */
    private fun cue(text: String) {
        if (handsFree) speak(text, kind = "cue")
    }

    /** Main thread. Runs after any TTS utterance finishes playing: clears the
     *  speaking phase, keeps the WAKE-mode recognizer alive, and — after a
     *  spoken REPLY in WAKE mode — opens the follow-up window so the user can
     *  answer ONCE without repeating the wake word. */
    private fun onTtsFinished(utteranceId: String) {
        state.speaking.value = false
        updateMicService()
        if (handsFree) {
            restartMicIfNeeded()
            // utteranceId is "$kind-<ts>" — see speak().
            if (utteranceId.substringBefore("-") == "reply") openFollowUp(FOLLOW_UP_MS)
        }
    }

    /** Re-speak the last normal reply (SAY AGAIN, button or voice). */
    private fun sayAgain() {
        val last = lastSpokenReply
        if (last.isNullOrBlank()) {
            log("(nothing to say again)")
            return
        }
        log("(say again)")
        speak(last)
    }

    // ---------- MASTER MIC (the authoritative toggle) ----------

    /**
     * The single MIC ON / MIC OFF control.
     * OFF is the SAME authoritative teardown as STOP (performStop is reused):
     * recognizer killed, AudioRecord released, in-flight HTTP severed, queue
     * cleared, userStopped set — nothing restarts the mic afterwards.
     * ON is the ONE explicit user action that clears the stop gate. What it
     * starts depends on the mode: WAKE opens the hands-free wake-word
     * listener; TAP/PTT just arm the gesture (the mic opens per tap/hold).
     */
    private fun setMasterMic(on: Boolean, source: String) {
        if (!on) {
            if (!state.masterOn.value && state.micState.value == MicState.OFF) return
            performStop(source) // also flips masterOn off + persists
            return
        }
        if (state.masterOn.value) return
        userStopped = false // the explicit ON is what re-arms the mic
        state.masterOn.value = true
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putBoolean("master_mic_on", true)
            .apply()
        when (state.micMode.value) {
            MicMode.WAKE -> {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (state.requireWake.value) {
                    log("MIC ON — hands-free. Say \"Cosmos ...\"; everything else is " +
                        "decoded on the phone and dropped.")
                    speak("Mic on. Say cosmos, then your command.")
                } else {
                    log("MIC ON — OPEN listening: everything you say (minus noise) " +
                        "is SENT to COSMOS. STOP always wins.")
                    speak("Mic on. Open listening. Everything you say is sent.")
                }
                startPolling()
                ensureMicOn(MicState.LISTENING_WAKE)
            }
            MicMode.TAP -> log("MIC ON — tap the circle to talk (one utterance per tap).")
            MicMode.PTT -> log("MIC ON — hold the circle to talk, release to send.")
        }
        updateMicService()
    }

    /** Mode selector (WAKE default / TAP / PTT). Persisted. Switching while
     *  the master is ON moves the live mic between hands-free and gesture
     *  capture without touching the master toggle. */
    private fun setMicMode(m: MicMode) {
        if (state.micMode.value == m) return
        state.micMode.value = m
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putString("mic_mode", m.name)
            .apply()
        log("MODE: ${m.name} — " + when (m) {
            MicMode.WAKE -> "hands-free, wake-word gated (\"Cosmos ...\")."
            MicMode.TAP -> "tap = one utterance."
            MicMode.PTT -> "hold = talk, release = send."
        })
        if (!state.masterOn.value) return
        // Master is ON: move the live mic to match the new mode.
        closeFollowUp()
        if (m == MicMode.WAKE) {
            if (state.micState.value != MicState.OFF) {
                endpointJob?.cancel()
                voice?.stop()
                state.micState.value = MicState.OFF
                state.partial.value = ""
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startPolling()
            ensureMicOn(MicState.LISTENING_WAKE)
        } else if (state.micState.value == MicState.LISTENING_WAKE) {
            voice?.stop()
            state.micState.value = MicState.OFF
            state.partial.value = ""
            pollJob?.cancel()
            pollJob = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updateMicService()
        }
    }

    /** The MicState a fresh capture should enter for the current mode. */
    private fun micTargetForMode(): MicState = when (state.micMode.value) {
        MicMode.WAKE -> MicState.LISTENING_WAKE
        MicMode.TAP -> MicState.LISTENING_T2T
        MicMode.PTT -> MicState.LISTENING_PTT
    }

    // ---------- follow-up window (WAKE mode) ----------

    /** Open the window: the NEXT utterance (one only) is accepted without the
     *  wake word. Shown in the UI; closed by consumption, timeout, or STOP. */
    private fun openFollowUp(ms: Long) {
        if (!handsFree) return
        // OPEN listening (trigger word off): everything is already a command —
        // the window is meaningless, so it never opens (nor shows in the UI).
        if (!state.requireWake.value) return
        followUpUntilMs = System.currentTimeMillis() + ms
        state.followUp.value = true
        followUpJob?.cancel()
        followUpJob = lifecycleScope.launch {
            delay(ms)
            if (followUpUntilMs != 0L && System.currentTimeMillis() >= followUpUntilMs) {
                followUpUntilMs = 0L
                state.followUp.value = false
                log("(follow-up window closed — say \"Cosmos ...\" again)")
            }
        }
    }

    private fun closeFollowUp() {
        followUpUntilMs = 0L
        followUpJob?.cancel()
        state.followUp.value = false
    }

    private fun followUpOpen(): Boolean = System.currentTimeMillis() < followUpUntilMs

    /** Wake word heard: short haptic tick + audible tick (media-routed, so it
     *  lands in Bluetooth headphones) BEFORE the command is captured/sent —
     *  eyes-free proof the phone is now taking a command. */
    private fun wakeTick() {
        haptics.micStart()
        try {
            if (toneGen == null) toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 85)
            toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        } catch (e: Exception) {
            // the tone is decoration — never let it break the voice path
        }
    }

    /** Poll /status every ~20s while hands-free: detects signal coming back
     *  and flushes the offline queue. Cancelled by STOP. */
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
                    // (a bar of LTE is not a reachable COSMOS) and flush. This
                    // NEVER touches the mic.
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

    // ---------- CONTROL CHANNEL (remote kill) ----------

    /**
     * Poll GET /api/v1/control?client_id=<id> every ~3s, forever, and obey
     * {mic_off, pause, clear_queue} IMMEDIATELY. Fail-safe: any fetch error
     * does NOTHING — the channel can only turn things off, never on.
     */
    private fun startControlPolling() {
        controlJob?.cancel()
        controlJob = lifecycleScope.launch {
            while (isActive) {
                delay(3_000)
                val base = state.baseUrl.value.trim().trimEnd('/')
                if (base.isBlank()) continue
                val resp = withContext(Dispatchers.IO) {
                    try {
                        ControlClient.fetch(base, state.token.value.trim(), clientId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null // fail-safe: no response -> no action
                    }
                } ?: continue
                if (resp.has("http_status")) continue // error body -> no action

                if (resp.optBoolean("mic_off", false)) {
                    if (state.micState.value != MicState.OFF || state.speaking.value ||
                        state.thinking.value || queue.size > 0
                    ) {
                        performStop("remote control mic_off")
                    }
                }
                if (resp.optBoolean("clear_queue", false) && queue.size > 0) {
                    val n = queue.clear()
                    state.queueSize.value = 0
                    log("CONTROL: cleared $n queued item(s).")
                }
                val pause = resp.optBoolean("pause", false)
                if (pause != controlPaused) {
                    controlPaused = pause
                    state.pausedByControl.value = pause
                    log(if (pause) "CONTROL: PAUSED — nothing is sent until the server clears it."
                        else "CONTROL: pause cleared — sending allowed again.")
                }
            }
        }
    }

    // ---------- settings ----------

    private fun saveSettings() {
        // Bearer token: MEMORY-ONLY, deliberately not persisted.
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putString("base_url", state.baseUrl.value.trim())
            .apply()
    }

    private fun setStream(s: String) {
        state.stream.value = s
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putString("stream", s)
            .apply()
        log("STREAM: $s — every payload is tagged with it.")
    }

    private fun setVerbosity(v: String) {
        state.verbosity.value = v
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putString("verbosity", v)
            .apply()
        log("VERBOSITY: $v")
    }

    private fun setSpeechRate(r: Float) {
        state.speechRate.value = r
        sherpaTts?.speed = r
        tts?.setSpeechRate(r)
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putFloat("speech_rate", r)
            .apply()
    }

    private fun setOfflineQueue(on: Boolean) {
        state.offlineQueueOn.value = on
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putBoolean("offline_queue_on", on)
            .apply()
        if (!on) {
            val n = queue.clear()
            state.queueSize.value = 0
            if (n > 0) log("Offline queue OFF — $n pending item(s) discarded.")
        }
        log(if (on) "OFFLINE QUEUE ON — up to ${OfflineQueue.MAX_ITEMS} items, " +
                "${OfflineQueue.MAX_AGE_MS / 1000}s TTL."
            else "SEND-NOW-OR-DISCARD — a failed send is dropped with a log.")
    }

    /**
     * Route audio INPUT to the built-in phone mic when the "Use phone mic"
     * toggle is ON (default): make sure Bluetooth SCO is DOWN before the
     * recognizer opens its AudioRecord. Called right before every recognizer
     * start; best-effort.
     */
    private fun applyMicRoute() {
        if (!state.phoneMicOn.value) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                log("(BT SCO mic disabled — using phone mic)")
            }
            if (am.mode != AudioManager.MODE_NORMAL) am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            log("MIC ROUTE: ${e.message?.take(80)}")
        }
    }

    /** "Use phone mic" toggle — persisted, default ON. A live recognizer is
     *  rebuilt so its AudioRecord re-opens on the new route. */
    private fun setPhoneMic(on: Boolean) {
        state.phoneMicOn.value = on
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putBoolean("phone_mic", on)
            .apply()
        log(if (on) "PHONE MIC — Bluetooth mic ignored (BT audio out still works)."
            else "SYSTEM MIC ROUTING — Bluetooth mic allowed.")
        if (state.micState.value != MicState.OFF) restartRecognizer()
    }

    /** "Require 'Cosmos' trigger word" toggle — WAKE mode only, persisted,
     *  default ON. OFF = OPEN listening: while MIC is ON, every utterance
     *  that passes the junk gate is SENT, no trigger word needed. This gates
     *  only WHICH utterances are sent — it never touches the stop path:
     *  master OFF (performStop), the late-final drop, the send() stop-gate,
     *  and the remote control channel apply identically in both sub-modes. */
    private fun setRequireWake(on: Boolean) {
        if (state.requireWake.value == on) return
        state.requireWake.value = on
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putBoolean("require_wake", on)
            .apply()
        closeFollowUp() // the window only means something while gating is on
        log(if (on) "TRIGGER WORD REQUIRED — only \"Cosmos ...\" is sent (WAKE mode)."
            else "TRIGGER WORD OFF — OPEN listening: while MIC is ON, everything " +
                "you say (minus noise) is SENT to COSMOS. STOP always wins.")
        if (handsFree) cue(if (on) "Trigger word required." else "Open listening.")
        updateMicService() // notification label names the active sub-mode
    }

    /** Wake sensitivity (WAKE mode) — persisted, default NORMAL. STRICT =
     *  exact "cosmos" only; NORMAL also accepts the close variants the small
     *  Vosk model emits in road noise. Pure gating change: it only alters
     *  which first tokens count as the wake word — every stop/safety path is
     *  untouched, and no recognizer rebuild is needed (the variants are
     *  always in the grammar; acceptance is decided per-final). */
    private fun setWakeSensitivity(s: WakeSensitivity) {
        if (state.wakeSensitivity.value == s) return
        state.wakeSensitivity.value = s
        VoiceGrammar.wakeSensitivity = s
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putString("wake_sensitivity", s.name)
            .apply()
        log(when (s) {
            WakeSensitivity.STRICT ->
                "WAKE SENSITIVITY: STRICT — only an exact \"Cosmos\" triggers."
            WakeSensitivity.NORMAL ->
                "WAKE SENSITIVITY: NORMAL — close mishearings of \"Cosmos\" " +
                    "(cosmo, cosmic, ...) also trigger."
        })
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

    /** Model download never auto-starts the mic on its own. ONE exception:
     *  when a standing explicit MIC ON (WAKE mode) triggered this download,
     *  completing it finishes THAT user action — same class as the permission
     *  grant. Launch/reboot/reconnect still never start the mic. */
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
                    if (state.masterOn.value && state.micMode.value == MicMode.WAKE &&
                        !userStopped
                    ) {
                        // The explicit MIC ON is still standing — the download
                        // was the missing piece of that user action; finish it.
                        log("Voice model ready — starting hands-free listening " +
                            "(completing your MIC ON).")
                        ensureMicOn(MicState.LISTENING_WAKE)
                    } else {
                        log("Voice model ready. Tap the mic to start.")
                    }
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
                            if (p.isNotBlank() && p != state.partial.value) {
                                lastVoiceActivityMs = System.currentTimeMillis()
                            }
                            state.partial.value = p
                            // ================== BARGE-IN ==================
                            // The user started talking while we were talking:
                            // stop the TTS so they can be heard. Exceptions:
                            // while the CONFIRM prompt is playing we do NOT
                            // barge-in (echo guard — the phone can hear its
                            // own prompt); and in WAKE mode ambient speech
                            // must not cut replies — barging needs the wake
                            // word in the partial, or an open follow-up window
                            // (or OPEN listening, where all speech is command).
                            if (p.isNotBlank() && ttsSpeaking && currentUtteranceKind != "confirm" &&
                                (state.micState.value != MicState.LISTENING_WAKE ||
                                    !state.requireWake.value ||
                                    p.contains("cosmos") || followUpOpen())
                            ) {
                                bargeInStopTts()
                            }
                        }
                    },
                    onFinal = { text -> runOnUiThread { onFinalTranscript(text) } },
                    onErr = { msg ->
                        runOnUiThread {
                            log("VOICE ERROR: $msg")
                            if (handsFree && !userStopped) {
                                // WAKE mode: never let the recognizer die
                                // silently. Tear down, wait a beat, restart.
                                voice?.stop()
                                lifecycleScope.launch {
                                    delay(1_000)
                                    restartMicIfNeeded()
                                }
                            } else {
                                endpointJob?.cancel()
                                voice?.stop()
                                state.micState.value = MicState.OFF
                                state.partial.value = ""
                                updateMicService()
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
                    if (handsFree && !userStopped) {
                        // The WAKE opt-in is still live: retry in a few
                        // seconds (STOP makes this a no-op). Both conditions
                        // are re-read after the delay — a STOP during the
                        // wait must end the retry, not just defer it.
                        lifecycleScope.launch {
                            delay(5_000)
                            if (userStopped || state.micState.value != MicState.LISTENING_WAKE) return@launch
                            restartMicIfNeeded()
                        }
                    }
                }
            }
        }
    }

    // ---------- recognition mode (COMMAND grammar vs open DICTATE) ----------

    /** Grammar for the CURRENT mode: command mode constrains VOSK to the
     *  COSMOS vocabulary (null = open dictation). */
    private fun currentGrammar(): String? =
        if (state.dictateMode.value) null else VoiceGrammar.commandGrammarJson()

    /**
     * Switch COMMAND <-> DICTATE. The grammar is baked into the Recognizer at
     * construction, so a live recognizer is stopped and rebuilt with (or
     * without) the grammar. DICTATE auto-reverts after one open utterance
     * (or on "done").
     */
    private fun setDictateMode(on: Boolean, announce: Boolean = true) {
        if (state.dictateMode.value == on) return
        state.dictateMode.value = on
        if (announce) {
            log(if (on) "DICTATE mode — speak freely; one utterance, then back to commands (or say \"done\")."
                else "COMMAND mode — grammar-constrained recognition.")
            if (on) cue("Dictating.") else cue("Commands.")
        }
        if (state.micState.value != MicState.OFF) restartRecognizer()
    }

    /** Rebuild the live recognizer for the current mode/mic-route settings,
     *  keeping the current mic state. */
    private fun restartRecognizer() {
        val v = voice ?: return
        if (userStopped || state.micState.value == MicState.OFF) return
        v.stop()
        // Re-check IMMEDIATELY before starting: a performStop() landing
        // between the gate above and this start must win — otherwise Vosk is
        // recording while the UI says OFF. Defensive v.stop() in case a
        // concurrent path started it in the gap (VoiceEngine.stop is
        // idempotent and cheap).
        if (userStopped || state.micState.value == MicState.OFF) {
            v.stop()
            return
        }
        try {
            applyMicRoute()
            v.start(currentGrammar())
        } catch (e: Exception) {
            log("MIC RESTART FAILED: ${e.message}")
            endpointJob?.cancel()
            state.micState.value = MicState.OFF
            updateMicService()
            if (handsFree) scheduleMicRetry("recognizer rebuild failed")
        }
    }

    // ---------- mic (user gestures) ----------

    /**
     * TAP on the mic circle — gated by the MASTER toggle (a tap while MIC is
     * OFF opens nothing; the toggle is the only way to arm the mic):
     *   OFF (master ON)  -> per mode: TAP starts a one-utterance capture,
     *                       WAKE recovers a dead hands-free mic, PTT hints
     *   T2T              -> endpoint NOW (Vosk flushes the final -> sent)
     *   PTT              -> no-op (the release handles it)
     *   WAKE             -> barge-in only (speech gating handles the rest)
     * Any tap silences the TTS (barge-in) first.
     */
    private fun onMicTap() {
        bargeInStopTts()
        when (state.micState.value) {
            MicState.OFF -> {
                if (!state.masterOn.value) {
                    log("MIC is OFF — tap the MIC ON toggle first.")
                    return
                }
                when (state.micMode.value) {
                    MicMode.TAP -> ensureMicOn(MicState.LISTENING_T2T)
                    MicMode.WAKE -> ensureMicOn(MicState.LISTENING_WAKE)
                    MicMode.PTT -> log("(PTT mode — hold the circle to talk)")
                }
            }
            MicState.LISTENING_T2T -> {
                endpointJob?.cancel()
                voice?.stop() // flushes the final -> onFinalTranscript sends it
                if (state.micState.value == MicState.LISTENING_T2T) {
                    state.micState.value = MicState.OFF
                    state.partial.value = ""
                    updateMicService()
                }
                log("(endpointed by tap)")
            }
            MicState.LISTENING_PTT -> { /* release handles it */ }
            MicState.LISTENING_WAKE -> { /* barge-in done above; gating decides */ }
        }
    }

    /** HOLD (push-to-talk): press opens the mic. Fires from a long-press on
     *  the mic button. Gated by the MASTER toggle. A hold during T2T upgrades
     *  the capture to PTT semantics (release = send). */
    private fun onPttStart() {
        when (state.micState.value) {
            MicState.OFF -> {
                if (!state.masterOn.value) {
                    log("MIC is OFF — tap the MIC ON toggle first.")
                    return
                }
                if (state.micMode.value == MicMode.WAKE) return // tap recovers wake
                bargeInStopTts()
                pttHeld = true
                ensureMicOn(MicState.LISTENING_PTT)
            }
            MicState.LISTENING_T2T -> {
                endpointJob?.cancel()
                pttHeld = true
                state.micState.value = MicState.LISTENING_PTT
                updateMicService()
                log("(hold — release to send)")
            }
            else -> { /* PTT already, or WAKE — nothing */ }
        }
    }

    /** RELEASE (push-to-talk): endpoint and send. */
    private fun onPttRelease() {
        pttHeld = false
        if (state.micState.value != MicState.LISTENING_PTT) return
        voice?.stop() // flushes the final -> onFinalTranscript sends it
        state.micState.value = MicState.OFF
        state.partial.value = ""
        updateMicService()
        log("(released — sending)")
    }

    /** Turn the mic on into [target], requesting permission when needed.
     *  ONLY reachable from an explicit user action. */
    private fun ensureMicOn(target: MicState) {
        if (state.micState.value != MicState.OFF) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = target
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening(target)
    }

    private fun startListening(target: MicState) {
        if (target == MicState.OFF) return
        if (userStopped) return // a hard stop is a hard stop
        if (!ModelManager.isReady(this)) {
            // First run: get the model; the user taps Talk again when ready
            // (the mic NEVER auto-starts, download completion included).
            downloadModel()
            return
        }
        ensureEngineLoaded {
            if (userStopped) return@ensureEngineLoaded // STOP won the race
            if (target == MicState.LISTENING_PTT && !pttHeld) {
                // The finger lifted while the engine was loading — never open
                // a mic nobody is holding.
                log("(hold released before the mic was ready — nothing captured)")
                return@ensureEngineLoaded
            }
            try {
                applyMicRoute()
                // Last-gate re-check: the engine load was async, and nothing
                // between here and start() may override a STOP that raced in.
                if (userStopped) return@ensureEngineLoaded
                voice?.start(currentGrammar())
                state.micState.value = target
                updateMicService()
                haptics.micStart() // short tick: "I'm hearing you" — no glance needed
                if (target == MicState.LISTENING_T2T) armT2tEndpoint()
                log(
                    when (target) {
                        MicState.LISTENING_WAKE ->
                            if (state.requireWake.value)
                                "Hands-free listening... say \"Cosmos ...\" — everything " +
                                    "else is dropped on the phone. STOP or the toggle ends it."
                            else
                                "OPEN listening... everything you say (minus noise) is " +
                                    "SENT. STOP or the toggle ends it."
                        MicState.LISTENING_PTT ->
                            "Listening (hold-to-talk)... release to send."
                        else ->
                            "Listening (${if (state.dictateMode.value) "DICTATE" else "COMMAND"})... " +
                                "speak, pause to send — one utterance, then off."
                    }
                )
                if (target == MicState.LISTENING_WAKE) cue("Listening.")
            } catch (e: Exception) {
                log("MIC START FAILED: ${e.message}")
                state.micState.value = MicState.OFF
                updateMicService()
            }
        }
    }

    /** T2T auto-endpoint: Vosk's own final (on end of speech) normally closes
     *  the capture; this watchdog is the belt — ~1s of silence after speech
     *  forces the endpoint, and hearing nothing at all times out. */
    private fun armT2tEndpoint() {
        endpointJob?.cancel()
        lastVoiceActivityMs = System.currentTimeMillis()
        endpointJob = lifecycleScope.launch {
            var sawSpeech = false
            while (isActive && state.micState.value == MicState.LISTENING_T2T) {
                delay(200)
                if (state.partial.value.isNotBlank()) sawSpeech = true
                val idleMs = System.currentTimeMillis() - lastVoiceActivityMs
                if (sawSpeech && idleMs >= T2T_SILENCE_MS) {
                    voice?.stop() // flush final -> onFinalTranscript sends it
                    if (state.micState.value == MicState.LISTENING_T2T) {
                        state.micState.value = MicState.OFF
                        state.partial.value = ""
                        updateMicService()
                    }
                    break
                }
                if (!sawSpeech && idleMs >= T2T_MAX_WAIT_MS) {
                    voice?.stop()
                    if (state.micState.value == MicState.LISTENING_T2T) {
                        state.micState.value = MicState.OFF
                        state.partial.value = ""
                        updateMicService()
                    }
                    log("(heard nothing — mic off)")
                    break
                }
            }
        }
    }

    /** WAKE-mode watchdog: if the recognizer is not running, restart it.
     *  Called after every TTS utterance finishes and after voice errors.
     *  A NO-OP in every other state and, always, while userStopped is set —
     *  this is a keep-alive for the explicit MIC ON, never an auto-start. */
    private fun restartMicIfNeeded() {
        if (userStopped) return // the user said STOP — the mic stays off
        if (state.micState.value != MicState.LISTENING_WAKE) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val v = voice
        if (v == null || !v.isLoaded) {
            if (!ModelManager.isReady(this)) {
                // Model vanished mid-session: exit hands-free instead of
                // looping a download — the user restarts when it is back.
                log("WAKE: voice model missing — going OFF.")
                state.micState.value = MicState.OFF
                updateMicService()
                return
            }
            ensureEngineLoaded {
                if (userStopped || state.micState.value != MicState.LISTENING_WAKE) return@ensureEngineLoaded
                try {
                    applyMicRoute()
                    // Last-gate re-check before opening the mic (STOP races).
                    if (userStopped || state.micState.value != MicState.LISTENING_WAKE) return@ensureEngineLoaded
                    voice?.start(currentGrammar())
                    updateMicService()
                    log("(mic auto-restarted after model load)")
                } catch (e: Exception) {
                    scheduleMicRetry("MIC RESTART FAILED: ${e.message}")
                }
            }
            return
        }
        if (v.isRunning) return
        try {
            applyMicRoute()
            // Last-gate re-check before opening the mic (STOP races).
            if (userStopped || state.micState.value != MicState.LISTENING_WAKE) return
            v.start(currentGrammar())
            log("(mic auto-restarted)")
        } catch (e: Exception) {
            scheduleMicRetry("MIC RESTART FAILED: ${e.message}")
        }
    }

    private fun scheduleMicRetry(msg: String) {
        // Retry ONLY while WAKE hands-free is still live AND the user has
        // not stopped — guard BOTH: a stop can flip either flag first, and
        // a retry loop must never outlive the state it keeps alive.
        if (userStopped || state.micState.value != MicState.LISTENING_WAKE) return
        log("$msg — retrying in 2s")
        lifecycleScope.launch {
            delay(2_000)
            // Re-check after the async gap; restartMicIfNeeded re-checks
            // both again, but a STOP during the delay must end the loop here.
            if (userStopped || state.micState.value != MicState.LISTENING_WAKE) return@launch
            restartMicIfNeeded()
        }
    }

    // ---------- transcript handling ----------

    /** One-shot capture done (T2T/PTT): return to OFF. No-op in WAKE mode
     *  (the hands-free mic stays hot; gating decides what is sent). */
    private fun stopAfterUtterance() {
        val st = state.micState.value
        if (st == MicState.LISTENING_WAKE || st == MicState.OFF) return
        endpointJob?.cancel()
        voice?.stop()
        state.micState.value = MicState.OFF
        state.partial.value = ""
        updateMicService()
        log("(mic idle — tap Talk to speak again)")
    }

    private fun onFinalTranscript(text: String) {
        // ============= AUTHORITATIVE-STOP GATE (late-final race) =============
        // Vosk finals are delivered ASYNCHRONOUSLY (posted to the main
        // handler), so one can land AFTER performStop() — including the final
        // that performStop's own voice.stop() flushes out. After a hard stop
        // it must be DROPPED: never classified, never confirmed, never sent,
        // never queued. userStopped is the flag every stop path sets and only
        // an explicit user start clears.
        // NOTE deliberately NOT gated on micState == OFF: the normal T2T/PTT
        // endpoint ("stop the recognizer to flush the final, then send it")
        // delivers its final after micState has already gone OFF — that is
        // the one legitimate late final, and userStopped tells them apart.
        if (userStopped) {
            log("(dropped after STOP: \"${text.trim().take(60)}\")")
            return
        }
        state.partial.value = ""
        // COMMAND mode's grammar surfaces out-of-vocabulary audio as "[unk]"
        // tokens instead of hallucinated sentences. Strip them; a final that
        // was ALL [unk] (road noise, side chatter) vanishes here.
        val raw = text.trim()
            .replace("[unk]", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (raw.isBlank()) return
        val norm = VoiceGrammar.normalize(raw)

        // ==================== WAKE-WORD GATE (WAKE mode) ====================
        // The ambient-capture fix: in hands-free mode EVERY utterance is
        // decoded locally, and only one that carries the wake word ("cosmos" /
        // "hey cosmos") — or the ONE allowed follow-up while the window is
        // open — goes any further. Everything else is dropped RIGHT HERE:
        // never classified, never sent, never queued, never spoken, never
        // spends. Pass-through WITHOUT the wake word, by design:
        //   - a pending confirm (the yes/no answer to a prompt WE spoke)
        //   - dictate mode (entered only through a wake-worded "ask")
        //   - a bare "stop" while speaking/thinking (the safe direction —
        //     it can only turn things off)
        var text = raw
        var normText = norm
        if (state.micState.value == MicState.LISTENING_WAKE) {
            if (!state.requireWake.value) {
                // ============ OPEN LISTENING (trigger word OFF) ============
                // The user explicitly turned the wake requirement off: every
                // utterance is a candidate command — only the junk gate below
                // (VoiceGrammar.isJunk) drops filler/fragments. A spoken wake
                // word still works and is stripped, so "cosmos status" and
                // "status" behave identically. The follow-up window is
                // meaningless here (everything is a command). EVERY safety
                // gate is untouched: the userStopped drop above, the stop
                // check below, send()'s stop-gate, pause, and dedupe all
                // apply exactly as in gated mode.
                closeFollowUp()
                if (VoiceGrammar.hasWake(norm)) {
                    wakeTick()
                    val rest = VoiceGrammar.stripWake(norm)
                    if (rest.isBlank()) {
                        log("YOU: $raw")
                        log("(wake word — open listening is already on, just speak)")
                        return
                    }
                    text = rest
                    normText = rest
                }
            } else when {
                VoiceGrammar.hasWake(norm) -> {
                    wakeTick() // wake word heard — tick BEFORE taking the command
                    val rest = VoiceGrammar.stripWake(norm)
                    if (rest.isBlank()) {
                        // Bare "cosmos": open a command window — the next
                        // utterance IS the command, no wake word needed.
                        log("YOU: $raw")
                        log("(wake word — listening for your command)")
                        openFollowUp(COMMAND_WINDOW_MS)
                        return
                    }
                    closeFollowUp()
                    text = rest
                    normText = rest
                }
                state.pendingConfirmId.value != null || state.dictateMode.value -> {
                    // active exchange — pass through un-stripped
                }
                followUpOpen() -> {
                    // The ONE follow-up: consume the window.
                    closeFollowUp()
                    log("(follow-up accepted)")
                }
                (ttsSpeaking || state.thinking.value) && VoiceGrammar.isStop(norm) -> {
                    // "stop" mid-exchange passes — handled just below
                }
                else -> {
                    log("(no wake word — dropped on the phone: \"${raw.take(48)}\")")
                    return
                }
            }
        }

        // Spoken authoritative STOP — checked BEFORE everything, including
        // the pending-confirm branch, so saying "stop" always stops: mic off,
        // speech killed, requests aborted, AND the pending nonce dropped
        // (performStop clears it). A confirm dialog must never be able to
        // swallow the stop word as a mere "cancel" answer. (The confirm
        // prompt text never contains the word "stop", so the echo guard is
        // not needed for this path.)
        if (VoiceGrammar.isStop(normText)) {
            log("YOU: $raw")
            performStop("voice")
            return
        }

        // ================= PENDING-CONFIRM STATE MACHINE =================
        // AWAITING_YESNO: the next final is the ANSWER to "Confirm: ...?" and
        // is NEVER treated as a new command ("stop" excepted — handled above).
        // Echo guard: a final completing while the confirm prompt is still
        // speaking is the phone hearing its own prompt — discarded.
        // =================================================================
        if (state.pendingConfirmId.value != null) {
            if (ttsSpeaking && currentUtteranceKind == "confirm") {
                log("(ignored while confirm prompt speaking: \"$raw\")")
                return
            }
            log("YOU: $raw")
            if (VoiceGrammar.isYes(normText)) {
                confirmPending()
            } else {
                confirmExpireJob?.cancel()
                state.pendingConfirmId.value = null
                state.pendingTranscript.value = null
                log("CANCELLED (heard: \"$raw\"). Nonce dropped.")
                speak("Cancelled.")
            }
            stopAfterUtterance() // confirm answered — one-shot capture goes idle
            return
        }

        log("YOU: $raw")

        // Local voice controls that never leave the phone.
        if (VoiceGrammar.isSayAgain(normText)) {
            sayAgain()
            stopAfterUtterance()
            return
        }
        if (VoiceGrammar.isNewSession(normText)) {
            newSession()
            stopAfterUtterance()
            return
        }

        // ===================== RECOGNITION MODE SWITCH =====================
        // DICTATE mode: this final IS the dictated utterance (open decode).
        if (state.dictateMode.value) {
            if (VoiceGrammar.isDictateDone(normText)) {
                stopAfterUtterance() // stop FIRST so setDictateMode does not rebuild a live recognizer
                setDictateMode(false)
                return
            }
            send(text, null, quiet = false)
            stopAfterUtterance() // one open utterance, then idle
            setDictateMode(false) // back to commands for the next tap
            return
        }
        // COMMAND mode: a bare "ask"/"dictate" opens the recognizer for free
        // speech (the grammar blocks arbitrary text, so the switch is explicit).
        if (VoiceGrammar.isDictateStart(normText)) {
            setDictateMode(true)
            return
        }

        // Voice control of hands-free. OFF is a full authoritative STOP;
        // ON switches the mode (the utterance itself was an explicit user
        // capture, so the gesture requirement is already met).
        if (VoiceGrammar.isDrivingOff(normText)) {
            performStop("voice (driving mode off)")
            return
        }
        if (VoiceGrammar.isDrivingOn(normText)) {
            if (handsFree) {
                speak("Hands-free is already on.")
            } else {
                stopAfterUtterance()
                setMicMode(MicMode.WAKE)
            }
            return
        }

        // Junk gate — ALL modes: road noise, filler words, and fragments
        // with no real content and no COSMOS verb are dropped entirely.
        if (VoiceGrammar.isJunk(normText)) {
            log("(ignored as noise: \"$raw\")")
            return
        }
        if (handsFree) {
            // Passed the wake/follow-up gate: this IS a command for COSMOS —
            // the SERVER classifies it and returns `kind`; the SPOKEN reply
            // is moderated in handleReply from that classification.
            send(text, null, quiet = false)
        } else {
            send(text, null, quiet = false)
            stopAfterUtterance() // one utterance per tap/hold
        }
    }

    // ---------- COSMOS API ----------

    /** Every /voice payload carries the same envelope: utterance + mode +
     *  stream + session + build + client id + idempotency key. Old field
     *  names (transcript / request_id) ride along for server compatibility. */
    private fun voiceBody(transcript: String): JSONObject {
        val rid = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("transcript", transcript)
            .put("utterance", transcript)
            .put("mode", if (state.dictateMode.value) "dictate" else "command")
            .put("project", state.stream.value)
            .put("stream", state.stream.value)
            .put("verbosity", state.verbosity.value)
            .put("build", BuildConfig.VERSION_NAME)
            .put("client_id", clientId)
            .put("request_id", rid)
            .put("idempotency_key", rid)
        state.sessionId.value?.let { body.put("session_id", it) }
        return body
    }

    /**
     * POST a transcript to COSMOS. `quiet` suppresses the pre-send "Got it,
     * thinking." cue in hands-free mode only. `action` tags the payload with a
     * server-side action (e.g. "bootup").
     */
    private fun send(
        transcript: String,
        confirmId: String?,
        quiet: Boolean = false,
        action: String? = null
    ) {
        // ============= AUTHORITATIVE-STOP GATE (late-send race) =============
        // Belt to onFinalTranscript's braces: a voice-originated transcript
        // (confirmId == null && action == null — every such call comes from
        // onFinalTranscript) must never POST or queue after a hard stop,
        // however late the recognizer delivered it. Explicit BUTTON actions
        // (BootUP!, the CONFIRM button) are user gestures, not audio — they
        // stay allowed; a stale confirm nonce is already impossible because
        // performStop clears pendingConfirmId.
        if (userStopped && confirmId == null && action == null) {
            log("(not sent — STOP is in effect: \"${transcript.take(60)}\")")
            return
        }
        val base = state.baseUrl.value.trim().trimEnd('/')
        if (base.isBlank()) {
            log("Set the server URL first.")
            if (handsFree) speak("No server URL is set.")
            return
        }
        if (controlPaused) {
            log("(PAUSED by control — not sent: \"$transcript\")")
            return
        }
        if (handsFree && !quiet && confirmId == null) {
            cue("Got it, thinking.")
        }
        haptics.sent()             // double-tick: recognized and on its way
        state.thinking.value = true // THINKING phase until the server answers
        // Build the FULL request up front, idempotency key included, so the
        // exact same request is what gets queued on failure and replayed by
        // the flush — the server can dedupe a retry.
        val body = voiceBody(transcript)
        if (confirmId != null) body.put("confirm_id", confirmId)
        if (action != null) body.put("action", action)
        netScope.launch {
            try {
                val resp = CosmosClient.postVoice(base, state.token.value.trim(), body)
                withContext(Dispatchers.Main) {
                    state.thinking.value = false
                    onServerReachable(true)
                    handleReply(transcript, resp)
                }
            } catch (e: CancellationException) {
                throw e // STOP cancelled us — no state to fix, performStop did it
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    state.thinking.value = false
                    log("SEND FAILED: ${e.message}")
                    onServerReachable(false)
                    onSendFailed(transcript, confirmId, body)
                }
            }
        }
    }

    /**
     * Send failure policy:
     *  - DEFAULT (offline queue off): SEND-NOW-OR-DISCARD — the transcript is
     *    dropped with a log; nothing replays later.
     *  - Offline queue ON: plain transcripts queue (5 max / 120s TTL, oldest
     *    dropped); the flush replays them in order when signal returns.
     *  - Confirm re-POSTs are NEVER queued — the nonce is single-use and
     *    firing a destructive action minutes later is what nobody expects.
     */
    private fun onSendFailed(transcript: String, confirmId: String?, body: JSONObject) {
        if (confirmId != null) {
            log("Confirm re-POST failed — nonce dropped (single-use, not queued).")
            if (handsFree) {
                speak("Couldn't reach COSMOS. The confirmation was not sent. Ask again when we're back online.")
            }
            return
        }
        if (!state.offlineQueueOn.value) {
            log("DISCARDED (send-now-or-discard): \"$transcript\"")
            if (handsFree) speak("Couldn't reach COSMOS. That was not saved.")
            return
        }
        val dropped = queue.add(body.toString())
        state.queueSize.value = queue.size
        if (dropped > 0) {
            log("QUEUE FULL (${OfflineQueue.MAX_ITEMS}) — dropped $dropped oldest item(s).")
        }
        log("QUEUED offline (${queue.size} pending): \"$transcript\"")
        if (handsFree) {
            speak("Saved, no signal. I'll send it when we're back.")
        }
    }

    /** Flush the offline queue in order. One flush at a time; stops (keeping
     *  the remainder) the moment a send fails again. */
    private fun tryFlush() {
        if (flushing || queue.size == 0) return
        if (controlPaused) return // remote pause blocks the flush too
        flushing = true
        val base = state.baseUrl.value.trim().trimEnd('/')
        if (base.isBlank()) {
            flushing = false
            return
        }
        // Stale voice must not replay: age-gate the backlog before sending.
        val stale = queue.pruneStale()
        if (stale > 0) {
            state.queueSize.value = queue.size
            log("QUEUE: dropped $stale stale item(s) older than " +
                "${OfflineQueue.MAX_AGE_MS / 1000}s — not replayed.")
        }
        if (queue.size == 0) {
            flushing = false
            return
        }
        val n = queue.size
        cue("Back online. Sending $n saved ${if (n == 1) "command" else "commands"}.")
        netScope.launch {
            try {
                while (true) {
                    val item = queue.peek() ?: break
                    val body = try {
                        JSONObject(item)
                    } catch (e: Exception) {
                        JSONObject().put("transcript", item).also { b ->
                            state.sessionId.value?.let { b.put("session_id", it) }
                        }
                    }
                    val shownTranscript = body.optString("transcript", item)
                    val resp = CosmosClient.postVoice(base, state.token.value.trim(), body)
                    withContext(Dispatchers.Main) {
                        queue.removeFirst()
                        state.queueSize.value = queue.size
                        log("FLUSHED: \"$shownTranscript\"")
                        // add=true: flushed replies queue up behind each other
                        // instead of each one cutting off the last.
                        handleReply(shownTranscript, resp, add = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e // STOP cancelled the flush; performStop cleared the queue
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
                // Client-side expiry: after CONFIRM_TTL_MS a stray "yes"/"ok"
                // can no longer fire this nonce, whatever the server would say.
                confirmExpireJob?.cancel()
                confirmExpireJob = lifecycleScope.launch {
                    delay(CONFIRM_TTL_MS)
                    if (state.pendingConfirmId.value == cid) {
                        state.pendingConfirmId.value = null
                        state.pendingTranscript.value = null
                        log("CONFIRM EXPIRED (${CONFIRM_TTL_MS / 1000}s) — nonce dropped.")
                        if (handsFree) speak("That confirmation expired.")
                    }
                }
                // Two strong pulses: a consequential confirm is FELT, not just heard.
                haptics.alert()
                log("NEEDS CONFIRM — say yes/no (or tap CONFIRM). Never auto-run.")
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
        // everything else a soft "reply arrived" buzz.
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
        // Rule: pure dictation is SILENT.
        if (kind == "dictation" && !refused) {
            log("(dictation — noted silently, not spoken)")
            return
        }
        val toSpeak: String? = when {
            spoken.isNotBlank() -> spoken
            handsFree && refused -> "COSMOS refused that."
            handsFree && o.has("http_status") ->
                "Server error ${o.optInt("http_status")}."
            handsFree -> "Done."
            else -> null
        }
        if (toSpeak != null) {
            speak(toSpeak, add = add)
        }
    }

    private fun confirmPending() {
        val cid = state.pendingConfirmId.value ?: return
        val original = state.pendingTranscript.value ?: ""
        confirmExpireJob?.cancel()
        state.pendingConfirmId.value = null
        state.pendingTranscript.value = null
        log("CONFIRMING (nonce ${cid.take(8)}...)")
        cue("Confirming.")
        send(original, cid)
    }

    // ---------- stream / session / bootup ----------

    /** Invoke server BootUP for the selected stream (a tagged /voice action). */
    private fun bootUp() {
        val s = state.stream.value
        log("BootUP! → $s")
        send("BootUP! $s", null, quiet = true, action = "bootup")
    }

    private fun newSession() {
        state.sessionId.value = null
        log("NEW SESSION — the next reply starts a fresh session id.")
        cue("New session.")
    }

    // ---------- console ----------

    private fun log(line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        state.console.add(0, "[$t] $line")
        while (state.console.size > 300) {
            state.console.removeAt(state.console.size - 1)
        }
    }

    private companion object {
        /** How long a pending confirm nonce stays live on the client. */
        const val CONFIRM_TTL_MS = 30_000L

        /** T2T endpoint: ~1s of silence after speech closes the capture. */
        const val T2T_SILENCE_MS = 1_000L

        /** T2T: give up if nothing is heard at all for this long. */
        const val T2T_MAX_WAIT_MS = 8_000L

        /** WAKE mode: follow-up window after a spoken reply — ONE utterance
         *  is accepted without the wake word, then gating resumes.
         *  ROAD-TUNABLE: lengthen if replies land while the driver is mid-
         *  maneuver and answers keep missing the window; shorten if ambient
         *  chatter keeps slipping in as the "follow-up". */
        const val FOLLOW_UP_MS = 10_000L

        /** WAKE mode: command window after a bare "cosmos" (wake heard, no
         *  command yet) — the next utterance is the command.
         *  ROAD-TUNABLE: same trade-off as FOLLOW_UP_MS. */
        const val COMMAND_WINDOW_MS = 8_000L
    }
}

// ============================== UI ==============================

private val STREAMS = listOf("plumbing", "physics", "chapter", "legal")

@Composable
fun AppScreen(
    state: AppState,
    buildName: String,
    onConnect: () -> Unit,
    onMicTap: () -> Unit,
    onPttStart: () -> Unit,
    onPttRelease: () -> Unit,
    onStop: () -> Unit,
    onConfirm: () -> Unit,
    onSave: () -> Unit,
    onMasterToggle: () -> Unit,
    onMode: (MicMode) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onDictate: () -> Unit,
    onPhoneMic: (Boolean) -> Unit,
    onRequireWake: (Boolean) -> Unit,
    onWakeSensitivity: (WakeSensitivity) -> Unit,
    onOfflineQueue: (Boolean) -> Unit,
    onStream: (String) -> Unit,
    onBootUp: () -> Unit,
    onNewSession: () -> Unit,
    onVerbosity: (String) -> Unit,
    onSpeechRate: (Float) -> Unit,
    onSayAgain: () -> Unit
) {
    val mic = state.micState.value
    // One visual phase drives the mic button, pulse ring, spinner, and the
    // big glanceable label. Priority: SPEAKING > THINKING > LISTENING > IDLE.
    val phase = when {
        state.speaking.value -> VoicePhase.SPEAKING
        state.thinking.value -> VoicePhase.THINKING
        mic != MicState.OFF -> VoicePhase.LISTENING
        else -> VoicePhase.IDLE
    }
    val listenColor = Color(0xFFB3261E)  // red — listening
    val thinkColor = Color(0xFFF9A825)   // amber — waiting on the server
    val speakColor = Color(0xFF1565C0)   // blue — TTS talking
    val stopRed = Color(0xFFC62828)
    val okGreen = Color(0xFF2E7D32)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {

            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "COSMOS Voice",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "v$buildName",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { state.showSettings.value = !state.showSettings.value }) {
                    Text(if (state.showSettings.value) "hide setup" else "setup")
                }
            }

            // ============ MASTER MIC TOGGLE — the one control ============
            // Unmistakable at a glance: green = hot (wake-gated), gray = off.
            val masterOn = state.masterOn.value
            Button(
                onClick = onMasterToggle,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(72.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (masterOn) okGreen else Color(0xFF49454F)
                )
            ) {
                Text(
                    text = if (masterOn) {
                        if (state.micMode.value == MicMode.WAKE) {
                            if (state.requireWake.value) "MIC ON · say \"Cosmos …\""
                            else "MIC ON · OPEN — everything you say is sent"
                        } else "MIC ON"
                    } else "MIC OFF — tap to turn on",
                    fontSize = if (masterOn && state.micMode.value == MicMode.WAKE &&
                        !state.requireWake.value) 17.sp else 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            // Mode selector: WAKE (default, hands-free) / TAP / HOLD.
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                MicMode.values().forEach { m ->
                    val sel = state.micMode.value == m
                    Button(
                        onClick = { onMode(m) },
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp).height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sel) Color(0xFF1565C0) else Color(0xFF49454F)
                        )
                    ) {
                        Text(
                            when (m) {
                                MicMode.WAKE -> "WAKE"
                                MicMode.TAP -> "TAP"
                                MicMode.PTT -> "HOLD"
                            },
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            // Persistent hands-free banner — a hot mic is never invisible.
            if (mic == MicState.LISTENING_WAKE) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .background(okGreen)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.requireWake.value)
                            "● HANDS-FREE — only \"Cosmos …\" is sent"
                        else "● OPEN MIC — everything you say is sent",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
            // Follow-up window: ONE utterance accepted without the wake word.
            if (state.followUp.value) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(thinkColor)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "FOLLOW-UP — just speak, no wake word",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (state.pausedByControl.value) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(thinkColor)
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "PAUSED by control — nothing is sent",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = "server: ${state.connStatus.value} · net: ${state.netStatus.value}" +
                    (if (state.queueSize.value > 0) " · queued: ${state.queueSize.value}" else ""),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "voice model: ${state.modelStatus.value}" +
                    "  ·  voice out: ${state.ttsStatus.value}",
                style = MaterialTheme.typography.bodySmall
            )

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
                    onValueChange = { state.token.value = it },
                    label = { Text("Bearer token (memory only — blank = no auth)") },
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Text(
                        text = "Use phone mic (ignore Bluetooth mic)",
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.phoneMicOn.value,
                        onCheckedChange = onPhoneMic
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Require \"Cosmos\" trigger word")
                        Text(
                            text = "OFF = open listening: while MIC is ON everything " +
                                "you say is sent (WAKE mode only)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = state.requireWake.value,
                        onCheckedChange = onRequireWake
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Wake sensitivity")
                        Text(
                            text = "NORMAL also hears close mishearings of " +
                                "\"Cosmos\"; STRICT = exact word only",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    WakeSensitivity.values().forEach { s ->
                        val sel = state.wakeSensitivity.value == s
                        TextButton(onClick = { onWakeSensitivity(s) }) {
                            Text(
                                text = s.name,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                color = if (sel) Color(0xFF2E7D32)
                                    else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Text(
                        text = "Offline queue (else send-now-or-discard)",
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.offlineQueueOn.value,
                        onCheckedChange = onOfflineQueue
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

            // Stream picker — big targets, the selection tags every payload.
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                STREAMS.forEach { s ->
                    val selected = state.stream.value == s
                    Button(
                        onClick = { onStream(s) },
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp).height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFF2E7D32) else Color(0xFF49454F)
                        )
                    ) {
                        Text(s, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }

            // BootUP + session controls.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Button(
                    onClick = onBootUp,
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                ) {
                    Text("BootUP!", fontSize = 16.sp)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "session: ${state.sessionId.value?.take(8) ?: "(new)"}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onNewSession) { Text("new session") }
            }

            // Recognition-mode chip + toggle: COMMAND (grammar) / DICTATE.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (state.dictateMode.value) "● DICTATE" else "● COMMAND",
                    color = if (state.dictateMode.value) Color(0xFF1565C0) else Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDictate) {
                    Text(if (state.dictateMode.value) "back to commands" else "Dictate")
                }
            }

            // Live partial transcript
            Text(
                text = if (state.partial.value.isBlank()) " " else state.partial.value,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )

            // Big mic button — TAP = one utterance (T2T), HOLD = push-to-talk.
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
                modifier = Modifier.fillMaxWidth().height(140.dp),
                contentAlignment = Alignment.Center
            ) {
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
                            .size(118.dp)
                            .scale(ringScale.value)
                            .background(
                                micColor.value.copy(alpha = ringAlpha.value),
                                CircleShape
                            )
                    )
                }
                Box(
                    modifier = Modifier
                        .size(114.dp)
                        .background(micColor.value, CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onMicTap() },
                                onLongPress = { onPttStart() },
                                onPress = {
                                    tryAwaitRelease()
                                    onPttRelease() // no-op unless PTT is live
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (mic) {
                            MicState.OFF -> if (state.masterOn.value) "TALK" else "OFF"
                            MicState.LISTENING_T2T -> "LISTENING"
                            MicState.LISTENING_PTT -> "HOLDING"
                            MicState.LISTENING_WAKE -> "WAKE"
                        },
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
                if (phase == VoicePhase.THINKING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(130.dp),
                        color = thinkColor,
                        strokeWidth = 4.dp
                    )
                }
            }
            Text(
                text = when (state.micMode.value) {
                    MicMode.WAKE -> if (state.requireWake.value)
                        "say \"Cosmos …\" · tap the circle to interrupt speech"
                    else "open listening — everything is sent · tap the circle to interrupt"
                    MicMode.TAP -> "tap = one utterance · hold = push-to-talk"
                    MicMode.PTT -> "hold = talk · release = send"
                },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // THE BIG RED STOP — authoritative, always present, from any state.
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = stopRed)
            ) {
                Text("STOP", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }

            // Big glanceable state label — readable while driving.
            Text(
                text = when (phase) {
                    VoicePhase.LISTENING -> when {
                        mic == MicState.LISTENING_WAKE && state.followUp.value ->
                            "Follow-up — just speak"
                        mic == MicState.LISTENING_WAKE ->
                            if (state.requireWake.value) "Say \"Cosmos …\""
                            else "Open mic — all speech sent"
                        else -> "Listening…"
                    }
                    VoicePhase.THINKING -> "Thinking…"
                    VoicePhase.SPEAKING -> "Speaking…"
                    VoicePhase.IDLE ->
                        if (state.masterOn.value) "Tap to talk" else "MIC OFF"
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = when (phase) {
                    VoicePhase.LISTENING -> listenColor
                    VoicePhase.THINKING -> thinkColor
                    VoicePhase.SPEAKING -> speakColor
                    VoicePhase.IDLE -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            )

            // Verbosity + speech rate + say-again.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            ) {
                listOf("brief", "normal", "full").forEach { v ->
                    val sel = state.verbosity.value == v
                    TextButton(onClick = { onVerbosity(v) }) {
                        Text(
                            v,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onSayAgain) { Text("SAY AGAIN") }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("rate", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = state.speechRate.value,
                    onValueChange = onSpeechRate,
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(
                    String.format(Locale.US, "%.1fx", state.speechRate.value),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Confirm (single-use nonce, never auto-run). Voice ("yes"/"no")
            // always works when pending; the button is the fallback.
            if (state.pendingConfirmId.value != null) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7D5260))
                ) {
                    Text("CONFIRM — run it (or say YES / NO)")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
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
