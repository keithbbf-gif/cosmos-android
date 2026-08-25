package com.cosmos.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzzy wake-word matching (VoiceGrammar.hasWake / stripWake / isWakeToken).
 *
 * The contract under test:
 *  - STRICT accepts ONLY an exact "cosmos" (bare or after hey/ok/okay).
 *  - NORMAL additionally accepts the closed variant set (cosmo, cosmic,
 *    cosms, kosmos) plus anything within ONE edit of "cosmos" that is
 *    5..7 chars and starts with c/k.
 *  - Common English words ("cost", "because", "customer", "cosmetic", ...)
 *    must NEVER match at any level — a wake word that fires on ordinary
 *    conversation is worse than one that misses in wind noise.
 *  - The wake token only counts as the FIRST word (or second, after a
 *    hey/ok/okay prefix): "the cosmos is big" never triggers.
 */
class VoiceGrammarWakeTest {

    private val N = WakeSensitivity.NORMAL
    private val S = WakeSensitivity.STRICT

    // ---------- exact forms: both levels ----------

    @Test
    fun exactWake_matchesAtBothLevels() {
        for (level in listOf(N, S)) {
            assertTrue(VoiceGrammar.hasWake("cosmos status", level))
            assertTrue(VoiceGrammar.hasWake("hey cosmos status", level))
            assertTrue(VoiceGrammar.hasWake("okay cosmos status", level))
            assertTrue(VoiceGrammar.hasWake("ok cosmos status", level))
            assertTrue(VoiceGrammar.hasWake("cosmos", level)) // bare wake
        }
    }

    @Test
    fun exactWake_strips() {
        assertEquals("status", VoiceGrammar.stripWake("cosmos status", S))
        assertEquals("status", VoiceGrammar.stripWake("hey cosmos status", S))
        assertEquals("", VoiceGrammar.stripWake("cosmos", S))
        assertEquals("", VoiceGrammar.stripWake("hey cosmos", S))
    }

    // ---------- variants: NORMAL accepts, STRICT refuses ----------

    @Test
    fun variants_matchAtNormal() {
        // Closed allow-set.
        assertTrue(VoiceGrammar.hasWake("cosmo status", N))
        assertTrue(VoiceGrammar.hasWake("cosmic status", N))
        assertTrue(VoiceGrammar.hasWake("cosms status", N))
        assertTrue(VoiceGrammar.hasWake("kosmos status", N))
        // Edit-distance <= 1 against "cosmos".
        assertTrue(VoiceGrammar.hasWake("cosmoss status", N)) // insertion
        assertTrue(VoiceGrammar.hasWake("cosmus status", N))  // substitution
        // Variant after a prefix.
        assertTrue(VoiceGrammar.hasWake("hey cosmo status", N))
        assertTrue(VoiceGrammar.hasWake("okay cosmic status", N))
        // Bare variant (opens the command window, like bare "cosmos").
        assertTrue(VoiceGrammar.hasWake("cosmic", N))
    }

    @Test
    fun variants_stripAtNormal() {
        assertEquals("status", VoiceGrammar.stripWake("cosmo status", N))
        assertEquals("status", VoiceGrammar.stripWake("hey cosmic status", N))
        assertEquals("", VoiceGrammar.stripWake("kosmos", N))
    }

    @Test
    fun variants_refusedAtStrict() {
        assertFalse(VoiceGrammar.hasWake("cosmo status", S))
        assertFalse(VoiceGrammar.hasWake("cosmic status", S))
        assertFalse(VoiceGrammar.hasWake("kosmos status", S))
        assertFalse(VoiceGrammar.hasWake("hey cosmo status", S))
        // And strip leaves the utterance untouched.
        assertEquals("cosmo status", VoiceGrammar.stripWake("cosmo status", S))
    }

    // ---------- NON-triggers: common English must never wake ----------

    @Test
    fun commonWords_neverMatch() {
        val nonTriggers = listOf(
            "cost", "costs", "because", "customer", "cosmetic",
            "cause", "chaos", "canvas", "campus", "chorus", "compass"
        )
        for (w in nonTriggers) {
            for (level in listOf(N, S)) {
                assertFalse("\"$w\" must not be a wake token ($level)",
                    VoiceGrammar.isWakeToken(w, level))
                assertFalse("\"$w status\" must not wake ($level)",
                    VoiceGrammar.hasWake("$w status", level))
            }
        }
    }

    @Test
    fun nonTriggerPhrases_passThroughStripUnchanged() {
        assertEquals("cost status", VoiceGrammar.stripWake("cost status", N))
        assertEquals("because i said so", VoiceGrammar.stripWake("because i said so", N))
        assertEquals("customer service", VoiceGrammar.stripWake("customer service", N))
    }

    // ---------- position: wake must be the first (addressed) token ----------

    @Test
    fun wakeMidSentence_neverMatches() {
        assertFalse(VoiceGrammar.hasWake("the cosmos is big", N))
        assertFalse(VoiceGrammar.hasWake("i love the cosmos", N))
        // Prefix only pairs with a wake token directly after it.
        assertFalse(VoiceGrammar.hasWake("hey there cosmos", N))
        assertFalse(VoiceGrammar.hasWake("", N))
    }

    // ---------- edit distance itself ----------

    @Test
    fun editDistance_basics() {
        assertEquals(0, VoiceGrammar.editDistance("cosmos", "cosmos"))
        assertEquals(1, VoiceGrammar.editDistance("cosmo", "cosmos"))
        assertEquals(1, VoiceGrammar.editDistance("kosmos", "cosmos"))
        assertEquals(2, VoiceGrammar.editDistance("cosmic", "cosmos"))
        assertEquals(6, VoiceGrammar.editDistance("", "cosmos"))
    }

    // ---------- the live default level ----------

    @Test
    fun liveDefault_followsWakeSensitivityVar() {
        val saved = VoiceGrammar.wakeSensitivity
        try {
            VoiceGrammar.wakeSensitivity = WakeSensitivity.NORMAL
            assertTrue(VoiceGrammar.hasWake("cosmo status"))
            VoiceGrammar.wakeSensitivity = WakeSensitivity.STRICT
            assertFalse(VoiceGrammar.hasWake("cosmo status"))
            assertTrue(VoiceGrammar.hasWake("cosmos status"))
        } finally {
            VoiceGrammar.wakeSensitivity = saved
        }
    }
}
