package com.cosmos.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Bundled OFFLINE text-to-speech: sherpa-onnx OfflineTts (Piper VITS voice)
 * synthesized on-device and played through AudioTrack. ZERO dependency on any
 * device TTS engine or Google service — this is what speaks on a stripped
 * phone, and it is the DEFAULT engine (android.speech.tts is only a fallback
 * while the voice model is still downloading, and only if a device engine
 * happens to exist).
 *
 * Mirrors android.speech.tts semantics the rest of the app already relies on:
 *  - speak(flush=true)  == QUEUE_FLUSH: stops current playback + clears queue
 *  - speak(flush=false) == QUEUE_ADD:   queues behind current speech
 *  - onStart/onDone fire per utterance (main thread), so the driving-mode
 *    mic-restart-after-speak and the confirm echo-guard keep working unchanged
 *  - stop() == barge-in: immediate silence, no onDone for the cut utterance
 *    (the caller clears its own flags, exactly as it did with tts.stop())
 *
 * Audio is routed as MEDIA (USAGE_MEDIA / CONTENT_TYPE_SPEECH) so it plays
 * over Bluetooth media (TOZO HT3) and the phone speaker.
 *
 * Threading: speak()/stop()/shutdown() must be called from the MAIN thread
 * (they are — MainActivity only touches TTS on main). Synthesis + playback
 * run on a single background worker; callbacks are posted back to main.
 */
class TtsEngine(
    private val onStart: (utteranceId: String) -> Unit,
    private val onDone: (utteranceId: String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tts: OfflineTts? = null
    private var sampleRate = 22_050 // replaced by the model's real rate in init()

    @Volatile
    var isReady = false
        private set

    /** Speech rate multiplier (1.0 = normal). Driven by the UI slider; applied
     *  per utterance at synthesis time, so the change takes effect on the very
     *  next thing spoken. */
    @Volatile var speed: Float = 1.0f

    // Bumped by every flush/stop. An utterance whose gen no longer matches is
    // stale: skipped if still queued, aborted mid-synthesis, and its onDone is
    // suppressed (the interrupter already owns the speaking flags).
    @Volatile private var generation = 0L

    @Volatile private var currentTrack: AudioTrack? = null

    private class Utterance(val text: String, val id: String, val gen: Long)

    private val queue = Channel<Utterance>(Channel.UNLIMITED)

    init {
        // Single worker == utterances play strictly in order (QUEUE_ADD).
        scope.launch {
            for (utt in queue) {
                if (utt.gen != generation) continue // flushed while queued
                playUtterance(utt)
            }
        }
    }

    /**
     * Blocking model load (a few seconds) — call on Dispatchers.IO, after
     * TtsModelManager.isReady() is true. Idempotent.
     */
    fun init(context: Context) {
        if (isReady) return
        val dir = TtsModelManager.modelDir(context).absolutePath
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$dir/${TtsModelManager.MODEL_FILE}",
                    tokens = "$dir/tokens.txt",
                    // Piper needs espeak-ng-data for phonemization; the model
                    // archive bundles it and TtsModelManager verified it.
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
        )
        val engine = OfflineTts(config = config)
        sampleRate = engine.sampleRate()
        tts = engine
        isReady = true
    }

    /**
     * Speak [text]. flush=true stops current playback and clears the queue
     * first (QUEUE_FLUSH / barge-in); flush=false queues after (QUEUE_ADD).
     * Returns false (and speaks nothing) when the engine is not ready —
     * the caller decides whether to fall back or drop with a log.
     */
    fun speak(text: String, utteranceId: String, flush: Boolean): Boolean {
        if (!isReady) return false
        if (flush) interrupt()
        queue.trySend(Utterance(text, utteranceId, generation))
        return true
    }

    /** Barge-in: immediate silence + queue cleared. No onDone fires for the
     *  cut utterance (caller clears its own speaking flags, as with tts.stop()). */
    fun stop() {
        interrupt()
    }

    fun shutdown() {
        interrupt()
        scope.cancel()
        queue.close()
        try {
            tts?.release()
        } catch (e: Exception) {
            // native teardown is best-effort
        }
        tts = null
        isReady = false
    }

    private fun interrupt() {
        generation += 1
        val t = currentTrack
        if (t != null) {
            try {
                t.pause()
                t.flush()
            } catch (e: Exception) {
                // track may already be released by the worker — fine
            }
        }
    }

    /** Runs on the worker. Synthesizes with a streaming callback (audio starts
     *  before the full utterance is generated) and blocks until played out. */
    private fun playUtterance(utt: Utterance) {
        val engine = tts ?: return
        main.post { onStart(utt.id) }
        var track: AudioTrack? = null
        var framesWritten = 0L
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // MEDIA routing: plays over Bluetooth media (TOZO HT3)
                        // and the speaker, honors the media volume the driver
                        // already controls from the wheel/headset.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 8_192) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            currentTrack = t
            t.play()

            // sherpa-onnx streams synthesized chunks into the callback;
            // samples are floats in [-1, 1] at the model's sample rate.
            // Return 1 to continue synthesis, 0 to abort (barge-in).
            engine.generateWithCallback(text = utt.text, speed = speed) { samples ->
                if (utt.gen != generation) {
                    0
                } else {
                    val n = t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    if (n > 0) framesWritten += n
                    if (utt.gen == generation) 1 else 0
                }
            }

            // Drain: wait until the sink actually played what we wrote, so
            // onDone (-> driving-mode mic restart) fires after the SOUND ends,
            // not after the last buffer write. Bail instantly on barge-in.
            while (utt.gen == generation && framesWritten > 0) {
                val pos = t.playbackHeadPosition.toLong()
                if (pos >= framesWritten) break
                Thread.sleep(30)
            }
        } catch (e: Exception) {
            // Synthesis/audio failure: fall through to onDone so the caller's
            // speaking flags never stay stuck (the confirm echo-guard would
            // otherwise ignore finals forever).
        } finally {
            currentTrack = null
            track?.let { tr ->
                try {
                    tr.stop()
                } catch (e: Exception) {
                    // already stopped
                }
                try {
                    tr.release()
                } catch (e: Exception) {
                    // already released
                }
            }
            if (utt.gen == generation) {
                main.post { onDone(utt.id) }
            }
        }
    }
}
