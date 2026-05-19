/*
 * Copyright (C) 2025 Zeliboard Contributors
 * Ported and adapted from FlorisBoard (Apache 2.0)
 * Original: github.com/florisboard/florisboard — StatisticalGlideTypingClassifier
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package helium314.keyboard.latin.gesturetyping

import helium314.keyboard.keyboard.Key
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.dictionary.Dictionary
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Statistical glide/swipe typing classifier for Zeliboard.
 *
 * Adapted from FlorisBoard's StatisticalGlideTypingClassifier (Apache 2.0).
 * Algorithm: resample gesture + word templates to a fixed point count, compute
 * shape similarity via Euclidean distance, weight by dictionary frequency, and
 * return the top candidates — all purely on-device, zero network calls.
 */
object GlideTypingClassifier {

    private const val RESAMPLE_COUNT = 100
    private const val MAX_SUGGESTIONS = 8
    // Shape-match bandwidth in normalised key-size units; 1.5 = tolerant enough to handle
    // natural gesture variation without float underflow (exp underflows to 0 for SIGMA<0.5
    // when shapeDist exceeds ~3 key-widths, which is common on non-matching words)
    private const val SIGMA = 1.5f
    // Minimum gesture displacement (px) before we attempt decoding
    private const val MIN_GESTURE_DISPLACEMENT = 10f

    /**
     * Decode a gesture into word candidates.
     *
     * @param inputPointers  Raw touch points collected by PointerTracker.
     * @param keyboard       Current keyboard layout (used for key-center lookup).
     * @param candidates     Pre-filtered SuggestedWordInfo list from the dictionary
     *                       (caller should supply words beginning with the first gesture key).
     * @return Up to [MAX_SUGGESTIONS] SuggestedWordInfo entries, ordered by score descending.
     */
    fun getSuggestions(
        inputPointers: InputPointers,
        keyboard: Keyboard,
        candidates: List<SuggestedWordInfo>,
    ): List<SuggestedWordInfo> {
        val count = inputPointers.pointerSize
        if (count < 2 || candidates.isEmpty()) return emptyList()

        val rawX = inputPointers.xCoordinates
        val rawY = inputPointers.yCoordinates

        // Reject micro-movements (accidental touches)
        val dx = (rawX[count - 1] - rawX[0]).toFloat()
        val dy = (rawY[count - 1] - rawY[0]).toFloat()
        if (hypot(dx, dy) < MIN_GESTURE_DISPLACEMENT) return emptyList()

        // Normalise bandwidth to current key size so it's resolution-independent
        val keySize = ((keyboard.mMostCommonKeyWidth + keyboard.mMostCommonKeyHeight) / 2f)
            .coerceAtLeast(1f)

        // Resample the gesture to a fixed-length path
        val (gestureX, gestureY) = resamplePath(rawX, rawY, count, RESAMPLE_COUNT)

        // Determine start key constraint from the gesture start point
        val firstKey = nearestKey(rawX[0], rawY[0], keyboard)
        val startCode = firstKey?.code?.let { Character.toLowerCase(it) } ?: 0

        // Normalise frequency relative to the highest-scoring candidate in this batch
        val maxCandidateScore = candidates.maxOf { it.mScore.toLong() }.toFloat().coerceAtLeast(1f)

        val scored = mutableListOf<Pair<SuggestedWordInfo, Float>>()

        for (wordInfo in candidates) {
            val word = wordInfo.mWord.lowercase()
            if (word.length < 2) continue

            // Hard filter by first letter only — shape scoring handles the rest
            if (startCode != 0 && word[0].code != startCode) continue

            val template = buildTemplate(word, keyboard) ?: continue
            val (tmplX, tmplY) = resamplePath(template.first, template.second, template.first.size, RESAMPLE_COUNT)

            val shapeDist = averageDistance(gestureX, gestureY, tmplX, tmplY, keySize)
            // Gaussian score: 1.0 = perfect shape match, approaches 0 as dist grows
            val shapeScore = exp(-(shapeDist * shapeDist) / (2f * SIGMA * SIGMA))

            // Combine shape score with dictionary frequency (normalised relative to batch max)
            val freqNorm = (wordInfo.mScore.toFloat() / maxCandidateScore).coerceIn(0f, 1f)
            val combined = shapeScore * (0.7f + 0.3f * freqNorm)

            scored += Pair(wordInfo, combined)
        }

        return scored
            .sortedByDescending { it.second }
            .take(MAX_SUGGESTIONS)
            .map { (info, score) ->
                // Produce a new SuggestedWordInfo with the combined score so the suggestion
                // strip sorts them correctly
                SuggestedWordInfo(
                    info.mWord,
                    info.mPrevWordsContext,
                    (score * 255).toInt().coerceIn(0, 255),
                    SuggestedWordInfo.KIND_CORRECTION,
                    Dictionary.DICTIONARY_USER_TYPED,
                    SuggestedWordInfo.NOT_AN_INDEX,
                    SuggestedWordInfo.NOT_A_CONFIDENCE,
                )
            }
    }

