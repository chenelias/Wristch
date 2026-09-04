package dev.elias.assistivetouchpeeker.dsp

import java.util.Random

/**
 * Kotlin port of PixelWatchAssistiveTouch/gesture_ml/augment.py's positive augmentations
 * (zoom/scale/time-warp) - the same ones used to train the base model - reused here to
 * squeeze a few more embedding samples out of each few-shot enrollment repetition.
 * Operates on resampled `[time][6]` signals at 100 Hz. Not parity-tested against Python
 * bit-for-bit: the two sides use different RNG algorithms, so only shapes/finiteness are
 * meaningfully comparable (see PixelWatchAssistiveTouch's own `test_augmentations_produce_valid_variants`).
 */

private const val SHUFFLE_PIECE_SAMPLES = 10 // 0.1 s at 100 Hz
private const val CUTOUT_SAMPLES = 50 // 0.5 s at 100 Hz

/** Simulates different gesture speed: uniform time re-scaling, factor ~ U(0.85, 1.15). */
fun zoomSignal(signal: Array<FloatArray>, random: Random): Array<FloatArray> {
    val factor = 0.85 + random.nextDouble() * 0.30
    val length = maxOf(Math.round(signal.size * factor).toInt(), 2)
    val positions = linspace(0.0, (signal.size - 1).toDouble(), length)
    return interpChannels(signal, positions)
}

/** Simulates different gesture strength: s ~ N(1, 0.2^2), clipped to [0, 2]. */
fun scaleSignal(signal: Array<FloatArray>, random: Random): Array<FloatArray> {
    val factor = (1.0 + random.nextGaussian() * 0.2).coerceIn(0.0, 2.0)
    return Array(signal.size) { t -> FloatArray(signal[t].size) { c -> (signal[t][c] * factor).toFloat() } }
}

/** Simulates temporal variance via two interior interpolation knots perturbed by N(0, 0.05*length). */
fun timeWarpSignal(signal: Array<FloatArray>, random: Random): Array<FloatArray> {
    val length = signal.size
    val knots = linspace(0.0, (length - 1).toDouble(), 4)
    val warpedKnots = knots.copyOf()
    warpedKnots[1] += random.nextGaussian() * 0.05 * length
    warpedKnots[2] += random.nextGaussian() * 0.05 * length
    warpedKnots.sort()
    for (i in warpedKnots.indices) {
        warpedKnots[i] = warpedKnots[i].coerceIn(0.0, (length - 1).toDouble())
    }
    val targetIndices = DoubleArray(length) { it.toDouble() }
    val warpedKnotsFloat = FloatArray(warpedKnots.size) { warpedKnots[it].toFloat() }
    val positions = linearResample(knots, warpedKnotsFloat, targetIndices).map { it.toDouble() }.toDoubleArray()
    return interpChannels(signal, positions)
}

/**
 * Distorted copies of a real gesture that must NOT count as that gesture - reverse,
 * shuffle, cutout - ported from `augment.py::negative_variants`. Used to give the
 * per-gesture head user-specific negatives, so it learns that only a correctly-ordered,
 * complete gesture is a positive (the paper's Section 4.2.2 negative augmentation).
 */
fun negativeVariants(signal: Array<FloatArray>, random: Random): List<Array<FloatArray>> =
    listOf(reverseSignal(signal), shuffleSignal(signal, random), cutoutSignal(signal, random))

private fun reverseSignal(signal: Array<FloatArray>): Array<FloatArray> =
    Array(signal.size) { signal[signal.size - 1 - it].copyOf() }

private fun shuffleSignal(signal: Array<FloatArray>, random: Random): Array<FloatArray> {
    val pieceCount = maxOf(signal.size / SHUFFLE_PIECE_SAMPLES, 2)
    val pieces = signal.toList().chunked((signal.size + pieceCount - 1) / pieceCount)
    val order = pieces.indices.shuffled(kotlin.random.Random(random.nextLong()))
    return order.flatMap { pieces[it] }.map { it.copyOf() }.toTypedArray()
}

private fun cutoutSignal(signal: Array<FloatArray>, random: Random): Array<FloatArray> {
    val result = Array(signal.size) { signal[it].copyOf() }
    if (result.size > CUTOUT_SAMPLES) {
        val start = random.nextInt(result.size - CUTOUT_SAMPLES)
        for (t in start until start + CUTOUT_SAMPLES) result[t].fill(0f)
    }
    return result
}

/** Applies a random non-empty combination of [zoomSignal], [scaleSignal], [timeWarpSignal]. */
fun augmentPositive(signal: Array<FloatArray>, random: Random): Array<FloatArray> {
    val operations = listOf(::zoomSignal, ::scaleSignal, ::timeWarpSignal)
    val chosen = operations.filter { random.nextDouble() < 0.5 }.ifEmpty { listOf(operations[random.nextInt(operations.size)]) }
    var augmented = signal
    for (operation in chosen) {
        augmented = operation(augmented, random)
    }
    return augmented
}

private fun linspace(start: Double, stop: Double, count: Int): DoubleArray {
    if (count == 1) return doubleArrayOf(start)
    return DoubleArray(count) { i -> start + i * (stop - start) / (count - 1) }
}

private fun interpChannels(signal: Array<FloatArray>, positions: DoubleArray): Array<FloatArray> {
    val sourceIndices = DoubleArray(signal.size) { it.toDouble() }
    val channelCount = signal[0].size
    val result = Array(positions.size) { FloatArray(channelCount) }
    for (c in 0 until channelCount) {
        val channelValues = FloatArray(signal.size) { signal[it][c] }
        val interpolated = linearResample(sourceIndices, channelValues, positions)
        for (i in positions.indices) {
            result[i][c] = interpolated[i]
        }
    }
    return result
}
