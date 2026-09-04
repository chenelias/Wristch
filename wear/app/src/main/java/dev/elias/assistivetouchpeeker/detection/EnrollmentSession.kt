package dev.elias.assistivetouchpeeker.detection

import android.util.Log
import dev.elias.assistivetouchpeeker.dsp.FeatureExtractor
import dev.elias.assistivetouchpeeker.dsp.WINDOW_SAMPLES
import dev.elias.assistivetouchpeeker.dsp.augmentPositive
import dev.elias.assistivetouchpeeker.dsp.findPeakIndex
import dev.elias.assistivetouchpeeker.dsp.negativeVariants
import dev.elias.assistivetouchpeeker.dsp.resampleSnapshot
import dev.elias.assistivetouchpeeker.ml.EmbeddingExtractor
import dev.elias.assistivetouchpeeker.ml.GestureClass
import dev.elias.assistivetouchpeeker.ml.GestureClassifier
import dev.elias.assistivetouchpeeker.sensing.ImuSensorSource
import java.util.Random

/** Tunable enrollment parameters - adjust here while validating on-watch. */
object EnrollmentConfig {
    /**
     * Repetitions required per custom gesture. The paper's own real-world usability
     * study defaults to 3; this build uses more because it skips the paper's Δ-encoder
     * data synthesis and adversarial-training accuracy boosts, so more real shots
     * partially compensates.
     */
    const val REPS_REQUIRED = 5

    /** How long each rep's capture window waits after the "Go!" cue before searching for the peak. */
    const val CAPTURE_WINDOW_MS = 2_000L

    /** Extra augmented embedding samples generated per accepted rep (zoom/scale/time-warp). */
    const val AUGMENTATIONS_PER_REP = 4

    /**
     * Raw samples kept around each rep's peak for augmentation - wider than [WINDOW_SAMPLES]
     * so a zoomed-out variant (as slow as 0.85x, see [augmentPositive]) still has at least
     * [WINDOW_SAMPLES] left after cropping back down.
     */
    const val MARGIN_SAMPLES = 130

    /** A rep is an outlier if its embedding's cosine similarity to the centroid falls below this. */
    const val REP_CONSISTENCY_THRESHOLD = 0.85f

    /** Reject enrollment if more than this many of [REPS_REQUIRED] reps are outliers. */
    const val MAX_OUTLIER_REPS = 1

    /**
     * A rep is "confused with daily activities" if its cosine similarity to its nearest
     * neighbor in the bundled negative-embedding pool meets or exceeds this.
     */
    const val DAILY_ACTIVITY_CONFUSION_THRESHOLD = 0.92f
}

sealed interface EnrollmentOutcome {
    data class Success(val head: GestureHead) : EnrollmentOutcome
    object InsufficientData : EnrollmentOutcome
    object TooInconsistent : EnrollmentOutcome
    object ConfusedWithDailyActivity : EnrollmentOutcome
    data class SimilarToExisting(val gestureClass: GestureClass) : EnrollmentOutcome
}

/**
 * Captures [EnrollmentConfig.REPS_REQUIRED] repetitions of a new custom gesture, checks
 * them against all three of the paper's sanity checks (Section 4.3 - "similar to an
 * existing gesture", "inconsistent", and "confused with daily activities", the last via
 * nearest-neighbor distance against [negativeEmbeddingPool] rather than the paper's full
 * offline HDBSCAN clustering), augments each with a few zoom/scale/time-warp variants,
 * and averages everything into one prototype embedding for
 * [dev.elias.assistivetouchpeeker.storage.CustomGestureStore].
 */
