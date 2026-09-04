"""Paper-inspired IMU augmentations (Section 4.2.2 of the included paper).

Positive augmentations simulate speed, strength, and temporal variance.
Negative-marked augmentations distort a real gesture so the model learns
that only correctly-ordered, complete gestures count as positives.
All functions operate on resampled [time, 6] float arrays at 100 Hz.
"""

from __future__ import annotations

import numpy as np

SHUFFLE_PIECE_SAMPLES = 10  # 0.1 s at 100 Hz
CUTOUT_SAMPLES = 50  # 0.5 s at 100 Hz


def _interp_channels(signal: np.ndarray, positions: np.ndarray) -> np.ndarray:
    source = np.arange(len(signal), dtype=np.float64)
    return np.column_stack(
        [np.interp(positions, source, signal[:, channel]) for channel in range(signal.shape[1])]
    ).astype(np.float32)


def zoom_signal(signal: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Simulate different gesture speed by uniform time re-scaling."""
    factor = float(rng.uniform(0.85, 1.15))
    length = max(int(round(len(signal) * factor)), 2)
    positions = np.linspace(0, len(signal) - 1, length)
    return _interp_channels(signal, positions)


def scale_signal(signal: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Simulate different gesture strength: s ~ N(1, 0.2^2), clipped to [0, 2]."""
    factor = float(np.clip(rng.normal(1.0, 0.2), 0.0, 2.0))
    return (signal * factor).astype(np.float32)


def time_warp_signal(signal: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Simulate temporal variance with two interior interpolation knots."""
    length = len(signal)
    knots = np.linspace(0, length - 1, 4)
    warped_knots = knots.copy()
    warped_knots[1:-1] += rng.normal(0.0, 0.05, size=2) * length
    warped_knots = np.clip(np.sort(warped_knots), 0, length - 1)
    positions = np.interp(np.arange(length), knots, warped_knots)
    return _interp_channels(signal, positions)


def reverse_signal(signal: np.ndarray) -> np.ndarray:
    return signal[::-1].copy()


def shuffle_signal(signal: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    piece_count = max(len(signal) // SHUFFLE_PIECE_SAMPLES, 2)
    pieces = np.array_split(signal, piece_count)
    order = rng.permutation(len(pieces))
    return np.concatenate([pieces[index] for index in order]).astype(np.float32)


def cutout_signal(signal: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    result = signal.copy()
    if len(result) > CUTOUT_SAMPLES:
        start = int(rng.integers(0, len(result) - CUTOUT_SAMPLES))
        result[start : start + CUTOUT_SAMPLES] = 0.0
    return result.astype(np.float32)


def augment_positive(signal: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Apply a random non-empty combination of the three positive augmentations."""
    operations = [zoom_signal, scale_signal, time_warp_signal]
    chosen = [operation for operation in operations if rng.random() < 0.5]
    if not chosen:
        chosen = [operations[int(rng.integers(0, len(operations)))]]
    augmented = signal
    for operation in chosen:
        augmented = operation(augmented, rng)
    return augmented.astype(np.float32)


def negative_variants(signal: np.ndarray, rng: np.random.Generator) -> list[tuple[str, np.ndarray]]:
    """Distorted gesture copies that must be classified as Null."""
    return [
        ("reverse", reverse_signal(signal)),
        ("shuffle", shuffle_signal(signal, rng)),
        ("cutout", cutout_signal(signal, rng)),
    ]
