start building a wear os app to vilidate the gesture, follow the latest material UI/UX guide line and make the codebase simple easy to read and handover, the app include a single screen when the user perform a gesture vibrate teh watch and show the detected gesture using something like SuccessConfirmationDialog

AssistiveTouchPeeker — Wear OS gesture validation app
Problem
Validate the trained gesture model from PixelWatchAssistiveTouch on a real watch: a single-screen Wear OS app that continuously classifies IMU data and, when a DoubleClench or DoublePinch is detected, vibrates and shows the detected gesture in a Material 3 SuccessConfirmationDialog.
Current state (model contract)
Model: ../PixelWatchAssistiveTouch/artifacts/gesture_model_int8.tflite — strict INT8 in/out, input [1, 100, 6, 4], output [1, 3] (DoubleClench, DoublePinch, Null). Quantization params are read from the interpreter at runtime.
Preprocessing to replicate on-watch (from gesture_ml/data.py):
6 channels: acc x/y/z (m/s²) + gyro x/y/z (rad/s), linearly interpolated onto a uniform 100 Hz grid.
Per channel, 4 features: raw + three zero-phase (forward-backward, sosfiltfilt) order-2 Butterworth bandpasses: 0.22–8 Hz, 8–32 Hz, 32–48 Hz.
Standardize with per-feature mean/std from artifacts/normalizer.json (broadcast over time and channel).
1-second (100-sample) windows.
Proposed changes
New standalone Gradle project at /Users/elias/projects/AssistiveTouchPeeker (Kotlin DSL, version catalog, single :app Wear OS module). Kotlin 2.x, Compose with the org.jetbrains.kotlin.plugin.compose plugin, latest stable Wear Compose Material 3 (androidx.wear.compose:compose-material3 + compose-foundation; exact latest stable verified from Maven metadata during setup), LiteRT/TensorFlow Lite runtime. minSdk 30, standalone watch app. Model + normalizer.json copied into app/src/main/assets/.
Code layout (package dev.elias.assistivetouchpeeker)
Small, single-purpose files for easy handover:
sensing/ImuSensorSource.kt — SensorManager accelerometer + gyroscope listeners at 100 Hz (10 000 µs), appending timestamped samples into per-sensor ring buffers (~3 s).
dsp/ — plain-Kotlin, dependency-free DSP mirroring training:
LinearResampler.kt — np.interp-style interpolation of both sensors onto a shared 100 Hz grid.
Butterworth.kt — hardcoded SOS coefficients (precomputed with scipy for the 3 bands at 100 Hz, documented with the generating command) + sosfiltfilt equivalent (odd-extension padding, forward-backward pass).
FeatureExtractor.kt — builds the normalized [100, 6, 4] window using mean/std parsed from normalizer.json.
ml/GestureClassifier.kt — TFLite interpreter wrapper: quantize input int8, invoke, dequantize output to 3 probabilities.
detection/GestureDetectionEngine.kt — runs inference every ~250 ms over the latest 1 s window; a gesture fires when a non-Null class stays above a confidence threshold for 2 consecutive inferences, followed by a ~2.5 s cooldown. Thresholds live in one DetectionConfig object for easy tuning. Exposes a StateFlow of live probabilities + one-shot detection events.
GestureViewModel.kt — wires engine to UI, triggers a double-pulse VibrationEffect on detection.
MainActivity.kt + ui/GestureValidationScreen.kt — Material 3 Expressive UI: AppScaffold/ScreenScaffold + TimeText, a prompt ("Perform a gesture"), live per-class confidence readout (useful for validation), screen kept on while active; SuccessConfirmationDialog with curved text naming the detected gesture, shown alongside the vibration.
README.md — how the pipeline mirrors training, how to tune thresholds, how to build/install.
No runtime permissions needed (IMU at 100 Hz requires none); VIBRATE is a normal manifest permission.
Validation
Parity unit test (JVM): golden JSON generated from the Python pipeline (resample → bandpass_features → normalize, via the tensorflow conda env) for a real sample CSV; the Kotlin DSP must match within a small tolerance. Golden generator script kept in the new repo under tools/.
./gradlew :app:testDebugUnitTest lint assembleDebug must pass.
Notes / tradeoffs
Training applies zero-phase filtering over whole ~3 s recordings; the watch approximates this by filtering the full ~3 s buffer each cycle and classifying the most recent 1 s window — closest streaming equivalent of the training transform.
Detection thresholds are deliberately conservative (test-set DoublePinch precision is only ~0.40); values are centralized and easy to tune during on-watch validation.