    // ── Path helpers ──────────────────────────────────────────────────────────

    /**
     * Resample a variable-length path to exactly [targetCount] evenly-spaced points
     * by linearly interpolating along the arc length.
     */
    internal fun resamplePath(
        xs: IntArray,
        ys: IntArray,
        size: Int,
        targetCount: Int,
    ): Pair<FloatArray, FloatArray> {
        if (size == 0) return Pair(FloatArray(0), FloatArray(0))
        if (size == 1) {
            val outX = FloatArray(targetCount) { xs[0].toFloat() }
            val outY = FloatArray(targetCount) { ys[0].toFloat() }
            return Pair(outX, outY)
        }

        // Build cumulative arc-length table
        val arcLen = FloatArray(size)
        for (i in 1 until size) {
            val segDx = (xs[i] - xs[i - 1]).toFloat()
            val segDy = (ys[i] - ys[i - 1]).toFloat()
            arcLen[i] = arcLen[i - 1] + hypot(segDx, segDy)
        }
        val totalLen = arcLen[size - 1]

        val outX = FloatArray(targetCount)
        val outY = FloatArray(targetCount)
        var segIdx = 0

        for (k in 0 until targetCount) {
            val targetArc = totalLen * k / (targetCount - 1).toFloat()
            // Advance segment pointer
            while (segIdx < size - 2 && arcLen[segIdx + 1] < targetArc) segIdx++
            val segLen = arcLen[segIdx + 1] - arcLen[segIdx]
            val t = if (segLen < 1e-6f) 0f
                    else (targetArc - arcLen[segIdx]) / segLen
            outX[k] = xs[segIdx] + t * (xs[segIdx + 1] - xs[segIdx])
            outY[k] = ys[segIdx] + t * (ys[segIdx + 1] - ys[segIdx])
        }
        return Pair(outX, outY)
    }

    /**
     * Overload for FloatArrays (used when building templates from key centers).
     */
    private fun resamplePath(
        xs: FloatArray,
        ys: FloatArray,
        size: Int,
        targetCount: Int,
    ): Pair<FloatArray, FloatArray> {
        val ixs = IntArray(size) { xs[it].toInt() }
        val iys = IntArray(size) { ys[it].toInt() }
        return resamplePath(ixs, iys, size, targetCount)
    }

    /**
     * Build an ideal key-center path for [word] on the given [keyboard].
     * Returns null if any letter has no corresponding key on the layout.
     */
    internal fun buildTemplate(word: String, keyboard: Keyboard): Pair<FloatArray, FloatArray>? {
        val xs = FloatArray(word.length)
        val ys = FloatArray(word.length)
        for (i in word.indices) {
            val key = keyboard.getKey(word[i].code) ?: return null
            xs[i] = key.x + key.width / 2f
            ys[i] = key.y + key.height / 2f
        }
        return Pair(xs, ys)
    }

    /**
     * Mean per-point distance between two same-length resampled paths,
     * normalised by [keySize] so the result is in key-size units.
     */
    private fun averageDistance(
        gx: FloatArray, gy: FloatArray,
        tx: FloatArray, ty: FloatArray,
        keySize: Float,
    ): Float {
        var total = 0f
        for (i in gx.indices) {
            total += hypot(gx[i] - tx[i], gy[i] - ty[i])
        }
        return (total / gx.size) / keySize
    }

    /** Returns the key whose centre is closest to (x, y), or null if the keyboard is empty. */
    private fun nearestKey(x: Int, y: Int, keyboard: Keyboard): Key? {
        val keys = keyboard.sortedKeys
        if (keys.isEmpty()) return null
        var best: Key? = null
        var bestDist = Float.MAX_VALUE
        for (key in keys) {
            if (key.isSpacer || !Character.isLetter(key.code)) continue
            val cx = key.x + key.width / 2f
            val cy = key.y + key.height / 2f
            val dist = hypot(cx - x, cy - y)
            if (dist < bestDist) { bestDist = dist; best = key }
        }
        return best
    }

}
