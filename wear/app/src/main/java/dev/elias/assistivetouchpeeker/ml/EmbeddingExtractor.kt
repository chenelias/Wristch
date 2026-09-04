package dev.elias.assistivetouchpeeker.ml

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/** Dimensionality of the model's `embedding` layer (PixelWatchAssistiveTouch/gesture_ml/model.py). */
const val EMBEDDING_SIZE = 120

/**
 * Wraps the frozen embedding sub-model (gesture_model_embedding_int8.tflite): quantizes
 * the normalized [100, 6, 4] window the same way [GestureClassifier] does, but reads a
 * plain float32 embedding back - the export deliberately leaves this output unquantized
 * (see PixelWatchAssistiveTouch/gesture_ml/export.py:export_int8_embedding) since a
 * 3-5-shot nearest-prototype matcher needs more precision than one shared int8
 * scale/zero-point across all 120 dimensions would give.
 */
class EmbeddingExtractor(
    context: Context,
    assetName: String = "gesture_model_embedding_int8.tflite",
) : AutoCloseable {

    private val interpreter = Interpreter(loadModelFile(context, assetName))
    private val inputQuantization = interpreter.getInputTensor(0).quantizationParams()

    private val inputBuffer = newInt8WindowBuffer()
    private val outputBuffer =
        ByteBuffer.allocateDirect(EMBEDDING_SIZE * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())

    /** [window] is `[time=100][channel=6][feature=4]` normalized features, oldest sample first. */
    fun embed(window: Array<Array<FloatArray>>): FloatArray {
        encodeInt8Window(window, inputQuantization, inputBuffer)

        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val embedding = FloatArray(EMBEDDING_SIZE)
        outputBuffer.asFloatBuffer().get(embedding)
        return embedding
    }

    override fun close() = interpreter.close()
}
