"""Compact Keras model following the paper's per-channel CNN design."""

from __future__ import annotations

import tensorflow as tf

from .data import CLASS_NAMES, WINDOW_SAMPLES


@tf.keras.utils.register_keras_serializable(package="pixel_watch")
class ChannelSlice(tf.keras.layers.Layer):
    """Select one sensor channel without unsafe Lambda deserialization."""

    def __init__(self, channel_index: int, **kwargs: object) -> None:
        super().__init__(**kwargs)
        self.channel_index = channel_index

    def call(self, inputs: tf.Tensor) -> tf.Tensor:
        return inputs[:, :, self.channel_index, :]

    def get_config(self) -> dict[str, object]:
        return {**super().get_config(), "channel_index": self.channel_index}


def _mbconv(inputs: tf.Tensor, filters: int, name: str) -> tf.Tensor:
    channels = int(inputs.shape[-1])
    expanded = tf.keras.layers.Conv1D(filters * 2, 1, padding="same", use_bias=False, name=f"{name}_expand")(inputs)
    expanded = tf.keras.layers.BatchNormalization(name=f"{name}_expand_bn")(expanded)
    expanded = tf.keras.layers.ReLU(max_value=6.0, name=f"{name}_expand_relu")(expanded)
    depthwise = tf.keras.layers.DepthwiseConv1D(5, padding="same", use_bias=False, name=f"{name}_depthwise")(expanded)
    depthwise = tf.keras.layers.BatchNormalization(name=f"{name}_depthwise_bn")(depthwise)
    depthwise = tf.keras.layers.ReLU(max_value=6.0, name=f"{name}_depthwise_relu")(depthwise)
    projected = tf.keras.layers.Conv1D(filters, 1, padding="same", use_bias=False, name=f"{name}_project")(depthwise)
    projected = tf.keras.layers.BatchNormalization(name=f"{name}_project_bn")(projected)
    if channels == filters:
        projected = tf.keras.layers.Add(name=f"{name}_residual")([inputs, projected])
    return projected


def build_base_model(class_count: int = len(CLASS_NAMES)) -> tf.keras.Model:
    """Create the 100 Hz, 1 s, six-channel, four-band base classifier."""
    inputs = tf.keras.Input(shape=(WINDOW_SAMPLES, 6, 4), name="imu_features")
    per_channel = []
    for index in range(6):
        signal = ChannelSlice(index, name=f"channel_{index}")(inputs)
        signal = _mbconv(signal, 12, f"channel_{index}_block1")
        signal = _mbconv(signal, 12, f"channel_{index}_block2")
        per_channel.append(signal)
    embedding = tf.keras.layers.Concatenate(axis=-1, name="concat_channels")(per_channel)
    embedding = tf.keras.layers.SeparableConv1D(24, 5, padding="same", activation="relu", name="cross_channel_conv")(
        embedding
    )
    embedding = tf.keras.layers.MaxPooling1D(5, name="embedding_pool")(embedding)
    embedding = tf.keras.layers.Flatten(name="embedding_flatten")(embedding)
    embedding = tf.keras.layers.Dense(120, activation="relu", name="embedding")(embedding)
    x = embedding
    for units in (80, 40, 20, 10):
        x = tf.keras.layers.Dense(units, use_bias=False)(x)
        x = tf.keras.layers.BatchNormalization()(x)
        x = tf.keras.layers.ReLU()(x)
        x = tf.keras.layers.Dropout(0.5)(x)
    outputs = tf.keras.layers.Dense(class_count, activation="softmax", name="classification")(x)
    model = tf.keras.Model(inputs=inputs, outputs=outputs, name="pixel_watch_gesture_base")
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss=tf.keras.losses.SparseCategoricalCrossentropy(),
        metrics=[tf.keras.metrics.SparseCategoricalAccuracy(name="accuracy")],
    )
    return model
