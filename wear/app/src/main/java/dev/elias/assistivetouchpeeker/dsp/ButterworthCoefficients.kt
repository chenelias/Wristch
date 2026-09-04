// GENERATED FILE - do not edit by hand.
// Regenerate with: conda run -n tensorflow python tools/generate_dsp_constants.py
// Coefficients equal scipy.signal.butter(2, band, btype="bandpass", output="sos")
// and scipy.signal.sosfilt_zi(sos), matching the training pipeline exactly.
package dev.elias.assistivetouchpeeker.dsp

/** The three bandpass filters used to build model features (low/mid/high bands). */
val FILTER_BANK: List<SosFilter> = listOf(
    // Bandpass 0.22-8.0 Hz at 100 Hz sampling.
    SosFilter(
        sections = arrayOf(
            doubleArrayOf(0.0439614283779296, 0.0879228567558592, 0.0439614283779296, 1.0, -1.3333119521379413, 0.5113090497792891),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.9805010094685416, 0.9807003702705214),
        ),
        steadyStateGain = arrayOf(
            doubleArrayOf(0.9439519468504444, -0.4611676207743406),
            doubleArrayOf(-0.987913375228374, 0.987913375228374),
        ),
    ),
    // Bandpass 8.0-32.0 Hz at 100 Hz sampling.
    SosFilter(
        sections = arrayOf(
            doubleArrayOf(0.2747268510356349, 0.5494537020712698, 0.2747268510356349, 1.0, 0.4311199780429398, 0.31544709290223166),
            doubleArrayOf(1.0, -2.0, 1.0, 1.0, -1.3101495883305037, 0.546941957651807),
        ),
        steadyStateGain = arrayOf(
            doubleArrayOf(0.35445448555503795, 0.0762534274997666),
            doubleArrayOf(-0.6291813365906729, 0.6291813365906729),
        ),
    ),
    // Bandpass 32.0-48.0 Hz at 100 Hz sampling.
    SosFilter(
        sections = arrayOf(
            doubleArrayOf(0.14532388387704231, -0.29064776775408463, 0.14532388387704231, 1.0, 0.6388234092246099, 0.29886183725646087),
            doubleArrayOf(1.0, 2.0, 1.0, 1.0, 1.8271031620505782, 0.8442852008091614),
        ),
        steadyStateGain = arrayOf(
            doubleArrayOf(-0.14532388387704231, 0.14532388387704231),
            doubleArrayOf(0.0, 0.0),
        ),
    ),
)
