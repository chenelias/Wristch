"""Generate Kotlin Butterworth SOS constants matching the training pipeline.

The training pipeline (PixelWatchAssistiveTouch/gesture_ml/data.py) filters with:
    sos = scipy.signal.butter(2, (low / nyq, high / nyq), btype="bandpass", output="sos")
    scipy.signal.sosfiltfilt(sos, signal, axis=0)

This script emits app/src/main/java/dev/elias/assistivetouchpeeker/dsp/ButterworthCoefficients.kt
with the SOS coefficients and the sosfilt_zi steady-state initial conditions the
Kotlin sosfiltfilt port needs.

Run:
    conda run -n tensorflow python tools/generate_dsp_constants.py
"""

from pathlib import Path

import numpy as np
from scipy.signal import butter, sosfilt_zi

SAMPLE_RATE_HZ = 100
FILTER_BANDS_HZ = ((0.22, 8.0), (8.0, 32.0), (32.0, 48.0))
OUTPUT = (
    Path(__file__).resolve().parent.parent
    / "app/src/main/java/dev/elias/assistivetouchpeeker/dsp/ButterworthCoefficients.kt"
)


def fmt_array(values: np.ndarray) -> str:
    return ", ".join(repr(float(value)) for value in values)


def main() -> None:
    nyquist = SAMPLE_RATE_HZ / 2
    bands_kotlin = []
    for low, high in FILTER_BANDS_HZ:
        sos = butter(2, (low / nyquist, high / nyquist), btype="bandpass", output="sos")
        zi = sosfilt_zi(sos)
        sections = ",\n".join(
            f"            doubleArrayOf({fmt_array(section)})" for section in sos
        )
        zi_rows = ",\n".join(
            f"            doubleArrayOf({fmt_array(row)})" for row in zi
        )
        bands_kotlin.append(
            f"    // Bandpass {low}-{high} Hz at {SAMPLE_RATE_HZ} Hz sampling.\n"
            f"    SosFilter(\n"
            f"        sections = arrayOf(\n{sections},\n        ),\n"
            f"        steadyStateGain = arrayOf(\n{zi_rows},\n        ),\n"
            f"    )"
        )
    body = ",\n".join(bands_kotlin)
    OUTPUT.write_text(
        "// GENERATED FILE - do not edit by hand.\n"
        "// Regenerate with: conda run -n tensorflow python tools/generate_dsp_constants.py\n"
        "// Coefficients equal scipy.signal.butter(2, band, btype=\"bandpass\", output=\"sos\")\n"
        "// and scipy.signal.sosfilt_zi(sos), matching the training pipeline exactly.\n"
        "package dev.elias.assistivetouchpeeker.dsp\n\n"
        "/** The three bandpass filters used to build model features (low/mid/high bands). */\n"
        "val FILTER_BANK: List<SosFilter> = listOf(\n"
        f"{body},\n"
        ")\n"
    )
    print(f"Wrote {OUTPUT}")


if __name__ == "__main__":
    main()
