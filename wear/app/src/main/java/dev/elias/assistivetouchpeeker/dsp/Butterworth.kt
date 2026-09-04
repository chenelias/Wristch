package dev.elias.assistivetouchpeeker.dsp

/**
 * A cascade of second-order sections (SOS) for a Butterworth filter, plus the
 * `scipy.signal.sosfilt_zi` steady-state initial conditions per section.
 *
 * Each row of [sections] is `[b0, b1, b2, a0, a1, a2]` (a0 is always 1, kept for
 * parity with scipy's SOS layout). Each row of [steadyStateGain] is the 2-value
 * initial filter state for that section, scaled at use time by the input's first
 * sample - see [sosFiltFilt].
 */
data class SosFilter(
    val sections: Array<DoubleArray>,
    val steadyStateGain: Array<DoubleArray>,
)

/**
 * Zero-phase forward-backward filtering equivalent to `scipy.signal.sosfiltfilt`
 * with its defaults (`padtype="odd"`, `padlen=None`), which the training pipeline
 * (PixelWatchAssistiveTouch/gesture_ml/data.py:bandpass_features) relies on.
 */
fun sosFiltFilt(filter: SosFilter, signal: DoubleArray): DoubleArray {
    val padLength = 3 * (2 * filter.sections.size + 1)
    val extended = oddExtend(signal, padLength)
    val forward = sosFiltForward(filter, extended, extended[0])
    val backward = sosFiltForward(filter, forward.reversedArray(), forward.last())
    val restored = backward.reversedArray()
    return restored.copyOfRange(padLength, restored.size - padLength)
}

/** Mirrors `scipy.signal._arraytools.odd_ext`: reflect-and-invert padding at both edges. */
private fun oddExtend(signal: DoubleArray, padLength: Int): DoubleArray {
    require(signal.size > padLength) {
        "Signal too short (${signal.size}) for padding length $padLength."
    }
    val left = DoubleArray(padLength) { i -> 2 * signal[0] - signal[padLength - i] }
    val right = DoubleArray(padLength) { i -> 2 * signal[signal.size - 1] - signal[signal.size - 2 - i] }
    return left + signal + right
}

/**
 * One pass of `scipy.signal.sosfilt`: cascades [filter]'s sections in Direct Form II
 * Transposed, each initialized from its steady-state gain scaled by [initialSample]
 * (the same convention `sosfiltfilt` uses so the cascade starts already settled).
 */
private fun sosFiltForward(filter: SosFilter, signal: DoubleArray, initialSample: Double): DoubleArray {
    var stage = signal
    for (sectionIndex in filter.sections.indices) {
        val section = filter.sections[sectionIndex]
        val zi = filter.steadyStateGain[sectionIndex]
        val b0 = section[0]
        val b1 = section[1]
        val b2 = section[2]
        val a1 = section[4]
        val a2 = section[5]
        var state1 = zi[0] * initialSample
        var state2 = zi[1] * initialSample
        val output = DoubleArray(stage.size)
        for (n in stage.indices) {
            val x = stage[n]
            val y = b0 * x + state1
            state1 = b1 * x - a1 * y + state2
            state2 = b2 * x - a2 * y
            output[n] = y
        }
        stage = output
    }
    return stage
}
