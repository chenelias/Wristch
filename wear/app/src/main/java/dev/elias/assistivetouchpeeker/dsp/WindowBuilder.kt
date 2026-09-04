package dev.elias.assistivetouchpeeker.dsp

import dev.elias.assistivetouchpeeker.sensing.ImuSample
import dev.elias.assistivetouchpeeker.sensing.ImuSnapshot

/** Channel order the model expects: acc x/y/z, gyro x/y/z. */
const val CHANNEL_COUNT = 6

/** 100 Hz sample rate the whole pipeline (resampling, filtering, windowing) assumes. */
const val SAMPLE_RATE_HZ = 100

/** The model's fixed 1 s input window (PixelWatchAssistiveTouch/gesture_ml/data.py:WINDOW_SAMPLES). */
const val WINDOW_SAMPLES = 100

/**
 * Resamples an [ImuSnapshot]'s accel+gyro buffers onto a shared 100 Hz grid - the raw
 * `[time][6]` signal, before any filtering/normalization. Returns null if the snapshot
 * doesn't cover at least [minSamples]. Exposed separately from [buildFeatureWindow] so
 * enrollment can augment (see [augmentPositive]) this raw signal before extracting
 * features from each variant.
 */
fun resampleSnapshot(snapshot: ImuSnapshot, minSamples: Int): Array<FloatArray>? {
    val accel = snapshot.accelerometer
    val gyro = snapshot.gyroscope

    val start = maxOf(accel.first().timestampSeconds, gyro.first().timestampSeconds)
    val end = minOf(accel.last().timestampSeconds, gyro.last().timestampSeconds)
    val sampleCount = ((end - start) * SAMPLE_RATE_HZ).toInt() + 1
    if (sampleCount < minSamples) return null

    val targetTimestamps = DoubleArray(sampleCount) { i -> start + i / SAMPLE_RATE_HZ.toDouble() }
    val resampled = Array(sampleCount) { FloatArray(CHANNEL_COUNT) }
    fillChannel(resampled, 0, accel, targetTimestamps) { it.x }
    fillChannel(resampled, 1, accel, targetTimestamps) { it.y }
    fillChannel(resampled, 2, accel, targetTimestamps) { it.z }
    fillChannel(resampled, 3, gyro, targetTimestamps) { it.x }
    fillChannel(resampled, 4, gyro, targetTimestamps) { it.y }
    fillChannel(resampled, 5, gyro, targetTimestamps) { it.z }
    return resampled
}

/**
 * [resampleSnapshot] followed by [FeatureExtractor.extract] over the full span - shared
 * by [dev.elias.assistivetouchpeeker.detection.GestureDetectionEngine] (which classifies
 * only the most recent 100 samples) and enrollment (which searches the whole span for a
 * gesture's peak via [findPeakWindow]).
 */
fun buildFeatureWindow(
    snapshot: ImuSnapshot,
    featureExtractor: FeatureExtractor,
    minSamples: Int,
): Array<Array<FloatArray>>? {
    val resampled = resampleSnapshot(snapshot, minSamples) ?: return null
    return featureExtractor.extract(resampled)
}

private inline fun fillChannel(
    destination: Array<FloatArray>,
    channelIndex: Int,
    samples: List<ImuSample>,
    targetTimestamps: DoubleArray,
    axis: (ImuSample) -> Float,
) {
    val sourceTimestamps = DoubleArray(samples.size) { samples[it].timestampSeconds }
    val sourceValues = FloatArray(samples.size) { axis(samples[it]) }
    val resampledChannel = linearResample(sourceTimestamps, sourceValues, targetTimestamps)
    for (t in resampledChannel.indices) {
        destination[t][channelIndex] = resampledChannel[t]
    }
}
