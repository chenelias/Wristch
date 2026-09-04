# PixelWatch AssistiveTouch

Train a lightweight, on-device TensorFlow Lite gesture classifier from Google Pixel Watch IMU recordings. The base model recognizes three classes:

1. **DoubleClench** — two consecutive full hand clenches
2. **DoublePinch** — two consecutive index-to-thumb pinches
3. **Null** — normal wrist/hand movement or idle state

The architecture follows Apple's paper [*Enabling Hand Gesture Customization on Wrist-Worn Devices*](https://arxiv.org/abs/2203.15239) (CHI 2022).

## Pipeline

- **Input:** 6-axis IMU (3-axis accelerometer + 3-axis gyroscope) recordings stored in `samples/`, each ~3 seconds long.
- Resamples each recording to **100 Hz**.
- Derives raw plus low/mid/high bandpass features per channel (24 features per timestep).
- Slices one-second windows with a near-8 Hz stride.
- Splits by **recording before windowing**, so overlapping windows cannot leak into validation or testing.
- Model: two per-channel inverted-residual blocks, a cross-channel separable convolution, and a 120-value embedding, with a softmax head over the 3 classes.
- Exports a **strict INT8 input/output** `.tflite` model and validates it with the TensorFlow Lite interpreter.

The paper's user-specific few-shot customization head requires a pre-trained base model and additional per-user data; it is deferred until the base-class recordings establish a reliable base model.

## Dataset layout

Add raw recordings beneath `samples/`, organized by label:

```text
samples/
  DoubleClench/
    participant_001_trial_01.csv
  DoublePinch/
    participant_001_trial_01.csv
  Null/
    participant_001_trial_01.csv
```

The loader also accepts a hand-organized layout:

```text
samples/
  LeftHand/<DoubleClench|DoublePinch|Null>/*.csv
  RightHand/<DoubleClench|DoublePinch|Null>/*.csv
```

Each CSV requires a header and these numeric columns:

```text
timestamp,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z
```

`timestamp` may be in seconds, milliseconds, microseconds, or nanoseconds and must be strictly increasing. Supply at least three recordings per class (five or more recommended); the split is per recording, not per window.

The Watch exporter format `t_ms,ax,ay,az,gx,gy,gz,skew_ms` is also read directly: `t_ms` is treated as milliseconds and `skew_ms` is ignored.

### Optional: augment with the OpenWatch dataset

The gated Hugging Face dataset [`pietrobonazzi/openwatch`](https://huggingface.co/datasets/pietrobonazzi/openwatch) (CC BY 4.0) provides `double_clench`/`double_pinch` trials from 50 participants plus daily-living activities usable as `Null`. After accepting its conditions and logging in with `hf auth login`:

```bash
conda run -n tensorflow python -c "from huggingface_hub import snapshot_download; print(snapshot_download('pietrobonazzi/openwatch', repo_type='dataset', allow_patterns=['open_watch_without_augmentations/*']))"
conda run -n tensorflow python -m gesture_ml.import_openwatch --snapshot <printed-path>/open_watch_without_augmentations --pixel-samples samples --output samples/OpenWatch
```

The importer synthesizes 100 Hz timestamps, calibrates accelerometer counts to m/s² via gravity magnitude, and scales gyroscope counts by matching motion percentiles against the local Pixel Watch recordings. Run it against a `samples` tree without a previous `samples/OpenWatch` import (delete that directory first if re-importing).

## Train and export

All ML commands run in the designated Conda environment:

```bash
conda run -n tensorflow python -m gesture_ml.train --samples samples --output artifacts --epochs 200
```

Successful training creates:

- `artifacts/base_model.keras` — best validation checkpoint
- `artifacts/gesture_model_int8.tflite` — deployable, fully INT8 model
- `artifacts/normalizer.json` — training-only feature normalization parameters needed by watch-side preprocessing
- `artifacts/metrics.json` — split sizes, held-out confusion matrix/per-class metrics, and TFLite interpreter verification

## Test

```bash
conda run -n tensorflow python -m pytest -q
```

Tests build synthetic recordings, verify preprocessing and recording-level isolation, train for one epoch, convert to INT8 TFLite, and execute a TFLite inference.
