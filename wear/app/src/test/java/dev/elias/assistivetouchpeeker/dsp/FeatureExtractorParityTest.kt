package dev.elias.assistivetouchpeeker.dsp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Compares [FeatureExtractor] against the training pipeline's
 * resample -> bandpass_features -> normalize (PixelWatchAssistiveTouch/gesture_ml/data.py),
 * run on a real recording by tools/generate_golden_fixture.py.
 */
class FeatureExtractorParityTest {

    @Test
    fun matchesTrainingPipeline() {
        val fixture = JSONObject(readResource("dsp_golden.json"))
        val inputSignal = fixture.getJSONArray("inputSignal")
        val timeSteps = inputSignal.length()
        val channelCount = inputSignal.getJSONArray(0).length()
        val resampledSignal = Array(timeSteps) { t ->
            val row = inputSignal.getJSONArray(t)
            FloatArray(channelCount) { c -> row.getDouble(c).toFloat() }
        }

        val normalizer = NormalizerParams.parse(File("src/main/assets/normalizer.json").readText())
        val features = FeatureExtractor(normalizer).extract(resampledSignal)
        val actualWindow = features.copyOfRange(features.size - WINDOW_SAMPLES, features.size)

        val expectedWindow = fixture.getJSONArray("expectedWindow")
        assertEquals(WINDOW_SAMPLES, expectedWindow.length())
        for (t in 0 until WINDOW_SAMPLES) {
            val expectedChannels = expectedWindow.getJSONArray(t)
            for (c in 0 until channelCount) {
                val expectedFeatures = expectedChannels.getJSONArray(c)
                for (f in 0 until FeatureExtractor.FEATURES_PER_CHANNEL) {
                    val expected = expectedFeatures.getDouble(f)
                    val actual = actualWindow[t][c][f].toDouble()
                    assertEquals("t=$t c=$c f=$f", expected, actual, TOLERANCE)
                }
            }
        }
    }

    companion object {
        private const val WINDOW_SAMPLES = 100
        private const val TOLERANCE = 1e-2
    }
}

private fun readResource(name: String): String =
    checkNotNull(object {}.javaClass.classLoader?.getResourceAsStream(name)) { "Missing test resource: $name" }
        .bufferedReader()
        .readText()
