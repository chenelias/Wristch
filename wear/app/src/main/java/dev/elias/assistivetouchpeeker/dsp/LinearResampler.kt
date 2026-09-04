package dev.elias.assistivetouchpeeker.dsp

/**
 * Linear interpolation matching `numpy.interp`: values outside
 * `sourceTimestamps`' range clamp to the nearest edge sample rather than
 * extrapolating. Mirrors PixelWatchAssistiveTouch/gesture_ml/data.py:resample,
 * which calls `np.interp` once per channel against a shared target grid.
 *
 * [sourceTimestamps] must be sorted ascending and the same length as [sourceValues].
 */
fun linearResample(
    sourceTimestamps: DoubleArray,
    sourceValues: FloatArray,
    targetTimestamps: DoubleArray,
): FloatArray {
    require(sourceTimestamps.size == sourceValues.size) {
        "Timestamps (${sourceTimestamps.size}) and values (${sourceValues.size}) must match."
    }
    require(sourceTimestamps.isNotEmpty()) { "Cannot interpolate an empty source series." }

    val firstTimestamp = sourceTimestamps[0]
    val lastTimestamp = sourceTimestamps[sourceTimestamps.size - 1]
    val firstValue = sourceValues[0]
    val lastValue = sourceValues[sourceValues.size - 1]

    var searchStart = 0
    return FloatArray(targetTimestamps.size) { i ->
        val t = targetTimestamps[i]
        when {
            t <= firstTimestamp -> firstValue
            t >= lastTimestamp -> lastValue
            else -> {
                searchStart = advanceToBracket(sourceTimestamps, searchStart, t)
                val t0 = sourceTimestamps[searchStart]
                val t1 = sourceTimestamps[searchStart + 1]
                val v0 = sourceValues[searchStart]
                val v1 = sourceValues[searchStart + 1]
                val fraction = ((t - t0) / (t1 - t0)).toFloat()
                v0 + fraction * (v1 - v0)
            }
        }
    }
}

/** Advances [start] forward until `timestamps[index] <= t < timestamps[index + 1]`. */
private fun advanceToBracket(timestamps: DoubleArray, start: Int, t: Double): Int {
    var index = start
    while (index + 1 < timestamps.size - 1 && timestamps[index + 1] <= t) {
        index++
    }
    return index
}