class EnrollmentSession(
    private val imuSource: ImuSensorSource,
    private val featureExtractor: FeatureExtractor,
    private val embeddingExtractor: EmbeddingExtractor,
    private val classifier: GestureClassifier,
    private val negativeEmbeddingPool: Array<FloatArray>,
    private val baseGestureEmbeddingPool: Array<FloatArray>,
) {
    private val random = Random()
    private val repMarginSignals = mutableListOf<Array<FloatArray>>()

    val capturedReps: Int get() = repMarginSignals.size

    /**
     * Finds the peak in whatever the IMU buffer currently holds and keeps a margin window
     * around it for later augmentation. Returns false if the buffer wasn't full enough to
     * search. The caller owns the recording window's timing (and its haptic cues), so
     * call this once the user has finished performing the rep.
     */
    fun captureRep(): Boolean {
        val snapshot = imuSource.snapshot()
        if (snapshot == null) {
            Log.d(TAG, "captureRep: no IMU snapshot (buffer not full enough yet)")
            return false
        }
        val resampled = resampleSnapshot(snapshot, minSamples = EnrollmentConfig.MARGIN_SAMPLES)
        if (resampled == null) {
            Log.d(TAG, "captureRep: resampled span shorter than MARGIN_SAMPLES=${EnrollmentConfig.MARGIN_SAMPLES}")
            return false
        }
        val features = featureExtractor.extract(resampled)
        val peakIndex = findPeakIndex(features)
        if (peakIndex == null) {
            Log.d(TAG, "captureRep: findPeakIndex returned null for ${features.size} samples")
            return false
        }

        val peakCenter = peakIndex + WINDOW_SAMPLES / 2
        val marginStart =
            (peakCenter - EnrollmentConfig.MARGIN_SAMPLES / 2).coerceIn(0, resampled.size - EnrollmentConfig.MARGIN_SAMPLES)
        repMarginSignals += resampled.copyOfRange(marginStart, marginStart + EnrollmentConfig.MARGIN_SAMPLES)
        Log.d(TAG, "captureRep: ok, rep ${repMarginSignals.size}/${EnrollmentConfig.REPS_REQUIRED}, peakIndex=$peakIndex")
        return true
    }

    /** Runs sanity checks, augments, and averages into a final prototype. Call once all reps are captured. */
    fun finish(): EnrollmentOutcome {
        if (repMarginSignals.size < EnrollmentConfig.REPS_REQUIRED) {
            Log.d(TAG, "finish: only ${repMarginSignals.size}/${EnrollmentConfig.REPS_REQUIRED} reps captured")
            return EnrollmentOutcome.InsufficientData
        }

        val cleanSignals = repMarginSignals.map { centerCrop(it, WINDOW_SAMPLES) }
        val cleanEmbeddings = cleanSignals.map { embeddingExtractor.embed(featureExtractor.extract(it)) }

        similarToExistingGesture(cleanSignals)?.let {
            Log.d(TAG, "finish: rejected, similar to existing gesture $it")
            return EnrollmentOutcome.SimilarToExisting(it)
        }
        if (confusedWithDailyActivity(cleanEmbeddings)) {
            Log.d(TAG, "finish: rejected, confused with daily activity")
            return EnrollmentOutcome.ConfusedWithDailyActivity
        }

        val consistentIndices = consistentRepIndices(cleanEmbeddings)
        val outlierCount = repMarginSignals.size - consistentIndices.size
        Log.d(TAG, "finish: $outlierCount/${repMarginSignals.size} reps flagged as outliers (max allowed ${EnrollmentConfig.MAX_OUTLIER_REPS})")
        if (outlierCount > EnrollmentConfig.MAX_OUTLIER_REPS) {
            return EnrollmentOutcome.TooInconsistent
        }

        val positives = mutableListOf<FloatArray>()
        val userNegatives = mutableListOf<FloatArray>()
        for (index in consistentIndices) {
            positives += cleanEmbeddings[index]
            repeat(EnrollmentConfig.AUGMENTATIONS_PER_REP) {
                val augmentedSignal = centerCrop(augmentPositive(repMarginSignals[index], random), WINDOW_SAMPLES)
                positives += embeddingExtractor.embed(featureExtractor.extract(augmentedSignal))
            }
            // Distorted copies of the user's own gesture, marked negative, so the head
            // learns that only the correctly-ordered, complete motion counts.
            for (variant in negativeVariants(repMarginSignals[index], random)) {
                userNegatives += embeddingExtractor.embed(featureExtractor.extract(centerCrop(variant, WINDOW_SAMPLES)))
            }
        }

        // Everyday motion + the built-in gestures + distortions of the user's own reps.
        // The built-in ones matter: without them the head never learns that a double
        // clench or pinch is not this gesture.
        val negatives = negativeEmbeddingPool.toList() + baseGestureEmbeddingPool.toList() + userNegatives
        Log.d(TAG, "finish: training head on ${positives.size} positives / ${negatives.size} negatives")
        val startMs = System.currentTimeMillis()
        val head = GestureHead.train(positives, negatives, random)
        Log.d(TAG, "finish: success, head trained in ${System.currentTimeMillis() - startMs}ms")
        return EnrollmentOutcome.Success(head)
    }

    private fun similarToExistingGesture(cleanSignals: List<Array<FloatArray>>): GestureClass? {
        val votes = cleanSignals.map { signal ->
            val result = classifier.classify(featureExtractor.extract(signal))
            when {
                result.doubleClench >= DetectionConfig.CONFIDENCE_THRESHOLD -> GestureClass.DOUBLE_CLENCH
                result.doublePinch >= DetectionConfig.CONFIDENCE_THRESHOLD -> GestureClass.DOUBLE_PINCH
                else -> null
            }
        }
        val majority = cleanSignals.size / 2 + 1
        return GestureClass.entries.firstOrNull { gestureClass -> votes.count { it == gestureClass } >= majority }
    }

    private fun confusedWithDailyActivity(embeddings: List<FloatArray>): Boolean {
        if (negativeEmbeddingPool.isEmpty()) return false
        val confusedVotes = embeddings.count { embedding ->
            val nearestSimilarity = negativeEmbeddingPool.maxOf { cosineSimilarity(embedding, it) }
            nearestSimilarity >= EnrollmentConfig.DAILY_ACTIVITY_CONFUSION_THRESHOLD
        }
        return confusedVotes >= embeddings.size / 2 + 1
    }

    private fun consistentRepIndices(embeddings: List<FloatArray>): List<Int> {
        val centroid = averageEmbedding(embeddings)
        return embeddings.indices.filter { cosineSimilarity(embeddings[it], centroid) >= EnrollmentConfig.REP_CONSISTENCY_THRESHOLD }
    }

    private fun centerCrop(signal: Array<FloatArray>, targetLength: Int): Array<FloatArray> {
        val start = ((signal.size - targetLength) / 2).coerceIn(0, maxOf(0, signal.size - targetLength))
        return signal.copyOfRange(start, start + targetLength)
    }

    private fun averageEmbedding(embeddings: List<FloatArray>): FloatArray {
        val size = embeddings[0].size
        val sum = FloatArray(size)
        for (embedding in embeddings) {
            for (i in 0 until size) sum[i] += embedding[i]
        }
        for (i in 0 until size) sum[i] /= embeddings.size
        return sum
    }

    companion object {
        private const val TAG = "GestureDetection"
    }
}
