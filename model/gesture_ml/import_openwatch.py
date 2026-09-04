"""Convert the Hugging Face OpenWatch dataset into canonical training CSVs.

OpenWatch (pietrobonazzi/openwatch, CC BY 4.0) stores 3-second, 300-sample
trials of raw integer sensor counts with no timestamp column:
``PPG_mean,AccX,AccY,AccZ,GyroX,GyroY,GyroZ``.

Conversion performed here:
- timestamps are synthesized at 100 Hz (300 samples over 3 seconds);
- accelerometer counts are calibrated per recording so that the median
  acceleration magnitude equals standard gravity (the wrist is near rest
  for most of each trial);
- gyroscope counts are scaled by one global factor chosen so the 95th
  percentile of gyro magnitude on OpenWatch double-gesture trials matches
  the same statistic computed from the local Pixel Watch recordings.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from .data import CLASS_NAMES, load_recordings

GRAVITY = 9.80665
POSITIVE_GESTURES = {"double_clench": "DoubleClench", "double_pinch": "DoublePinch"}
NULL_GESTURES = (
    "type_computer",
    "type_phone",
    "write_pen",
    "wash_hands",
    "waving_hello",
    "clapping",
    "grab_cup",
    "answer_phone",
    "clench",
    "pinch",
)
NULL_PARTICIPANTS_PER_GESTURE = 15


def _read_openwatch_csv(path: Path) -> tuple[np.ndarray, np.ndarray]:
    data = np.genfromtxt(path, delimiter=",", names=True, dtype=np.float64)
    accelerometer = np.column_stack([data["AccX"], data["AccY"], data["AccZ"]])
    gyroscope = np.column_stack([data["GyroX"], data["GyroY"], data["GyroZ"]])
    return accelerometer, gyroscope


def _pixel_gyro_p95(samples_dir: Path) -> float:
    recordings = load_recordings(samples_dir)
    null_label = CLASS_NAMES.index("Null")
    magnitudes = [
        np.linalg.norm(recording.signal[:, 3:6], axis=1)
        for recording in recordings
        if recording.label != null_label
    ]
    return float(np.percentile(np.concatenate(magnitudes), 95))


def _openwatch_gyro_p95(snapshot: Path) -> float:
    magnitudes = []
    for gesture in POSITIVE_GESTURES:
        for path in sorted(snapshot.rglob(f"{gesture}.csv")):
            _, gyroscope = _read_openwatch_csv(path)
            magnitudes.append(np.linalg.norm(gyroscope, axis=1))
    if not magnitudes:
        raise SystemExit(f"No positive gesture CSVs found below {snapshot}")
    return float(np.percentile(np.concatenate(magnitudes), 95))


def _convert_file(path: Path, gyro_scale: float, output_path: Path) -> bool:
    accelerometer, gyroscope = _read_openwatch_csv(path)
    if len(accelerometer) < 150:
        return False
    magnitude = np.linalg.norm(accelerometer, axis=1)
    median_magnitude = float(np.median(magnitude))
    if median_magnitude <= 0:
        return False
    accelerometer = accelerometer * (GRAVITY / median_magnitude)
    gyroscope = gyroscope * gyro_scale
    timestamps = np.arange(len(accelerometer)) / 100.0
    output_path.parent.mkdir(parents=True, exist_ok=True)
    rows = np.column_stack([timestamps, accelerometer, gyroscope])
    header = "timestamp,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z"
    np.savetxt(output_path, rows, delimiter=",", header=header, comments="", fmt="%.6f")
    return True


def import_openwatch(snapshot: Path, pixel_samples: Path, output: Path) -> dict[str, int]:
    gyro_scale = _pixel_gyro_p95(pixel_samples) / max(_openwatch_gyro_p95(snapshot), 1e-9)
    counts: dict[str, int] = {name: 0 for name in CLASS_NAMES}
    for gesture, class_name in POSITIVE_GESTURES.items():
        for path in sorted(snapshot.rglob(f"{gesture}.csv")):
            participant, position = path.parent.parent.name, path.parent.name
            destination = output / class_name / f"{participant}_{position}_{gesture}.csv"
            counts[class_name] += int(_convert_file(path, gyro_scale, destination))
    for gesture in NULL_GESTURES:
        paths = sorted(snapshot.rglob(f"{gesture}.csv"))[:NULL_PARTICIPANTS_PER_GESTURE]
        for path in paths:
            participant, position = path.parent.parent.name, path.parent.name
            destination = output / "Null" / f"{participant}_{position}_{gesture}.csv"
            counts["Null"] += int(_convert_file(path, gyro_scale, destination))
    (output / "import_summary.json").write_text(
        json.dumps({"gyro_scale": gyro_scale, "converted": counts}, indent=2) + "\n"
    )
    return counts


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert the OpenWatch dataset into canonical training CSVs.")
    parser.add_argument("--snapshot", required=True, help="Path to open_watch_without_augmentations.")
    parser.add_argument("--pixel-samples", default="samples", help="Existing Pixel Watch samples for calibration.")
    parser.add_argument("--output", default="samples/OpenWatch", help="Destination inside the samples tree.")
    args = parser.parse_args()
    counts = import_openwatch(Path(args.snapshot), Path(args.pixel_samples), Path(args.output))
    print(json.dumps(counts, indent=2))


if __name__ == "__main__":
    main()
