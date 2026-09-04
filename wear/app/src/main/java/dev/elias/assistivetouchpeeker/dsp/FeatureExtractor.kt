package dev.elias.assistivetouchpeeker.dsp

import org.json.JSONObject

/** Per-feature mean/std fit during training, broadcast over time and channel. */
data class NormalizerParams(val mean: FloatArray, val std: FloatArray) {

    fun normalize(value: Float, featureIndex: Int): Float =
        (value - mean[featureIndex]) / std[featureIndex]

    companion object {
        /** Parses `artifacts/normalizer.json`'s `{"mean": [[[..4]]], "std": [[[..4]]]}` shape. */
        fun parse(json: String): NormalizerParams {
            val root = JSONObject(json)
            return NormalizerParams(mean = readNestedFeatureArray(root, "mean"), std = readNestedFeatureArray(root, "std"))
        }

        private fun readNestedFeatureArray(root: JSONObject, key: String): FloatArray {
            val featureArray = root.getJSONArray(key).getJSONArray(0).getJSONArray(0)
            return FloatArray(featureArray.length()) { i -> featureArray.getDouble(i).toFloat() }
        }
    }
}

/**
 * Builds the model's per-timestep feature stack - mirrors
 * PixelWatchAssistiveTouch/gesture_ml/data.py:bandpass_features + Normalizer.apply:
 * raw signal plus three zero-phase Butterworth bandpasses, normalized per feature.
 */
class FeatureExtractor(private val normalizer: NormalizerParams) {

    /**
     * [resampledSignal] is `[time][channel]` (6 channels: acc x/y/z, gyro x/y/z) on the
     * uniform 100 Hz grid. Returns normalized `[time][channel][feature]` (4 features:
     * raw + low/mid/high band), one row per input timestep.
     */
    fun extract(resampledSignal: Array<FloatArray>): Array<Array<FloatArray>> {
        val timeSteps = resampledSignal.size
        val channelCount = if (timeSteps > 0) resampledSignal[0].size else 0
        val features = Array(timeSteps) { Array(channelCount) { FloatArray(FEATURES_PER_CHANNEL) } }

        for (channel in 0 until channelCount) {
            val channelSignal = DoubleArray(timeSteps) { t -> resampledSignal[t][channel].toDouble() }
            for (t in 0 until timeSteps) {
                features[t][channel][0] = normalizer.normalize(channelSignal[t].toFloat(), 0)
            }
            FILTER_BANK.forEachIndexed { bandIndex, filter ->
                val featureIndex = bandIndex + 1
                val filtered = sosFiltFilt(filter, channelSignal)
                for (t in 0 until timeSteps) {
                    features[t][channel][featureIndex] = normalizer.normalize(filtered[t].toFloat(), featureIndex)
                }
            }
        }
        return features
    }

    companion object {
        const val FEATURES_PER_CHANNEL = 4
    }
}
