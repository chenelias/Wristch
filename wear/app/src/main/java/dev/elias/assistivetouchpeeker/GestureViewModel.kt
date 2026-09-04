package dev.elias.assistivetouchpeeker

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.elias.assistivetouchpeeker.detection.EnrollmentSession
import dev.elias.assistivetouchpeeker.detection.GestureDetectionEngine
import dev.elias.assistivetouchpeeker.detection.GestureHead
import dev.elias.assistivetouchpeeker.dsp.FeatureExtractor
import dev.elias.assistivetouchpeeker.dsp.NormalizerParams
import dev.elias.assistivetouchpeeker.ml.ClassProbabilities
import dev.elias.assistivetouchpeeker.ml.DetectedGesture
import dev.elias.assistivetouchpeeker.ml.EmbeddingExtractor
import dev.elias.assistivetouchpeeker.ml.GestureClassifier
import dev.elias.assistivetouchpeeker.ml.loadEmbeddingPool
import dev.elias.assistivetouchpeeker.sensing.ImuSensorSource
import dev.elias.assistivetouchpeeker.storage.CustomGesture
import dev.elias.assistivetouchpeeker.storage.CustomGestureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Wires [GestureDetectionEngine] to the UI, vibrates the watch on each detection, and
 * owns the few-shot custom-gesture pipeline ([CustomGestureStore], [EnrollmentSession]).
 */
class GestureViewModel(application: Application) : AndroidViewModel(application) {

    private val imuSource = ImuSensorSource(application)
    private val classifier = GestureClassifier(application)
    private val embeddingExtractor: Lazy<EmbeddingExtractor> = lazy { EmbeddingExtractor(application) }
    private val negativeEmbeddingPool: Lazy<Array<FloatArray>> =
        lazy { loadEmbeddingPool(application, "negative_embeddings.json") }
    private val baseGestureEmbeddingPool: Lazy<Array<FloatArray>> =
        lazy { loadEmbeddingPool(application, "base_gesture_embeddings.json") }
    private val customGestureStore = CustomGestureStore(application)
    private val normalizer = loadNormalizer(application)
    private val featureExtractor = FeatureExtractor(normalizer)
    private val vibrator = application.vibrator()

    private val engine = GestureDetectionEngine(
        imuSource = imuSource,
        classifier = classifier,
        customGestureStore = customGestureStore,
        embeddingExtractor = embeddingExtractor,
        normalizer = normalizer,
    )

    val probabilities: StateFlow<ClassProbabilities> = engine.probabilities
    val customGestureSimilarities: StateFlow<Map<String, Float>> = engine.customGestureSimilarities

    private val _detectedGesture = MutableStateFlow<DetectedGesture?>(null)
    val detectedGesture: StateFlow<DetectedGesture?> = _detectedGesture.asStateFlow()

    /** Delegated straight to the store's own reactive state - no manual refresh to forget. */
    val customGestures: StateFlow<List<CustomGesture>> = customGestureStore.gestures

    init {
        imuSource.start()
        engine.start(viewModelScope)
        viewModelScope.launch {
            engine.detections.collect { gesture ->
                _detectedGesture.value = gesture
                vibrator.vibrate(DOUBLE_PULSE)
            }
        }
    }

    /** Called once the confirmation dialog has been shown, to allow the next detection through. */
    fun acknowledgeDetection() {
        _detectedGesture.value = null
    }

    /** A fresh capture session for the enrollment screen; call [pauseDetection] first. */
    fun startEnrollment(): EnrollmentSession = EnrollmentSession(
        imuSource = imuSource,
        featureExtractor = featureExtractor,
        embeddingExtractor = embeddingExtractor.value,
        classifier = classifier,
        negativeEmbeddingPool = negativeEmbeddingPool.value,
        baseGestureEmbeddingPool = baseGestureEmbeddingPool.value,
    )

    fun saveCustomGesture(name: String, head: GestureHead) {
        customGestureStore.enroll(name, head)
    }

    fun deleteCustomGesture(id: String) {
        customGestureStore.delete(id)
    }

    fun renameCustomGesture(id: String, name: String) {
        customGestureStore.rename(id, name)
    }

    /** Two short pulses: "start performing the gesture now." */
    fun vibrateRecordingStart() {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
    }

    /** One long pulse: "recording finished, stop." */
    fun vibrateRecordingStop() {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350), -1))
    }

    fun nextAutoGestureName(): String = customGestureStore.nextAutoName()

    /** Stops the continuous detector (and the vibration/dialog it triggers) while enrollment is active. */
    fun pauseDetection() = engine.pause()

    fun resumeDetection() = engine.resume()

    override fun onCleared() {
        imuSource.stop()
        classifier.close()
        if (embeddingExtractor.isInitialized()) embeddingExtractor.value.close()
    }

    companion object {
        private const val NORMALIZER_ASSET = "normalizer.json"
        private val DOUBLE_PULSE: VibrationEffect = VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80), -1)

        private fun loadNormalizer(application: Application): NormalizerParams =
            NormalizerParams.parse(application.assets.open(NORMALIZER_ASSET).bufferedReader().readText())

        private fun Context.vibrator(): Vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
    }
}
