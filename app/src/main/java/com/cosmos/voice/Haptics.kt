package com.cosmos.voice

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Eyes-free haptic cues. Every effect is short and distinct so the driver can
 * tell "hearing you" / "got it" / "reply" / "CONFIRM NEEDED" apart by feel
 * alone, without looking at the screen.
 *
 * All calls are best-effort: no vibrator service, no vibrator hardware, or a
 * framework quirk simply means silence — never a crash in the voice path.
 * `enabled` mirrors the settings toggle (persisted by MainActivity).
 */
class Haptics(context: Context) {

    @Volatile var enabled: Boolean = true

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        null
    }

    /** Short single tick — the mic just STARTED listening ("I'm hearing you"). */
    fun micStart() = play(
        longArrayOf(0, 30),
        intArrayOf(0, 160)
    )

    /** Quick double tick — utterance recognized and SENT to COSMOS ("got it"). */
    fun sent() = play(
        longArrayOf(0, 25, 60, 25),
        intArrayOf(0, 160, 0, 160)
    )

    /** Soft single buzz — a reply arrived / TTS is starting to speak. */
    fun reply() = play(
        longArrayOf(0, 40),
        intArrayOf(0, 90)
    )

    /** Two strong pulses — REFUSAL or a needs_confirm prompt. Deliberately the
     *  heaviest pattern here: a consequential confirm must be FELT. */
    fun alert() = play(
        longArrayOf(0, 80, 100, 80),
        intArrayOf(0, 255, 0, 255)
    )

    private fun play(timings: LongArray, amplitudes: IntArray) {
        if (!enabled) return
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            // Haptics are decoration — never let them take down the voice path.
        }
    }
}
