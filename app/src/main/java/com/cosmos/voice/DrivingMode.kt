package com.cosmos.voice

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Small, dumb, testable voice grammar for eyes-free driving.
 *
 * The SERVER classifies commands — this object only decides what the PHONE
 * does locally: driving-mode toggles, yes/no answers to a pending confirm,
 * and junk gating (road noise). Spoken-reply moderation is NOT decided here:
 * it comes from the server's `kind` classification (see handleReply).
 */
object VoiceGrammar {

    /** First words the COSMOS server treats as verbs. SINGLE SOURCE OF TRUTH
     *  for the command vocabulary: the junk DROP gate, the pre-send cue hint,
     *  AND the command-mode recognition grammar (commandGrammarJson) are all
     *  built from this set so they cannot drift. Never used to moderate what
     *  the reply SPEAKS (the server's `kind` decides that). */
    val VERBS = setOf(
        "status", "health", "jobs", "spend", "rails", "makers", "events",
        "help", "submit", "session", "search", "open", "ask"
    )

    /** Filler/noise tokens Vosk hallucinates out of ambient sound. Used ONLY
     *  by the junk DROP gate: they carry no content, so an utterance made
     *  entirely of them (or of them plus one non-verb word) never leaves the
     *  phone. NOT consulted by isYes — a lone "yeah"/"okay" while a confirm
     *  is pending is still a valid answer (that path runs before the gate). */
    private val FILLERS = setOf(
        "huh", "uh", "uhh", "um", "umm", "hmm", "hm", "mm", "mmm", "mhm",
        "mhmm", "yeah", "yep", "ok", "okay", "oh", "ohh", "ah", "ahh", "eh",
        "er", "the", "a", "an", "and"
    )

    private val YES_WORDS = setOf(
        "yes", "yeah", "yep", "yup", "confirm", "affirmative", "sure",
        "ok", "okay", "correct"
    )
    private val YES_PHRASES = setOf(
        "do it", "go ahead", "send it", "yes please", "go for it", "confirm it"
    )

    /** Utterances that switch the recognizer into OPEN dictation (no grammar)
     *  for one utterance. "ask" doubles as a verb; a BARE "ask" means "let me
     *  dictate a question". */
    private val DICTATE_START = setOf("ask", "dictate", "dictation", "start dictation")

    /** Utterances that end dictation without sending anything. */
    private val DICTATE_DONE = setOf("done", "stop dictation", "end dictation")

    fun isDictateStart(norm: String): Boolean = stripWake(normalize(norm)) in DICTATE_START
    fun isDictateDone(norm: String): Boolean = stripWake(normalize(norm)) in DICTATE_DONE

    /**
     * VOSK command-mode grammar: a JSON array of every phrase the recognizer
     * is ALLOWED to emit, plus "[unk]" (anything else surfaces as [unk]
     * instead of being force-fitted into a hallucinated English sentence).
     * Built from VERBS + confirm/cancel words + driving toggles + dictation
     * triggers, so the grammar and the classifier share one vocabulary.
     */
    fun commandGrammarJson(): String {
        val phrases = LinkedHashSet<String>()
        phrases += VERBS
        phrases += "new session"
        phrases += YES_WORDS
        phrases += YES_PHRASES
        phrases += setOf("no", "nope", "cancel", "stop", "negative")
        phrases += setOf(
            "driving mode on", "driving mode off",
            "driving mode start", "driving mode stop"
        )
        phrases += DICTATE_START
        phrases += DICTATE_DONE
        phrases += "cosmos"
        phrases += "[unk]"
        return JSONArray(phrases.toList()).toString()
    }

    /** Lowercase, strip punctuation, collapse whitespace. */
    fun normalize(raw: String): String =
        raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokens(norm: String): List<String> =
        if (norm.isBlank()) emptyList() else norm.split(" ")

    /** "cosmos status" -> "status": an optional address/wake word, nothing more.
     *  Driving mode is always-listening, so the wake word is never REQUIRED. */
    fun stripWake(norm: String): String {
        val t = tokens(norm)
        return if (t.size > 1 && t[0] == "cosmos") t.drop(1).joinToString(" ") else norm
    }

    /** Is this utterance an affirmative answer to a pending confirm? */
    fun isYes(norm: String): Boolean {
        val n = stripWake(norm)
        if (n in YES_PHRASES) return true
        return tokens(n).firstOrNull() in YES_WORDS
    }

    fun isDrivingOn(norm: String): Boolean = drivingToggle(norm) == true
    fun isDrivingOff(norm: String): Boolean = drivingToggle(norm) == false

    /**
     * true = turn driving mode ON, false = OFF, null = not a toggle command.
     * Requires BOTH "driving" and "mode" so that ordinary sentences that
     * happen to contain "driving ... on" ("I am driving on the highway")
     * never flip the mode. Long sentences (> 6 words) are never toggles.
     */
    private fun drivingToggle(norm: String): Boolean? {
        val t = tokens(stripWake(norm))
        if (t.size > 6) return null
        if ("driving" !in t || "mode" !in t) return null
        if (t.any { it == "off" || it == "stop" || it == "end" || it == "disable" || it == "exit" }) return false
        if (t.any { it == "on" || it == "start" || it == "enable" || it == "begin" }) return true
        return null
    }

