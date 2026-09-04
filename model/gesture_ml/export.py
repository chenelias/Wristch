"""INT8 TensorFlow Lite conversion and interpreter verification."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import tensorflow as tf


def export_int8(model: tf.keras.Model, calibration_windows: np.ndarray, output_path: str | Path) -> dict[str, object]:
    if len(calibration_windows) == 0:
        raise ValueError("INT8 conversion requires representative calibration windows.")
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    def representative_dataset():
        for window in calibration_windows[: min(len(calibration_windows), 200)]:
            yield [window[np.newaxis, ...].astype(np.float32)]

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.int8
    output_path.write_bytes(converter.convert())
    return verify_tflite(output_path)


def verify_tflite(model_path: str | Path) -> dict[str, object]:
    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    input_detail = interpreter.get_input_details()[0]
    output_detail = interpreter.get_output_details()[0]
    if input_detail["dtype"] != np.int8 or output_detail["dtype"] != np.int8:
        raise ValueError("TFLite model must use int8 inputs and outputs.")
    scale, zero_point = input_detail["quantization"]
    if scale <= 0:
        raise ValueError("TFLite input is missing quantization parameters.")
    input_shape = tuple(int(value) for value in input_detail["shape"])
    quantized_zero = np.full(input_shape, zero_point, dtype=np.int8)
    interpreter.set_tensor(input_detail["index"], quantized_zero)
    interpreter.invoke()
    output = interpreter.get_tensor(output_detail["index"])
    return {
        "path": str(model_path),
        "size_bytes": Path(model_path).stat().st_size,
        "input_shape": list(input_shape),
        "output_shape": [int(value) for value in output.shape],
        "input_dtype": np.dtype(input_detail["dtype"]).name,
        "output_dtype": np.dtype(output_detail["dtype"]).name,
        "input_quantization": [float(scale), int(zero_point)],
        "output_quantization": [float(output_detail["quantization"][0]), int(output_detail["quantization"][1])],
    }
