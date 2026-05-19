/*
 * Copyright (C) 2025 Zeliboard Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package helium314.keyboard.latin.gesturetyping

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json

/**
 * Persists per-word gesture acceptance counts so that Zeliboard can boost the
 * ranking of words you frequently swipe-type.
 *
 * Acceptance is recorded whenever the user commits a gesture-decoded suggestion
 * without further editing. Counts decay slowly over time so stale patterns don't
 * dominate.  Everything stays on-device — no network calls, no shared storage.
 *
 * Usage:
 *   val gp = GesturePersonalization.getInstance(context)
 *   gp.recordAccepted("hello")             // call when user commits swipe result
 *   val boost = gp.getBoost("hello")       // 1.0..MAX_BOOST multiplier for scoring
 */
class GesturePersonalization private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // word → acceptance count (stored as JSON map)
    private val counts: MutableMap<String, Int> = loadCounts()

    /** Record that the user accepted [word] from a glide-typing result. */
    fun recordAccepted(word: String) {
        if (word.isBlank()) return
        val key = word.lowercase().trim()
        counts[key] = (counts[key] ?: 0) + 1
        persistCounts()
        pruneIfNeeded()
    }

    /**
     * Returns a score multiplier in [1.0, MAX_BOOST] for [word].
     * Words never confirmed via gesture typing return 1.0 (no boost).
     */
    fun getBoost(word: String): Float {
        val key = word.lowercase().trim()
        val count = counts[key] ?: return 1f
        return 1f + (MAX_BOOST - 1f) * (count.toFloat() / (count + SATURATION_COUNT))
    }

    /**
     * Apply personalisation boosts to a list of gesture candidates in-place.
     * Mutates the list's scores by creating new SuggestedWordInfo with boosted scores.
     */
    fun applyBoosts(
        candidates: List<helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo>,
    ): List<helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo> {
        return candidates.map { info ->
            val boost = getBoost(info.mWord)
            if (boost == 1f) info
            else helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo(
                info.mWord,
                info.mPrevWordsContext,
                (info.mScore * boost).toInt().coerceIn(0, 255),
                info.mKindAndFlags,
                info.mSourceDict,
                info.mIndexOfTouchPointOfSecondWord,
                info.mAutoCommitFirstWordConfidence,
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun loadCounts(): MutableMap<String, Int> = try {
        val raw = prefs.getString(KEY_COUNTS, null) ?: return mutableMapOf()
        Json.decodeFromString<Map<String, Int>>(raw).toMutableMap()
    } catch (_: Exception) {
        mutableMapOf()
    }

    private fun persistCounts() {
        prefs.edit { putString(KEY_COUNTS, Json.encodeToString(counts.toMap())) }
    }

    private fun pruneIfNeeded() {
        if (counts.size > MAX_ENTRIES) {
            // Remove the least-used entries
            val sorted = counts.entries.sortedBy { it.value }
            sorted.take(counts.size - MAX_ENTRIES).forEach { counts.remove(it.key) }
            persistCounts()
        }
    }

    companion object {
        private const val PREFS_NAME = "zeliboard_gesture_personalization"
        private const val KEY_COUNTS = "word_counts"
        private const val MAX_BOOST = 2.5f      // maximum score multiplier for a well-known word
        private const val SATURATION_COUNT = 10 // confirmations needed to reach ~half of max boost
        private const val MAX_ENTRIES = 2000    // prune when dict grows beyond this

        @Volatile private var instance: GesturePersonalization? = null

        @JvmStatic
        fun getInstance(context: Context): GesturePersonalization =
            instance ?: synchronized(this) {
                instance ?: GesturePersonalization(context).also { instance = it }
            }
    }
}