    /** Junk gate: fragments that are almost certainly road noise / side
     *  chatter. Dropped (never sent):
     *   - empty, or shorter than 3 characters after normalize ("", "ok", "mm")
     *   - utterances made entirely of filler tokens ("huh", "uh huh", "yeah okay")
     *   - a single real (non-filler) word that is not a COSMOS verb ("chess",
     *     "huh chess") — one content word is a fragment, not dictation
     *  Kept (sent to COSMOS, which classifies it):
     *   - anything whose first token is a known verb ("status", "ask ...")
     *   - anything with 2+ real words — genuine dictation or an ask. */
    fun isJunk(norm: String): Boolean {
        // Re-normalize defensively (idempotent) so casing/whitespace from the
        // recognizer can never matter, even if a caller passes raw text.
        val n = stripWake(normalize(norm))
        if (n.length < 3) return true
        val t = tokens(n)
        if (t.firstOrNull() in VERBS) return false // command shape — always keep
        val real = t.filterNot { it in FILLERS }   // strip filler/noise tokens
        if (real.isEmpty()) return true            // pure filler
        if (real.size == 1 && real[0] !in VERBS) return true // lone fragment
        return false                               // 2+ real words: keep
    }

    /** Does the utterance start with a known COSMOS verb (after optional wake
     *  word)? Cue-hint only — never used to moderate the spoken reply. */
    fun startsWithKnownVerb(norm: String): Boolean =
        tokens(stripWake(normalize(norm))).firstOrNull() in VERBS
}

/**
 * FIFO of /voice requests that failed to POST (no signal on the road).
 *
 * Each item is the FULL request body as a JSON string — transcript,
 * request_id (idempotency key, generated at the FIRST attempt so the flush
 * re-sends the SAME id and the server can dedupe), and session_id — so a
 * flush replays the original request, not a reconstruction. Legacy items
 * that are bare transcripts (pre-request_id builds) are still accepted by
 * the flusher.
 *
 * In-memory list mirrored to SharedPreferences as a JSON array on every
 * mutation, so queued commands survive an app restart. A command is only
 * removed AFTER its re-POST succeeds — a flush interrupted by signal loss
 * leaves the remainder queued. Never silently lose a command.
 *
 * Bounded at MAX_ITEMS: past the cap the OLDEST item is dropped (a bounded,
 * reported loss beats an unbounded SharedPreferences string). A corrupt
 * persisted queue is never silently discarded — the raw string is preserved
 * under a backup key and the error surfaces via loadError.
 */
class OfflineQueue(private val prefs: SharedPreferences) {

    // Each element is a wrapper JSON string: {"body": <request JSON>, "ts": <epoch ms>}.
    // The timestamp exists so stale voice never replays (see pruneStale).
    private val items = mutableListOf<String>()

    /** Non-null when the persisted queue failed to parse at startup. The raw
     *  string was preserved under KEY_CORRUPT_BACKUP for post-mortem. */
    var loadError: String? = null
        private set

    /** Items discarded at construction. Voice is EPHEMERAL: a backlog from a
     *  previous process is stale by definition (queued commands cannot be told
     *  apart from dictation client-side — the SERVER classifies), so a fresh
     *  app start drops the whole persisted backlog and reports the count.
     *  Without this, reconnecting replayed old context ("scrolls back"). */
    var droppedAtLoad: Int = 0
        private set

    init {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            droppedAtLoad = arr.length()
            if (droppedAtLoad > 0) persist() // start empty; count reported by caller
        } catch (e: Exception) {
            // NEVER silently discard: preserve the corrupt payload for
            // post-mortem and surface the error to the caller's log.
            prefs.edit().putString(KEY_CORRUPT_BACKUP, raw).apply()
            loadError = "offline queue was corrupt — preserved to '$KEY_CORRUPT_BACKUP' " +
                "(${raw.length} chars): ${e.message}"
        }
    }

    val size: Int get() = synchronized(items) { items.size }

    /** Add a full request-body JSON string (timestamped now). Returns the
     *  number of oldest items dropped to stay under the cap (0 normally). */
    fun add(requestJson: String): Int {
        synchronized(items) {
            items.add(
                JSONObject()
                    .put("body", requestJson)
                    .put("ts", System.currentTimeMillis())
                    .toString()
            )
            var dropped = 0
            while (items.size > MAX_ITEMS) {
                items.removeAt(0)
                dropped++
            }
            persist()
            return dropped
        }
    }

    /** Drop every queued item older than MAX_AGE_MS — a stale voice command
     *  must NOT replay when signal returns. Items with no timestamp (legacy
     *  builds) are treated as stale. Call before flushing. Returns the count
     *  dropped so the caller can log it. */
    fun pruneStale(now: Long = System.currentTimeMillis()): Int {
        synchronized(items) {
            val before = items.size
            items.retainAll { item ->
                val ts = try {
                    JSONObject(item).optLong("ts", -1L)
                } catch (e: Exception) {
                    -1L
                }
                ts >= 0 && now - ts <= MAX_AGE_MS
            }
            val dropped = before - items.size
            if (dropped > 0) persist()
            return dropped
        }
    }

    /** Oldest item's request BODY, without removing it (remove only after a
     *  successful send). Unwraps the {body, ts} envelope; a legacy bare item
     *  is returned as-is. */
    fun peek(): String? = synchronized(items) {
        items.firstOrNull()?.let { item ->
            try {
                val o = JSONObject(item)
                if (o.has("body")) o.optString("body", item) else item
            } catch (e: Exception) {
                item
            }
        }
    }

    fun removeFirst() {
        synchronized(items) {
            if (items.isNotEmpty()) {
                items.removeAt(0)
                persist()
            }
        }
    }

    private fun persist() {
        prefs.edit().putString(KEY, JSONArray(items).toString()).apply()
    }

    companion object {
        const val MAX_ITEMS = 100
        /** Voice goes stale fast: anything older than this never replays. */
        const val MAX_AGE_MS = 120_000L
        private const val KEY = "offline_queue"
        private const val KEY_CORRUPT_BACKUP = "offline_queue_corrupt_backup"
    }
}
