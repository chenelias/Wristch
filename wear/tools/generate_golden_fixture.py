"""Generate golden JSON fixtures for the Kotlin DSP parity tests.

Runs the exact training pipeline (PixelWatchAssistiveTouch/gesture_ml/data.py:
resample -> bandpass_features -> normalize) on a real recording and on a
synthetic irregular timestamp series, so the JVM unit tests can assert the
Kotlin ports match scipy/numpy within a small tolerance.

Run:
    conda run -n tensorflow python tools/generate_golden_fixture.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
TRAINING_REPO = REPO_ROOT.parent / "PixelWatchAssistiveTouch"
sys.path.insert(0, str(TRAINING_REPO))

from gesture_ml.data import (  # noqa: E402
    CHANNELS,
    Normalizer,
    bandpass_features,
    extract_peak_window,
    load_csv_recording,
    resample,
)

OUTPUT_DIR = REPO_ROOT / "app" / "src" / "test" / "resources"
ANDROID_TEST_ASSETS_DIR = REPO_ROOT / "app" / "src" / "androidTest" / "assets"
SAMPLE_CSV = (
    TRAINING_REPO
    / "samples"
    / "LeftHand"
    / "DoublePinch"
    / "2026-08-28T10:55:27.281Z.csv"
)


def _load_normalizer() -> Normalizer:
    normalizer_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "normalizer.json"
    return Normalizer(
        mean=np.array(json.loads(normalizer_path.read_text())["mean"]),
        std=np.array(json.loads(normalizer_path.read_text())["std"]),
    )


def _trimmed_and_normalized_features() -> tuple[np.ndarray, np.ndarray]:
    """(trimmed_signal, normalized_features) for the last 3 s of a real recording,
    matching the on-watch ring buffer - shared by the DSP and peak-window fixtures."""
    recording = load_csv_recording(SAMPLE_CSV, label=1, root=SAMPLE_CSV.parent)
    resampled = resample(recording)
    trimmed_signal = resampled.signal[-300:]
    features = bandpass_features(trimmed_signal)
    normalized = _load_normalizer().apply(features)
    return trimmed_signal, normalized


def generate_dsp_fixture() -> None:
    """resample -> bandpass_features -> normalize on a real recording."""
    trimmed_signal, normalized = _trimmed_and_normalized_features()
    # The watch classifies the most recent 1 s (100-sample) window.
    last_window = normalized[-100:]

    fixture = {
        "description": (
            f"resample -> bandpass_features -> normalize on {SAMPLE_CSV.name}, "
            "last 3 s trimmed to the on-watch buffer, last 1 s window kept for comparison."
        ),
        "inputSignal": trimmed_signal.tolist(),  # [300, 6] raw resampled acc+gyro
        "channels": list(CHANNELS),
        "expectedWindow": last_window.tolist(),  # [100, 6, 4] normalized features
    }
    out_path = OUTPUT_DIR / "dsp_golden.json"
    out_path.write_text(json.dumps(fixture))
    print(f"Wrote {out_path} ({len(trimmed_signal)} input samples)")


def generate_peak_window_fixture() -> None:
    """extract_peak_window on the same real recording's full normalized features -
    for PeakWindowFinderParityTest, which only needs plain Kotlin DSP (no TFLite)."""
    _, normalized = _trimmed_and_normalized_features()
    expected_window = extract_peak_window(normalized)[0]

    fixture = {
        "description": f"extract_peak_window on {SAMPLE_CSV.name}'s full normalized features.",
        "inputFeatures": normalized.tolist(),  # [N, 6, 4] normalized features
        "expectedPeakWindow": expected_window.tolist(),  # [100, 6, 4]
    }
    out_path = OUTPUT_DIR / "peak_window_golden.json"
    out_path.write_text(json.dumps(fixture))
    print(f"Wrote {out_path} ({len(normalized)} input samples)")


def generate_embedding_fixture() -> None:
    """Feeds the same 1 s window from generate_dsp_fixture through the exported
    embedding TFLite model, for the androidTest instrumented parity test (needs a real
    on-device Interpreter - see app/src/androidTest)."""
    import tensorflow as tf

    embedding_model_path = REPO_ROOT.parent / "PixelWatchAssistiveTouch" / "artifacts" / "gesture_model_embedding_int8.tflite"
    _, normalized = _trimmed_and_normalized_features()
    last_window = normalized[-100:].astype(np.float32)

    interpreter = tf.lite.Interpreter(model_path=str(embedding_model_path))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    scale, zero_point = input_detail["quantization"]
    quantized = np.clip(np.round(last_window[np.newaxis, ...] / scale + zero_point), -128, 127).astype(np.int8)
    interpreter.set_tensor(input_detail["index"], quantized)
    interpreter.invoke()
    expected_embedding = interpreter.get_tensor(output_detail["index"])[0]

    fixture = {
        "description": f"gesture_model_embedding_int8.tflite output for {SAMPLE_CSV.name}'s last 1 s window.",
        "normalizedWindow": last_window.tolist(),  # [100, 6, 4]
        "expectedEmbedding": expected_embedding.tolist(),  # [120]
    }
    ANDROID_TEST_ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = ANDROID_TEST_ASSETS_DIR / "embedding_golden.json"
    out_path.write_text(json.dumps(fixture))
    print(f"Wrote {out_path}")


def generate_classification_fixture() -> None:
    """Feeds the same 1 s window through gesture_model_int8.tflite (dequantized to
    probabilities), for the androidTest instrumented parity test - nothing currently
    verifies GestureClassifier's real quantize->invoke->dequantize round-trip against
    Python at all, so this also closes a pre-existing gap, not just for new code."""
    import tensorflow as tf

    model_path = REPO_ROOT.parent / "PixelWatchAssistiveTouch" / "artifacts" / "gesture_model_int8.tflite"
    _, normalized = _trimmed_and_normalized_features()
    last_window = normalized[-100:].astype(np.float32)

    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    in_scale, in_zero_point = input_detail["quantization"]
    quantized = np.clip(np.round(last_window[np.newaxis, ...] / in_scale + in_zero_point), -128, 127).astype(np.int8)
    interpreter.set_tensor(input_detail["index"], quantized)
    interpreter.invoke()
    raw_output = interpreter.get_tensor(output_detail["index"])[0]
    out_scale, out_zero_point = output_detail["quantization"]
    probabilities = (raw_output.astype(np.float32) - out_zero_point) * out_scale

    fixture = {
        "description": f"gesture_model_int8.tflite dequantized output for {SAMPLE_CSV.name}'s last 1 s window.",
        "normalizedWindow": last_window.tolist(),  # [100, 6, 4]
        "expectedProbabilities": probabilities.tolist(),  # [3]: DoubleClench, DoublePinch, Null
    }
    ANDROID_TEST_ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    out_path = ANDROID_TEST_ASSETS_DIR / "classification_golden.json"
    out_path.write_text(json.dumps(fixture))
    print(f"Wrote {out_path}")


def generate_resampler_fixture() -> None:
    """np.interp with an irregular timestamp series, mirroring resample()."""
    rng = np.random.default_rng(42)
    n = 40
    # Irregular but strictly increasing timestamps around a ~93 Hz sensor rate.
    source_timestamps = np.cumsum(rng.uniform(0.008, 0.014, size=n))
    source_values = np.sin(source_timestamps * 17.0) + 0.1 * rng.standard_normal(n)
    target_timestamps = np.arange(0.0, source_timestamps[-1], 0.01)
    expected = np.interp(target_timestamps, source_timestamps, source_values)

    fixture = {
        "sourceTimestamps": source_timestamps.tolist(),
        "sourceValues": source_values.tolist(),
        "targetTimestamps": target_timestamps.tolist(),
        "expectedValues": expected.tolist(),
    }
    out_path = OUTPUT_DIR / "resampler_golden.json"
    out_path.write_text(json.dumps(fixture))
    print(f"Wrote {out_path} ({n} source samples)")


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    generate_dsp_fixture()
    generate_resampler_fixture()
    generate_peak_window_fixture()
    generate_embedding_fixture()
    generate_classification_fixture()


if __name__ == "__main__":
    main()
