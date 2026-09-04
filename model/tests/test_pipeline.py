from pathlib import Path

import numpy as np
import pytest

from gesture_ml.data import (
    CLASS_NAMES,
    DataSchemaError,
    bandpass_features,
    extract_windows,
    fit_normalizer,
    load_recordings,
    make_windows,
    split_recordings,
)
from gesture_ml.model import build_base_model
from gesture_ml.train import train


def _write_recording(path: Path, class_index: int, phase: float) -> None:
    time = np.arange(0, 1.5, 0.01)
    signal = np.column_stack(
        [np.sin(2 * np.pi * (class_index + axis + 1) * time + phase) for axis in range(6)]
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    np.savetxt(
        path,
        np.column_stack([time, signal]),
        delimiter=",",
        header="timestamp,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z",
        comments="",
    )


@pytest.fixture
def sample_root(tmp_path: Path) -> Path:
    for class_index, name in enumerate(CLASS_NAMES):
        for recording in range(5):
            _write_recording(tmp_path / name / f"trial_{recording}.csv", class_index, recording * 0.2)
    return tmp_path


def test_preprocesses_and_windows_recordings(sample_root: Path) -> None:
    recordings = load_recordings(sample_root)
    train_split, validation_split, test_split = split_recordings(recordings, seed=4)
    assert {item.identifier for item in train_split}.isdisjoint(item.identifier for item in validation_split)
    assert {item.identifier for item in train_split}.isdisjoint(item.identifier for item in test_split)
    normalizer = fit_normalizer(train_split)
    windows, labels = make_windows(validation_split, normalizer)
    assert windows.shape[1:] == (100, 6, 4)
    assert len(windows) == len(labels)
    assert np.isfinite(windows).all()


def test_rejects_missing_channel(tmp_path: Path) -> None:
    class_dir = tmp_path / "DoubleClench"
    class_dir.mkdir()
    (class_dir / "bad.csv").write_text("timestamp,acc_x\n0,0\n1,1\n")
    for name in CLASS_NAMES[1:]:
        (tmp_path / name).mkdir()
    with pytest.raises(DataSchemaError, match="missing"):
        load_recordings(tmp_path)


def test_model_output_shape() -> None:
    model = build_base_model()
    output = model(np.zeros((2, 100, 6, 4), dtype=np.float32), training=False)
    assert output.shape == (2, len(CLASS_NAMES))
    np.testing.assert_allclose(np.sum(output.numpy(), axis=1), np.ones(2), atol=1e-5)


def test_end_to_end_train_and_int8_export(sample_root: Path, tmp_path: Path) -> None:
    result = train(sample_root, tmp_path / "artifacts", epochs=1, seed=3)
    assert result["tflite"]["input_dtype"] == "int8"
    assert result["tflite"]["output_dtype"] == "int8"
    assert (tmp_path / "artifacts" / "gesture_model_int8.tflite").is_file()


def test_augmentations_produce_valid_variants() -> None:
    from gesture_ml.augment import augment_positive, negative_variants

    rng = np.random.default_rng(11)
    signal = np.random.default_rng(0).normal(size=(300, 6)).astype(np.float32)
    augmented = augment_positive(signal, rng)
    assert augmented.shape[1] == 6
    assert np.isfinite(augmented).all()
    variants = negative_variants(signal, rng)
    assert [name for name, _ in variants] == ["reverse", "shuffle", "cutout"]
    for _, variant in variants:
        assert variant.shape == signal.shape
        assert np.isfinite(variant).all()
    np.testing.assert_allclose(variants[0][1], signal[::-1])
