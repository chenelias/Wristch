package dev.elias.assistivetouchpeeker.ml

import android.content.Context
import dev.elias.assistivetouchpeeker.dsp.CHANNEL_COUNT
import dev.elias.assistivetouchpeeker.dsp.WINDOW_SAMPLES
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.roundToInt
import org.tensorflow.lite.Tensor

const val WINDOW_FEATURES_PER_CHANNEL = 4

/** Loads a `.tflite` asset as a memory-mapped buffer - shared by every `Interpreter` wrapper in `ml/`. */
fun loadModelFile(context: Context, assetName: String): MappedByteBuffer =
    context.assets.openFd(assetName).use { fd ->
        FileInputStream(fd.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

/** A reusable direct buffer sized for one `[100][6][4]` int8-encoded window. */
fun newInt8WindowBuffer(): ByteBuffer =
    ByteBuffer.allocateDirect(WINDOW_SAMPLES * CHANNEL_COUNT * WINDOW_FEATURES_PER_CHANNEL).order(ByteOrder.nativeOrder())

/** Quantizes a `[100][6][4]` normalized window into [destination], rewound and ready to run. */
fun encodeInt8Window(window: Array<Array<FloatArray>>, quantization: Tensor.QuantizationParams, destination: ByteBuffer) {
    require(window.size == WINDOW_SAMPLES) { "Expected $WINDOW_SAMPLES timesteps, got ${window.size}." }
    destination.rewind()
    for (t in 0 until WINDOW_SAMPLES) {
        for (c in 0 until CHANNEL_COUNT) {
            for (f in 0 until WINDOW_FEATURES_PER_CHANNEL) {
                destination.put(quantize(window[t][c][f], quantization))
            }
        }
    }
    destination.rewind()
}

private fun quantize(value: Float, quantization: Tensor.QuantizationParams): Byte {
    val quantized = (value / quantization.scale + quantization.zeroPoint).roundToInt()
    return quantized.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
}
