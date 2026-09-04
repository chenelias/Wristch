package dev.elias.assistivetouchpeeker.dsp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Compares [findPeakWindow] against `gesture_ml.data.extract_peak_window`
 * (PixelWatchAssistiveTouch/gesture_ml/data.py), run on a real recording's normalized
 * features by tools/generate_golden_fixture.py.
 */
class PeakWindowFinderParityTest {

    @Test
    fun matchesTrainingPipeline() {
        val fixture = JSONObject(readResource("peak_window_golden.json"))
        val inputArray = fixture.getJSONArray("inputFeatures")
        val timeSteps = inputArray.length()
        val channelCount = inputArray.getJSONArray(0).length()
        val featureCount = inputArray.getJSONArray(0).getJSONArray(0).length()

        val features = Array(timeSteps) { t ->
            val channels = inputArray.getJSONArray(t)
            Array(channelCount) { c ->
                val channelFeatures = channels.getJSONArray(c)
                FloatArray(featureCount) { f -> channelFeatures.getDouble(f).toFloat() }
            }
        }

        val actualWindow = findPeakWindow(features)
        checkNotNull(actualWindow) { "findPeakWindow returned null for a $timeSteps-sample input." }

        val expectedWindow = fixture.getJSONArray("expectedPeakWindow")
        assertEquals(WINDOW_SAMPLES, expectedWindow.length())
        for (t in 0 until WINDOW_SAMPLES) {
            val expectedChannels = expectedWindow.getJSONArray(t)
            for (c in 0 until channelCount) {
                val expectedFeatures = expectedChannels.getJSONArray(c)
                for (f in 0 until featureCount) {
                    assertEquals(
                        "t=$t c=$c f=$f",
                        expectedFeatures.getDouble(f),
                        actualWindow[t][c][f].toDouble(),
                        TOLERANCE,
                    )
                }
            }
        }
    }

    companion object {
        private const val TOLERANCE = 1e-4
    }
}

private fun readResource(name: String): String =
    checkNotNull(object {}.javaClass.classLoader?.getResourceAsStream(name)) { "Missing test resource: $name" }
        .bufferedReader()
        .readText()
