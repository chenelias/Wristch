package dev.elias.assistivetouchpeeker.detection

import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt

/** Tunable training parameters for the per-gesture head - adjust here while validating on-watch. */
object HeadConfig {
    /** Hidden layer width. Small on purpose: only ~25 positive samples exist per gesture. */
    const val HIDDEN_SIZE = 16

    /** Leaky ReLU slope for negative inputs (the paper's value). */
    const val LEAKY_RELU_ALPHA = 0.3f

    /** L2 weight penalty (the paper's value). */
    const val L2_LAMBDA = 5e-5f

    /** Dropout probability on the hidden layer during training (the paper's value). */
    const val DROPOUT = 0.5f

    const val LEARNING_RATE = 0.01f
    const val EPOCHS = 60
    const val BATCH_SIZE = 32
}

/**
 * A small discriminative classifier over the frozen 120-d embedding: `120 -> hidden ->
 * sigmoid`, trained on-watch to separate one enrolled gesture from everything else.
 *
 * This is the paper's customization approach (Xu et al., CHI'22, Section 4.1: freeze the
 * pretrained embedding layers, train a lightweight head per user) with one deliberate
 * deviation - the training loop is hand-written Kotlin rather than TFLite's on-device
 * training API. That API needs the `SELECT_TF_OPS` delegate, which is a stale artifact
 * (last released 2024, under the old `org.tensorflow.lite` group rather than the
 * `litert` line this app uses), adds ~50-80MB per ABI to the APK, and would not even
 * execute in a controlled Python environment when tested. The head is ~2000 parameters,
 * so training it directly is both cheaper and better-conditioned than that dependency.
 *
 * A trained head replaces the nearest-prototype/cosine matcher it supersedes: cosine
 * similarity has no notion of what *isn't* the gesture, so idle motion scored ~55% and
 * real gestures never separated cleanly. Training against a large negative set is
 * precisely what fixes that.
 */
class GestureHead(
    private val weights1: Array<FloatArray>,
    private val bias1: FloatArray,
    private val weights2: FloatArray,
    private val bias2: Float,
) {

    /** Probability that [embedding] is this gesture, in 0..1. */
    fun score(embedding: FloatArray): Float {
        val hidden = FloatArray(weights1[0].size)
        for (h in hidden.indices) {
            var sum = bias1[h]
            for (i in embedding.indices) sum += embedding[i] * weights1[i][h]
            hidden[h] = leakyRelu(sum)
        }
        var output = bias2
        for (h in hidden.indices) output += hidden[h] * weights2[h]
        return sigmoid(output)
    }

    fun toJson(): org.json.JSONObject {
        val json = org.json.JSONObject()
        json.put("weights1", org.json.JSONArray(weights1.map { row -> org.json.JSONArray(row.map { it.toDouble() }) }))
        json.put("bias1", org.json.JSONArray(bias1.map { it.toDouble() }))
        json.put("weights2", org.json.JSONArray(weights2.map { it.toDouble() }))
        json.put("bias2", bias2.toDouble())
        return json
    }

    companion object {
        fun fromJson(json: org.json.JSONObject): GestureHead {
            val weights1Array = json.getJSONArray("weights1")
            val weights1 = Array(weights1Array.length()) { i ->
                val row = weights1Array.getJSONArray(i)
                FloatArray(row.length()) { j -> row.getDouble(j).toFloat() }
            }
            val bias1Array = json.getJSONArray("bias1")
            val weights2Array = json.getJSONArray("weights2")
            return GestureHead(
                weights1 = weights1,
                bias1 = FloatArray(bias1Array.length()) { bias1Array.getDouble(it).toFloat() },
                weights2 = FloatArray(weights2Array.length()) { weights2Array.getDouble(it).toFloat() },
                bias2 = json.getDouble("bias2").toFloat(),
            )
        }

        /**
         * Trains a head to separate [positives] from [negatives]. Positives are weighted up
         * by the class-imbalance ratio (there are only a handful of enrolled reps against
         * hundreds of negatives, so an unweighted loss would just always answer "negative").
         */
        fun train(positives: List<FloatArray>, negatives: List<FloatArray>, random: Random): GestureHead {
            require(positives.isNotEmpty() && negatives.isNotEmpty()) { "Need both positive and negative samples." }
            val inputSize = positives[0].size
            val hiddenSize = HeadConfig.HIDDEN_SIZE

            // He-style init, scaled for the leaky ReLU.
            val scale = sqrt(2.0f / inputSize)
            val weights1 = Array(inputSize) { FloatArray(hiddenSize) { random.nextGaussian().toFloat() * scale } }
            val bias1 = FloatArray(hiddenSize)
            val weights2 = FloatArray(hiddenSize) { random.nextGaussian().toFloat() * sqrt(2.0f / hiddenSize) }
            var bias2 = 0f

            val adam = Adam(inputSize, hiddenSize)
            val samples = positives.map { it to 1f } + negatives.map { it to 0f }
            val positiveWeight = negatives.size.toFloat() / positives.size
            val keepProbability = 1f - HeadConfig.DROPOUT

            repeat(HeadConfig.EPOCHS) {
                val shuffled = samples.shuffled(kotlin.random.Random(random.nextLong()))
                for (batch in shuffled.chunked(HeadConfig.BATCH_SIZE)) {
                    val gradWeights1 = Array(inputSize) { FloatArray(hiddenSize) }
                    val gradBias1 = FloatArray(hiddenSize)
                    val gradWeights2 = FloatArray(hiddenSize)
                    var gradBias2 = 0f

                    for ((input, label) in batch) {
                        // Forward.
                        val preActivation = FloatArray(hiddenSize)
                        val hidden = FloatArray(hiddenSize)
                        val dropoutMask = FloatArray(hiddenSize)
                        for (h in 0 until hiddenSize) {
                            var sum = bias1[h]
                            for (i in 0 until inputSize) sum += input[i] * weights1[i][h]
                            preActivation[h] = sum
                            // Inverted dropout, so inference needs no rescaling.
                            dropoutMask[h] = if (random.nextFloat() < keepProbability) 1f / keepProbability else 0f
                            hidden[h] = leakyRelu(sum) * dropoutMask[h]
                        }
                        var output = bias2
                        for (h in 0 until hiddenSize) output += hidden[h] * weights2[h]
                        val probability = sigmoid(output)

                        // Backward (weighted binary cross-entropy).
                        val sampleWeight = if (label == 1f) positiveWeight else 1f
                        val dOutput = sampleWeight * (probability - label)
                        gradBias2 += dOutput
                        for (h in 0 until hiddenSize) {
                            gradWeights2[h] += dOutput * hidden[h]
                            val dHidden = dOutput * weights2[h] * dropoutMask[h]
                            val dPreActivation =
                                dHidden * if (preActivation[h] > 0f) 1f else HeadConfig.LEAKY_RELU_ALPHA
                            gradBias1[h] += dPreActivation
                            for (i in 0 until inputSize) gradWeights1[i][h] += input[i] * dPreActivation
                        }
                    }

                    val inverseBatch = 1f / batch.size
                    for (i in 0 until inputSize) {
                        for (h in 0 until hiddenSize) {
                            gradWeights1[i][h] = gradWeights1[i][h] * inverseBatch + 2 * HeadConfig.L2_LAMBDA * weights1[i][h]
                        }
                    }
                    for (h in 0 until hiddenSize) {
                        gradBias1[h] *= inverseBatch
                        gradWeights2[h] = gradWeights2[h] * inverseBatch + 2 * HeadConfig.L2_LAMBDA * weights2[h]
                    }
                    gradBias2 *= inverseBatch

                    bias2 = adam.step(weights1, bias1, weights2, bias2, gradWeights1, gradBias1, gradWeights2, gradBias2)
                }
            }
            return GestureHead(weights1, bias1, weights2, bias2)
        }

        private fun leakyRelu(value: Float): Float =
            if (value > 0f) value else HeadConfig.LEAKY_RELU_ALPHA * value

        private fun sigmoid(value: Float): Float = 1f / (1f + exp(-value))
    }
}

