package com.cosmos.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
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
    val listening = mutableStateOf(false)
    val partial = mutableStateOf("")
    val sessionId = mutableStateOf<String?>(null)
    val pendingConfirmId = mutableStateOf<String?>(null)
    val pendingTranscript = mutableStateOf<String?>(null)
    val console = mutableStateListOf<String>()
    val showSettings = mutableStateOf(true)
}

class MainActivity : ComponentActivity() {

    private val state = AppState()
    private var tts: TextToSpeech? = null
    private var voice: VoiceEngine? = null

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

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }

        state.modelStatus.value = if (ModelManager.isReady(this)) "ready (not loaded)" else "not downloaded"

        setContent {
            MaterialTheme {
                AppScreen(
                    state = state,
                    onConnect = { testConnection() },
                    onMic = { toggleMic() },
                    onConfirm = { confirmPending() },
                    onSave = { saveSettings() }
                )
            }
        }

        log("COSMOS Voice v0.1 — set the server URL, tap Connect, then tap MIC.")
    }

    override fun onDestroy() {
        voice?.stop()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- settings ----------

    private fun saveSettings() {
        getSharedPreferences("cosmos", Context.MODE_PRIVATE).edit()
            .putString("base_url", state.baseUrl.value.trim())
            .putString("token", state.token.value.trim())
            .apply()
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
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    state.connStatus.value = "failed: ${e.message?.take(80)}"
                    log("CONNECT FAILED: ${e.message}")
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
                    onPartial = { p -> runOnUiThread { state.partial.value = p } },
                    onFinal = { text -> runOnUiThread { onFinalTranscript(text) } },
                    onErr = { msg ->
                        runOnUiThread {
                            log("VOICE ERROR: $msg")
                            state.listening.value = false
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
                log("Listening... speak, pause to send. Tap again to stop.")
            } catch (e: Exception) {
                log("MIC START FAILED: ${e.message}")
                state.listening.value = false
            }
        }
    }

    private fun onFinalTranscript(text: String) {
        state.partial.value = ""
        if (text.isBlank()) return
        log("YOU: $text")
        send(text, null)
    }

    // ---------- COSMOS API ----------

    private fun send(transcript: String, confirmId: String?) {
        val base = state.baseUrl.value.trim().trimEnd('/')
        if (base.isBlank()) {
            log("Set the server URL first.")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject().put("transcript", transcript)
                state.sessionId.value?.let { body.put("session_id", it) }
                if (confirmId != null) body.put("confirm_id", confirmId)
                val resp = CosmosClient.postVoice(base, state.token.value.trim(), body)
                withContext(Dispatchers.Main) { handleReply(transcript, resp) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { log("SEND FAILED: ${e.message}") }
            }
        }
    }

    private fun handleReply(original: String, o: JSONObject) {
        // Carry the session forward — the sid IS the conversation.
        o.optString("session_id").takeIf { it.isNotBlank() }?.let { state.sessionId.value = it }

        val needsConfirm = o.optBoolean("needs_confirm", false)
        if (needsConfirm) {
            val cid = o.optString("confirm_id")
            if (cid.isNotBlank()) {
                state.pendingConfirmId.value = cid
                state.pendingTranscript.value = original
                log("NEEDS CONFIRM — review, then tap CONFIRM. Never auto-run.")
            }
        }

        val spoken = o.optString("spoken")
        val shown = if (spoken.isNotBlank()) spoken else o.toString().take(400)
        log("COSMOS: $shown")

        if (o.has("ok")) {
            log(if (o.optBoolean("ok", false)) "[ok]" else "[refused]")
        }
        if (o.has("http_status")) {
            log("[http ${o.optInt("http_status")}]")
        }

        if (spoken.isNotBlank()) {
            tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "cosmos-reply")
        }
    }

    private fun confirmPending() {
        val cid = state.pendingConfirmId.value ?: return
        val original = state.pendingTranscript.value ?: ""
        state.pendingConfirmId.value = null
        state.pendingTranscript.value = null
        log("CONFIRMING (nonce ${cid.take(8)}...)")
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
    onSave: () -> Unit
) {
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
                text = "server: ${state.connStatus.value}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "voice model: ${state.modelStatus.value}" +
                    (state.sessionId.value?.let { "  ·  session ${it.take(8)}" } ?: ""),
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
                    onValueChange = { state.token.value = it; onSave() },
                    label = { Text("Bearer token (blank = no auth)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
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

            // Big mic button (tap to toggle)
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(
                    onClick = onMic,
                    shape = CircleShape,
                    modifier = Modifier.size(120.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.listening.value) Color(0xFFB3261E)
                        else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (state.listening.value) "STOP" else "MIC",
                        fontSize = 20.sp
                    )
                }
            }

            // Confirm button (single-use nonce, never auto-run)
            if (state.pendingConfirmId.value != null) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7D5260))
                ) {
                    Text("CONFIRM — run it")
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
