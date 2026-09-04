package dev.elias.assistivetouchpeeker.detection

import dev.elias.assistivetouchpeeker.storage.CustomGesture
import kotlin.math.sqrt

/**
 * Scores a live embedding against each enrolled gesture's trained [GestureHead], gated by
 * [DetectionConfig.CUSTOM_GESTURE_SCORE_THRESHOLD] and, once more than one gesture is
 * enrolled, a margin over the runner-up ([DetectionConfig.CUSTOM_GESTURE_MARGIN]).
 *
 * These are calibrated probabilities from a classifier trained against hundreds of
 * negatives, not distances - which is what makes them separate cleanly. The previous
 * cosine-to-prototype version had no notion of what *isn't* the gesture, so ordinary idle
 * motion sat around 55% similarity and left almost no usable dynamic range.
 */
object CustomGestureMatcher {

    fun bestMatch(embedding: FloatArray, gestures: List<CustomGesture>): CustomGesture? {
        if (gestures.isEmpty()) return null
        val scored = gestures.map { it to it.head.score(embedding) }.sortedByDescending { it.second }
        val (best, bestScore) = scored.first()
        if (bestScore < DetectionConfig.CUSTOM_GESTURE_SCORE_THRESHOLD) return null
        if (scored.size > 1 && bestScore - scored[1].second < DetectionConfig.CUSTOM_GESTURE_MARGIN) return null
        return best
    }

    /** Every gesture's live score, for the on-screen % readouts. */
    fun scoreAll(embedding: FloatArray, gestures: List<CustomGesture>): Map<String, Float> =
        gestures.associate { it.id to it.head.score(embedding) }
}

fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size) { "Vector sizes must match: ${a.size} vs ${b.size}." }
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denominator = sqrt(normA) * sqrt(normB)
    return if (denominator < 1e-12f) 0f else dot / denominator
}
