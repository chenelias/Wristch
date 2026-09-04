package dev.elias.assistivetouchpeeker.ml

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/** Class order matches the model's softmax head (PixelWatchAssistiveTouch/gesture_ml/data.py:CLASS_NAMES). */
enum class GestureClass { DOUBLE_CLENCH, DOUBLE_PINCH, NULL }

data class ClassProbabilities(val doubleClench: Float, val doublePinch: Float, val nullClass: Float) {
    fun forClass(gestureClass: GestureClass): Float = when (gestureClass) {
        GestureClass.DOUBLE_CLENCH -> doubleClench
        GestureClass.DOUBLE_PINCH -> doublePinch
        GestureClass.NULL -> nullClass
    }
}

/**
 * Wraps the strict INT8 gesture_model_int8.tflite interpreter: quantizes the
 * normalized [100, 6, 4] window on the way in, dequantizes the 3-class output
 * on the way out. Quantization parameters are read from the model at runtime
 * rather than hardcoded, so a re-exported model keeps working unchanged.
 */
class GestureClassifier(
    context: Context,
    assetName: String = "gesture_model_int8.tflite",
) : AutoCloseable {

    private val interpreter = Interpreter(loadModelFile(context, assetName))

    private val inputQuantization = interpreter.getInputTensor(0).quantizationParams()
    private val outputQuantization = interpreter.getOutputTensor(0).quantizationParams()

    private val inputBuffer = newInt8WindowBuffer()
    private val outputBuffer = ByteBuffer.allocateDirect(CLASS_COUNT).order(ByteOrder.nativeOrder())

    /** [window] is `[time=100][channel=6][feature=4]` normalized features, oldest sample first. */
    fun classify(window: Array<Array<FloatArray>>): ClassProbabilities {
        encodeInt8Window(window, inputQuantization, inputBuffer)

        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val scores = FloatArray(CLASS_COUNT) { dequantize(outputBuffer.get()) }
        return ClassProbabilities(doubleClench = scores[0], doublePinch = scores[1], nullClass = scores[2])
    }

    private fun dequantize(value: Byte): Float =
        (value.toInt() - outputQuantization.zeroPoint) * outputQuantization.scale

    override fun close() = interpreter.close()

    companion object {
        private const val CLASS_COUNT = 3
    }
}
