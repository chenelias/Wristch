package dev.elias.assistivetouchpeeker.detection

import android.util.Log
import dev.elias.assistivetouchpeeker.dsp.FeatureExtractor
import dev.elias.assistivetouchpeeker.dsp.NormalizerParams
import dev.elias.assistivetouchpeeker.dsp.WINDOW_SAMPLES
import dev.elias.assistivetouchpeeker.dsp.buildFeatureWindow
import dev.elias.assistivetouchpeeker.ml.ClassProbabilities
import dev.elias.assistivetouchpeeker.ml.DetectedGesture
import dev.elias.assistivetouchpeeker.ml.EmbeddingExtractor
import dev.elias.assistivetouchpeeker.ml.GestureClass
import dev.elias.assistivetouchpeeker.ml.GestureClassifier
import dev.elias.assistivetouchpeeker.sensing.ImuSensorSource
import dev.elias.assistivetouchpeeker.storage.CustomGesture
import dev.elias.assistivetouchpeeker.storage.CustomGestureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tunable detection thresholds - adjust here while validating on-watch. */
object DetectionConfig {
    /** How often a new 1 s window is classified. */
    const val INFERENCE_INTERVAL_MS = 250L

    /** Minimum class confidence to count as a base-class candidate detection. */
    const val CONFIDENCE_THRESHOLD = 0.85f

    /**
     * Consecutive candidate inferences (same class, above threshold) required to fire.
     * On-watch validation showed a real gesture's confidence peaks for exactly one
     * [INFERENCE_INTERVAL_MS] cycle (training centers windows tightly on the motion
     * peak), so requiring more than 1 here means the debounce eats every detection.
     */
    const val CONSECUTIVE_DETECTIONS_REQUIRED = 1

    /** Minimum time between fired detections, so one gesture cannot re-trigger itself. */
    const val COOLDOWN_MS = 2_500L

    /** Minimum trained-head probability to count as a custom-gesture match. */
    const val CUSTOM_GESTURE_SCORE_THRESHOLD = 0.90f

    /** Minimum score margin over the runner-up gesture, once more than one is enrolled. */
    const val CUSTOM_GESTURE_MARGIN = 0.10f
}

/**
 * Runs inference on a timer over the buffered IMU window, applying [DetectionConfig]'s
 * debounce and cooldown before emitting a one-shot [detections] event. Mirrors the
 * training pipeline: resample -> bandpass_features -> normalize -> classify, but on a
 * live ring buffer instead of a fixed recording (PixelWatchAssistiveTouch/gesture_ml/data.py).
 *
 * Also checks the same window against any enrolled custom gestures via
 * [CustomGestureMatcher], but only when the base classifier is confidently Null and at
 * least one gesture is enrolled - [embeddingExtractor] is a lazy supplier (shared with
 * enrollment, see [GestureDetectionEngine]'s caller) so users with no custom gestures
 * enrolled never trigger the second model's load/inference cost.
 */