/** Adam optimizer state for one [GestureHead]'s parameters, updated in place. */
private class Adam(inputSize: Int, hiddenSize: Int) {
    private val m1 = Array(inputSize) { FloatArray(hiddenSize) }
    private val v1 = Array(inputSize) { FloatArray(hiddenSize) }
    private val mBias1 = FloatArray(hiddenSize)
    private val vBias1 = FloatArray(hiddenSize)
    private val m2 = FloatArray(hiddenSize)
    private val v2 = FloatArray(hiddenSize)
    private var mBias2 = 0f
    private var vBias2 = 0f
    private var step = 0

    /** Applies one update; returns the new `bias2` (the only scalar parameter). */
    fun step(
        weights1: Array<FloatArray>,
        bias1: FloatArray,
        weights2: FloatArray,
        bias2: Float,
        gradWeights1: Array<FloatArray>,
        gradBias1: FloatArray,
        gradWeights2: FloatArray,
        gradBias2: Float,
    ): Float {
        step++
        val correction1 = 1f - pow(BETA1, step)
        val correction2 = 1f - pow(BETA2, step)

        for (i in weights1.indices) {
            for (h in weights1[i].indices) {
                m1[i][h] = BETA1 * m1[i][h] + (1 - BETA1) * gradWeights1[i][h]
                v1[i][h] = BETA2 * v1[i][h] + (1 - BETA2) * gradWeights1[i][h] * gradWeights1[i][h]
                weights1[i][h] -= HeadConfig.LEARNING_RATE * (m1[i][h] / correction1) /
                    (sqrt(v1[i][h] / correction2) + EPSILON)
            }
        }
        for (h in bias1.indices) {
            mBias1[h] = BETA1 * mBias1[h] + (1 - BETA1) * gradBias1[h]
            vBias1[h] = BETA2 * vBias1[h] + (1 - BETA2) * gradBias1[h] * gradBias1[h]
            bias1[h] -= HeadConfig.LEARNING_RATE * (mBias1[h] / correction1) / (sqrt(vBias1[h] / correction2) + EPSILON)

            m2[h] = BETA1 * m2[h] + (1 - BETA1) * gradWeights2[h]
            v2[h] = BETA2 * v2[h] + (1 - BETA2) * gradWeights2[h] * gradWeights2[h]
            weights2[h] -= HeadConfig.LEARNING_RATE * (m2[h] / correction1) / (sqrt(v2[h] / correction2) + EPSILON)
        }
        mBias2 = BETA1 * mBias2 + (1 - BETA1) * gradBias2
        vBias2 = BETA2 * vBias2 + (1 - BETA2) * gradBias2 * gradBias2
        return bias2 - HeadConfig.LEARNING_RATE * (mBias2 / correction1) / (sqrt(vBias2 / correction2) + EPSILON)
    }

    private fun pow(base: Float, exponent: Int): Float {
        var result = 1f
        repeat(exponent) { result *= base }
        return result
    }

    companion object {
        private const val BETA1 = 0.9f
        private const val BETA2 = 0.999f
        private const val EPSILON = 1e-8f
    }
}
