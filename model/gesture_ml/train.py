"""Command-line training entry point."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import numpy as np
import tensorflow as tf

from .augment import augment_positive, negative_variants
from .data import (
    CLASS_NAMES,
    DataSchemaError,
    Recording,
    SAMPLE_RATE_HZ,
    fit_normalizer,
    load_recordings,
    make_windows,
    resample,
    split_recordings,
)
from .export import export_int8
from .model import build_base_model

POSITIVE_AUGMENTATIONS_PER_RECORDING = 5


def _augment_training_set(recordings: list[Recording], seed: int) -> list[Recording]:
    """Add paper-style augmented positives and negative-marked gesture variants."""
    rng = np.random.default_rng(seed)
    null_label = CLASS_NAMES.index("Null")
    augmented = list(recordings)
    for recording in recordings:
        if recording.label == null_label:
            continue
        resampled = resample(recording)
        for index in range(POSITIVE_AUGMENTATIONS_PER_RECORDING):
            signal = augment_positive(resampled.signal, rng)
            timestamps = np.arange(len(signal)) / SAMPLE_RATE_HZ
            augmented.append(
                Recording(f"{recording.identifier}#aug{index}", recording.label, timestamps, signal)
            )
        for variant_name, signal in negative_variants(resampled.signal, rng):
            timestamps = np.arange(len(signal)) / SAMPLE_RATE_HZ
            augmented.append(
                Recording(f"{recording.identifier}#{variant_name}", null_label, timestamps, signal)
            )
    return augmented


def _metrics(model: tf.keras.Model, windows: np.ndarray, labels: np.ndarray) -> dict[str, object]:
    probabilities = model.predict(windows, verbose=0)
    predictions = probabilities.argmax(axis=1)
    confusion = tf.math.confusion_matrix(labels, predictions, num_classes=len(CLASS_NAMES)).numpy()
    total = confusion.sum()
    per_class = {}
    for index, class_name in enumerate(CLASS_NAMES):
        true_positive = int(confusion[index, index])
        precision = true_positive / max(int(confusion[:, index].sum()), 1)
        recall = true_positive / max(int(confusion[index, :].sum()), 1)
        per_class[class_name] = {
            "precision": precision,
            "recall": recall,
            "f1": 2 * precision * recall / max(precision + recall, 1e-12),
            "support": int(confusion[index, :].sum()),
        }
    return {
        "accuracy": float(np.trace(confusion) / max(int(total), 1)),
        "confusion_matrix": confusion.tolist(),
        "per_class": per_class,
    }


def train(samples_dir: str | Path, output_dir: str | Path, epochs: int, seed: int) -> dict[str, object]:
    os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")
    tf.keras.utils.set_random_seed(seed)
    recordings = load_recordings(samples_dir)
    train_recordings, validation_recordings, test_recordings = split_recordings(recordings, seed)
    normalizer = fit_normalizer(train_recordings)
    augmented_train = _augment_training_set(train_recordings, seed)
    train_x, train_y = make_windows(augmented_train, normalizer, peak_offsets=(-12, 0, 12))
    validation_x, validation_y = make_windows(validation_recordings, normalizer, peak_offsets=(0,))
    test_x, test_y = make_windows(test_recordings, normalizer, peak_offsets=(0,))
    counts = np.bincount(train_y, minlength=len(CLASS_NAMES))
    class_weight = {index: float(len(train_y) / (len(CLASS_NAMES) * count)) for index, count in enumerate(counts)}

    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    checkpoint = output / "base_model.keras"
    model = build_base_model()
    callbacks = [
        tf.keras.callbacks.ModelCheckpoint(checkpoint, monitor="val_accuracy", mode="max", save_best_only=True),
        tf.keras.callbacks.EarlyStopping(monitor="val_accuracy", mode="max", patience=20, restore_best_weights=True),
    ]
    history = model.fit(
        train_x,
        train_y,
        validation_data=(validation_x, validation_y),
        epochs=epochs,
        batch_size=min(64, len(train_x)),
        class_weight=class_weight,
        callbacks=callbacks,
        verbose=2,
    )
    best_model = tf.keras.models.load_model(checkpoint)
    tflite = export_int8(best_model, train_x, output / "gesture_model_int8.tflite")
    result = {
        "classes": list(CLASS_NAMES),
        "recording_counts": {
            "train": len(train_recordings),
            "validation": len(validation_recordings),
            "test": len(test_recordings),
        },
        "window_counts": {"train": len(train_x), "validation": len(validation_x), "test": len(test_x)},
        "best_validation_accuracy": float(max(history.history["val_accuracy"])),
        "test": _metrics(best_model, test_x, test_y),
        "tflite": tflite,
    }
    (output / "normalizer.json").write_text(json.dumps(normalizer.to_dict(), indent=2) + "\n")
    (output / "metrics.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Train the Pixel Watch IMU gesture classifier and export strict INT8 TFLite.")
    parser.add_argument("--samples", default="samples", help="Class-directory dataset root.")
    parser.add_argument("--output", default="artifacts", help="Output directory for model and metrics.")
    parser.add_argument("--epochs", type=int, default=200)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    try:
        result = train(args.samples, args.output, args.epochs, args.seed)
    except DataSchemaError as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
