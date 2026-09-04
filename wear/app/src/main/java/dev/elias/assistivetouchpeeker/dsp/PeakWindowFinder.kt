package dev.elias.assistivetouchpeeker.dsp

import kotlin.math.sqrt

/** Index of the mid-band (8-32 Hz) feature within [FeatureExtractor]'s 4-feature stack. */
private const val MID_BAND_FEATURE_INDEX = 2
private const val ENERGY_SMOOTHING_KERNEL_SIZE = 11

/**
 * Kotlin port of PixelWatchAssistiveTouch/gesture_ml/data.py:extract_peak_window - finds
 * the start index of the [WINDOW_SAMPLES]-sample window centered on the strongest
 * mid-band motion peak, the same way training builds peak-centered windows for gesture
 * recordings. The model's input is a fixed 100-sample (1 s) window, so this searches a
 * longer capture period for where the gesture actually happened rather than always
 * taking the most recent second.
 *
 * Returns null if [features] is shorter than [WINDOW_SAMPLES]. The index applies equally
 * to the raw resampled `[time][6]` signal the features were extracted from (same time
 * axis), so callers needing both the raw and feature-extracted peak window - such as
 * enrollment, which augments the raw signal - can slice both with it.
 */
fun findPeakIndex(features: Array<Array<FloatArray>>): Int? {
    val sampleCount = features.size
    if (sampleCount < WINDOW_SAMPLES) return null

    val channelCount = features[0].size
    val motionEnergy = DoubleArray(sampleCount) { t ->
        var sumSquares = 0.0
        for (c in 0 until channelCount) {
            val value = features[t][c][MID_BAND_FEATURE_INDEX].toDouble()
            sumSquares += value * value
        }
        sqrt(sumSquares)
    }
    val smoothedEnergy = zeroPaddedBoxcarSmooth(motionEnergy, ENERGY_SMOOTHING_KERNEL_SIZE)

    val halfWindow = WINDOW_SAMPLES / 2
    var peakIndex = halfWindow
    var peakValue = smoothedEnergy[halfWindow]
    for (i in halfWindow until sampleCount - halfWindow) {
        if (smoothedEnergy[i] > peakValue) {
            peakValue = smoothedEnergy[i]
            peakIndex = i
        }
    }

    return (peakIndex - halfWindow).coerceIn(0, sampleCount - WINDOW_SAMPLES)
}

/** [findPeakIndex] followed by slicing [features] at that index. */
fun findPeakWindow(features: Array<Array<FloatArray>>): Array<Array<FloatArray>>? {
    val start = findPeakIndex(features) ?: return null
    return features.copyOfRange(start, start + WINDOW_SAMPLES)
}

/**
 * Mirrors `np.convolve(signal, ones(kernelSize)/kernelSize, mode="same")`: a moving
 * average that always divides by the full kernel size, even near the edges where fewer
 * than [kernelSize] samples are actually summed (equivalent to implicit zero-padding).
 */
private fun zeroPaddedBoxcarSmooth(signal: DoubleArray, kernelSize: Int): DoubleArray {
    val half = kernelSize / 2
    val n = signal.size
    return DoubleArray(n) { i ->
        var sum = 0.0
        val lo = maxOf(0, i - half)
        val hi = minOf(n - 1, i + half)
        for (k in lo..hi) sum += signal[k]
        sum / kernelSize
    }
}
