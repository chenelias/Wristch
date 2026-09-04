"""Dataset loading and paper-inspired IMU preprocessing."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
from scipy.signal import butter, sosfiltfilt

CHANNELS = ("acc_x", "acc_y", "acc_z", "gyro_x", "gyro_y", "gyro_z")
CLASS_NAMES = ("DoubleClench", "DoublePinch", "Null")
TIMESTAMP_COLUMNS = ("timestamp", "time", "t_ms", "t_us", "t_ns")
COLUMN_ALIASES = {
    "acc_x": ("acc_x", "ax"),
    "acc_y": ("acc_y", "ay"),
    "acc_z": ("acc_z", "az"),
    "gyro_x": ("gyro_x", "gx"),
    "gyro_y": ("gyro_y", "gy"),
    "gyro_z": ("gyro_z", "gz"),
}
SAMPLE_RATE_HZ = 100
WINDOW_SAMPLES = 100
WINDOW_STRIDE_SAMPLES = 12  # 0.12 s, nearest integer stride to the paper's 0.125 s.
FILTER_BANDS_HZ = ((0.22, 8.0), (8.0, 32.0), (32.0, 48.0))


class DataSchemaError(ValueError):
    """Raised when a recording cannot safely be used for training."""


@dataclass(frozen=True)
class Recording:
    identifier: str
    label: int
    timestamps: np.ndarray
    signal: np.ndarray


@dataclass(frozen=True)
class Normalizer:
    mean: np.ndarray
    std: np.ndarray

    def apply(self, features: np.ndarray) -> np.ndarray:
        return ((features - self.mean) / self.std).astype(np.float32)

    def to_dict(self) -> dict[str, list[float]]:
        return {"mean": self.mean.tolist(), "std": self.std.tolist()}


def _timestamp_seconds(values: np.ndarray, column_name: str) -> np.ndarray:
    values = np.asarray(values, dtype=np.float64)
    if not np.all(np.isfinite(values)):
        raise DataSchemaError("Timestamp column contains non-finite values.")
    deltas = np.diff(values)
    if np.any(deltas <= 0):
        raise DataSchemaError("Timestamps must be strictly increasing.")
    median_delta = float(np.median(deltas))
    if column_name == "t_ns" or median_delta > 1e6:
        values = values / 1e9  # nanoseconds
    elif column_name == "t_us" or median_delta > 1e3:
        values = values / 1e6  # microseconds
    elif column_name == "t_ms" or median_delta > 1:
        values = values / 1e3  # milliseconds
    return values - values[0]


def load_csv_recording(path: Path, label: int, root: Path) -> Recording:
    try:
        data = np.genfromtxt(path, delimiter=",", names=True, dtype=np.float64)
    except ValueError as error:
        raise DataSchemaError(f"{path}: invalid CSV: {error}") from error
    if data.dtype.names is None or len(data) < 2:
        raise DataSchemaError(f"{path}: requires a header and at least two samples.")
    names = set(data.dtype.names)
    timestamp = next((name for name in TIMESTAMP_COLUMNS if name in names), None)
    selected_channels = {
        canonical: next((name for name in aliases if name in names), None)
        for canonical, aliases in COLUMN_ALIASES.items()
    }
    missing = [channel for channel, selected in selected_channels.items() if selected is None]
    if timestamp is None or missing:
        required = ", ".join(("timestamp", *CHANNELS))
        raise DataSchemaError(f"{path}: required columns are {required}; missing {missing}.")
    timestamps = _timestamp_seconds(data[timestamp], timestamp)
    signal = np.column_stack([data[selected_channels[channel]] for channel in CHANNELS])
    if not np.all(np.isfinite(signal)):
        raise DataSchemaError(f"{path}: sensor channels contain non-finite values.")
    return Recording(
        identifier=str(path.relative_to(root)),
        label=label,
        timestamps=timestamps,
        signal=signal.astype(np.float32),
    )


def load_recordings(samples_dir: str | Path) -> list[Recording]:
    root = Path(samples_dir)
    if not root.is_dir():
        raise DataSchemaError(f"Samples directory does not exist: {root}")
    recordings: list[Recording] = []
    for path in sorted(root.rglob("*.csv")):
        path_parts = set(path.relative_to(root).parts[:-1])
        matching_labels = [index for index, class_name in enumerate(CLASS_NAMES) if class_name in path_parts]
        if len(matching_labels) != 1:
            continue
        recordings.append(load_csv_recording(path, matching_labels[0], root))
    if not recordings:
        raise DataSchemaError(f"No CSV recordings found below {root}")
    present_labels = {recording.label for recording in recordings}
    missing_classes = [CLASS_NAMES[index] for index in range(len(CLASS_NAMES)) if index not in present_labels]
    if missing_classes:
        raise DataSchemaError(f"Missing CSV recordings for classes: {', '.join(missing_classes)}")
    return recordings


def resample(recording: Recording, sample_rate_hz: int = SAMPLE_RATE_HZ) -> Recording:
    step = 1.0 / sample_rate_hz
    timestamps = np.arange(0.0, recording.timestamps[-1] + step / 2, step)
    if len(timestamps) < WINDOW_SAMPLES:
        raise DataSchemaError(
            f"{recording.identifier}: resampled recording has only {len(timestamps)} samples; "
            f"requires {WINDOW_SAMPLES}."
        )
    signal = np.column_stack(
        [np.interp(timestamps, recording.timestamps, recording.signal[:, index]) for index in range(len(CHANNELS))]
    )
    return Recording(recording.identifier, recording.label, timestamps, signal.astype(np.float32))


def bandpass_features(signal: np.ndarray, sample_rate_hz: int = SAMPLE_RATE_HZ) -> np.ndarray:
    """Return raw plus three frequency bands per IMU channel: [time, 6, 4]."""
    if signal.ndim != 2 or signal.shape[1] != len(CHANNELS):
        raise ValueError(f"Expected [time, {len(CHANNELS)}] IMU signal, received {signal.shape}.")
    nyquist = sample_rate_hz / 2
    features = [signal]
    for low, high in FILTER_BANDS_HZ:
        sos = butter(2, (low / nyquist, high / nyquist), btype="bandpass", output="sos")
        features.append(sosfiltfilt(sos, signal, axis=0))
    return np.stack(features, axis=-1).astype(np.float32)


def extract_windows(features: np.ndarray, stride: int = WINDOW_STRIDE_SAMPLES) -> np.ndarray:
    if len(features) < WINDOW_SAMPLES:
        return np.empty((0, WINDOW_SAMPLES, len(CHANNELS), 4), dtype=np.float32)
    starts = range(0, len(features) - WINDOW_SAMPLES + 1, stride)
    return np.asarray([features[start : start + WINDOW_SAMPLES] for start in starts], dtype=np.float32)

def extract_peak_window(features: np.ndarray, offset: int = 0) -> np.ndarray:
    """Return a one-second window centered on the strongest middle-band motion peak."""
    if len(features) < WINDOW_SAMPLES:
        return np.empty((0, WINDOW_SAMPLES, len(CHANNELS), 4), dtype=np.float32)
    motion_energy = np.linalg.norm(features[:, :, 2], axis=1)
    smoothed_energy = np.convolve(motion_energy, np.ones(11, dtype=np.float32) / 11, mode="same")
    half_window = WINDOW_SAMPLES // 2
    peak_index = half_window + int(np.argmax(smoothed_energy[half_window:-half_window]))
    start = int(np.clip(peak_index - half_window + offset, 0, len(features) - WINDOW_SAMPLES))
    return features[start : start + WINDOW_SAMPLES][np.newaxis, ...].astype(np.float32)


def split_recordings(
    recordings: Iterable[Recording], seed: int = 7
) -> tuple[list[Recording], list[Recording], list[Recording]]:
    """Stratify at recording level, keeping every recording in exactly one split."""
    groups: dict[int, list[Recording]] = {index: [] for index in range(len(CLASS_NAMES))}
    for recording in recordings:
        groups[recording.label].append(recording)
    rng = np.random.default_rng(seed)
    splits: tuple[list[Recording], list[Recording], list[Recording]] = ([], [], [])
    for label, group in groups.items():
        if len(group) < 3:
            raise DataSchemaError(
                f"{CLASS_NAMES[label]} needs at least 3 recordings for train/validation/test; found {len(group)}."
            )
        shuffled = list(group)
        rng.shuffle(shuffled)
        validation_count = max(1, round(len(shuffled) * 0.2))
        test_count = max(1, round(len(shuffled) * 0.2))
        train_count = len(shuffled) - validation_count - test_count
        if train_count < 1:
            validation_count = 1
            test_count = 1
            train_count = len(shuffled) - 2
        splits[0].extend(shuffled[:train_count])
        splits[1].extend(shuffled[train_count : train_count + validation_count])
        splits[2].extend(shuffled[train_count + validation_count :])
    return splits


def fit_normalizer(recordings: Iterable[Recording]) -> Normalizer:
    features = [bandpass_features(resample(recording).signal).reshape(-1, 4) for recording in recordings]
    if not features:
        raise DataSchemaError("Cannot fit a normalizer without training recordings.")
    stacked = np.concatenate(features, axis=0)
    mean = stacked.mean(axis=0, keepdims=True)
    std = np.maximum(stacked.std(axis=0, keepdims=True), 1e-6)
    return Normalizer(mean=mean.reshape(1, 1, 4), std=std.reshape(1, 1, 4))


def make_windows(
    recordings: Iterable[Recording],
    normalizer: Normalizer,
    peak_offsets: tuple[int, ...] | None = None,
) -> tuple[np.ndarray, np.ndarray]:
    """Create windows.

    When ``peak_offsets`` is provided, gesture recordings are reduced to
    peak-centered windows (the trial contains exactly one gesture), while
    Null recordings always keep sliding windows so that ordinary motion is
    represented the same way the deployed model will see it.
    """
    null_label = CLASS_NAMES.index("Null")
    windows: list[np.ndarray] = []
    labels: list[np.ndarray] = []
    for recording in recordings:
        features = normalizer.apply(bandpass_features(resample(recording).signal))
        if peak_offsets is not None and recording.label != null_label:
            recording_windows = np.concatenate(
                [extract_peak_window(features, offset) for offset in peak_offsets], axis=0
            )
        else:
            recording_windows = extract_windows(features)
        windows.append(recording_windows)
        labels.append(np.full(len(recording_windows), recording.label, dtype=np.int32))
    if not windows or not any(len(window) for window in windows):
        raise DataSchemaError("No 1-second windows were generated from the selected recordings.")
    return np.concatenate(windows), np.concatenate(labels)