class GestureDetectionEngine(
    private val imuSource: ImuSensorSource,
    private val classifier: GestureClassifier,
    private val customGestureStore: CustomGestureStore,
    private val embeddingExtractor: Lazy<EmbeddingExtractor>,
    normalizer: NormalizerParams,
) {
    private val featureExtractor = FeatureExtractor(normalizer)

    private val _probabilities = MutableStateFlow(ClassProbabilities(doubleClench = 0f, doublePinch = 0f, nullClass = 1f))
    val probabilities: StateFlow<ClassProbabilities> = _probabilities

    /** Live cosine similarity of each enrolled custom gesture (by id) to the current window - for on-screen % readouts. */
    private val _customGestureSimilarities = MutableStateFlow<Map<String, Float>>(emptyMap())
    val customGestureSimilarities: StateFlow<Map<String, Float>> = _customGestureSimilarities

    private val _detections = Channel<DetectedGesture>(Channel.BUFFERED)
    val detections: Flow<DetectedGesture> = _detections.receiveAsFlow()

    private var consecutiveCount = 0
    private var lastCandidate: DetectedGesture? = null
    private var cooldownUntilMs = 0L
    private var paused = false

    /** Launches the periodic classification loop on [scope]; runs until the scope is cancelled. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                delay(DetectionConfig.INFERENCE_INTERVAL_MS)
                if (!paused) {
                    withContext(Dispatchers.Default) { runInferenceCycle() }
                }
            }
        }
    }

    /** Stops firing detections (and the vibration/dialog they trigger) while e.g. enrollment is active. */
    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    private fun runInferenceCycle() {
        val snapshot = imuSource.snapshot() ?: return
        val features = buildFeatureWindow(snapshot, featureExtractor, minSamples = WINDOW_SAMPLES) ?: return
        val window = features.copyOfRange(features.size - WINDOW_SAMPLES, features.size)

        val result = classifier.classify(window)
        _probabilities.value = result

        // Computed once per cycle (whenever gestures are enrolled) and reused for both the
        // live %-readout StateFlow and candidate resolution below, rather than embedding twice.
        val enrolledGestures = customGestureStore.gestures.value
        val embedding = if (enrolledGestures.isNotEmpty()) embeddingExtractor.value.embed(window) else null
        if (embedding != null) {
            _customGestureSimilarities.value = CustomGestureMatcher.scoreAll(embedding, enrolledGestures)
        }

        val candidate = resolveCandidate(result, embedding, enrolledGestures)
        evaluateDetection(candidate, result)
    }

    private fun resolveCandidate(
        result: ClassProbabilities,
        embedding: FloatArray?,
        enrolledGestures: List<CustomGesture>,
    ): DetectedGesture? {
        // A built-in gesture always wins outright; that ordering is the only guard needed
        // against a custom gesture stealing a real clench/pinch. An earlier version also
        // required the base model's Null confidence to be high before considering a custom
        // match, which was self-defeating: performing a custom gesture IS motion, so Null
        // confidence drops and the gate blocked the very detection it should have allowed
        // (the live % readout bypassed the gate, hence "100% but nothing fires"). Keeping
        // custom gestures distinct from the built-ins is instead the trained head's job -
        // it gets clench/pinch embeddings as explicit negatives.
        val baseCandidate = strongestBaseCandidate(result)
        if (baseCandidate != null) return DetectedGesture.Base(baseCandidate)

        if (embedding == null || enrolledGestures.isEmpty()) return null

        val match = CustomGestureMatcher.bestMatch(embedding, enrolledGestures) ?: return null
        return DetectedGesture.Custom(match.id, match.name)
    }

    private fun evaluateDetection(candidate: DetectedGesture?, result: ClassProbabilities) {
        val now = System.currentTimeMillis()
        if (now < cooldownUntilMs) {
            Log.d(TAG, "cooldown, ${cooldownUntilMs - now}ms left")
            return
        }

        if (candidate == null || candidate != lastCandidate) {
            lastCandidate = candidate
            consecutiveCount = if (candidate != null) 1 else 0
        } else {
            consecutiveCount++
        }

        Log.d(
            TAG,
            "clench=%.3f pinch=%.3f null=%.3f candidate=%s consecutive=%d/%d".format(
                result.doubleClench,
                result.doublePinch,
                result.nullClass,
                candidate,
                consecutiveCount,
                DetectionConfig.CONSECUTIVE_DETECTIONS_REQUIRED,
            ),
        )

        if (candidate != null && consecutiveCount >= DetectionConfig.CONSECUTIVE_DETECTIONS_REQUIRED) {
            lastCandidate = null
            consecutiveCount = 0
            cooldownUntilMs = now + DetectionConfig.COOLDOWN_MS
            Log.d(TAG, "FIRED $candidate")
            _detections.trySend(candidate)
        }
    }

    private fun strongestBaseCandidate(result: ClassProbabilities): GestureClass? = when {
        result.doubleClench < DetectionConfig.CONFIDENCE_THRESHOLD &&
            result.doublePinch < DetectionConfig.CONFIDENCE_THRESHOLD -> null
        result.doubleClench >= result.doublePinch -> GestureClass.DOUBLE_CLENCH
        else -> GestureClass.DOUBLE_PINCH
    }

    companion object {
        private const val TAG = "GestureDetection"
    }
}
