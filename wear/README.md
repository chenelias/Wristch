# AssistiveTouchPeeker

A single-screen Wear OS app that validates the gesture model trained in
[PixelWatchAssistiveTouch](../PixelWatchAssistiveTouch) on a real watch: it continuously
classifies IMU data and, on a `DoubleClench` or `DoublePinch` detection, vibrates and shows
the detected gesture in a Material 3 `SuccessConfirmationDialog`.

## How the pipeline mirrors training

The training pipeline (`PixelWatchAssistiveTouch/gesture_ml/data.py`) does, per recording:

```
resample -> bandpass_features -> normalize -> 1 s (100-sample) windows
```

The watch approximates this on a live ring buffer instead of a fixed recording:

| Stage | Training (`gesture_ml/data.py`) | On-watch |
|---|---|---|
| Sensing | Pre-recorded CSV, one shared timestamp column | [`sensing/ImuSensorSource.kt`](app/src/main/java/dev/elias/assistivetouchpeeker/sensing/ImuSensorSource.kt): accelerometer + gyroscope at 100 Hz, each with its own timestamps, kept in a ~3 s ring buffer |
| Resample | `np.interp` per channel onto a uniform 100 Hz grid (`resample`) | [`dsp/LinearResampler.kt`](app/src/main/java/dev/elias/assistivetouchpeeker/dsp/LinearResampler.kt): the same `np.interp` semantics (edge-clamped, no extrapolation), per channel, onto a shared grid |
| Features | Raw + 3 zero-phase Butterworth bandpasses (0.22-8, 8-32, 32-48 Hz) via `scipy.signal.sosfiltfilt` (`bandpass_features`) | [`dsp/Butterworth.kt`](app/src/main/java/dev/elias/assistivetouchpeeker/dsp/Butterworth.kt): a Kotlin port of `sosfiltfilt` (odd-extension padding, forward-backward pass) against hardcoded SOS coefficients in [`dsp/ButterworthCoefficients.kt`](app/src/main/java/dev/elias/assistivetouchpeeker/dsp/ButterworthCoefficients.kt) |
| Normalize | Per-feature mean/std from `artifacts/normalizer.json`, broadcast over time and channel | [`dsp/FeatureExtractor.kt`](app/src/main/java/dev/elias/assistivetouchpeeker/dsp/FeatureExtractor.kt) applies the same `normalizer.json`, bundled as an asset |
| Inference | Strict INT8 `.tflite`, verified with the TFLite interpreter | [`ml/GestureClassifier.kt`](app/src/main/java/dev/elias/assistivetouchpeeker/ml/GestureClassifier.kt): quantizes/dequantizes using the scale + zero-point read from the model at runtime |

Training filters each whole ~3 s recording with zero-phase `sosfiltfilt`, which needs the
full signal (it runs forward then backward). The watch approximates this by re-filtering
its full ~3 s ring buffer every inference cycle and classifying only the most recent 1 s
(100-sample) window - the closest streaming equivalent of the training transform.

[`detection/GestureDetectionEngine.kt`](app/src/main/java/dev/elias/assistivetouchpeeker/detection/GestureDetectionEngine.kt)
ties the stages together: every `DetectionConfig.INFERENCE_INTERVAL_MS` it builds a window,
classifies it, and publishes live per-class probabilities. A gesture fires only once a
non-`Null` class stays above `DetectionConfig.CONFIDENCE_THRESHOLD` for
`DetectionConfig.CONSECUTIVE_DETECTIONS_REQUIRED` consecutive inferences, followed by a
`DetectionConfig.COOLDOWN_MS` cooldown before the next one can fire.

## Tuning thresholds

All detection knobs live in one place: `DetectionConfig` at the top of
[`GestureDetectionEngine.kt`](app/src/main/java/dev/elias/assistivetouchpeeker/detection/GestureDetectionEngine.kt).
The defaults are deliberately conservative - the trained model's test-set `DoublePinch`
precision is only ~0.40 - so expect to raise `CONFIDENCE_THRESHOLD` or
`CONSECUTIVE_DETECTIONS_REQUIRED` while validating on-watch. The live confidence readout on
screen is there to make that tuning loop visible without needing logcat.

## Build & install

```bash
./gradlew :app:testDebugUnitTest lint assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No runtime permissions are required (IMU sensors at 100 Hz need none); `VIBRATE` is a
normal manifest permission.

## Regenerating the model assets

`app/src/main/assets/{gesture_model_int8.tflite,gesture_model_embedding_int8.tflite,
negative_embeddings.json,normalizer.json}` are copied from `PixelWatchAssistiveTouch/artifacts/`.
Re-copy them after retraining. The embedding model + negative pool can be re-exported from
an existing checkpoint without a full retrain - see that repo's README.

The Butterworth SOS coefficients in `dsp/ButterworthCoefficients.kt` are generated, not
hand-written:

```bash
conda run -n tensorflow python tools/generate_dsp_constants.py
```

## Parity tests

`app/src/test/java/.../dsp/*ParityTest.kt` compare the Kotlin DSP port against the real
Python pipeline (`gesture_ml.data`) run on a real recording, via golden JSON fixtures under
`app/src/test/resources/`. Regenerate the fixtures after changing the training pipeline's
preprocessing:

```bash
conda run -n tensorflow python tools/generate_golden_fixture.py
```
