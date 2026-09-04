package dev.elias.assistivetouchpeeker.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real on-device TFLite interpreter's quantize->invoke->dequantize round-trip
 * against a window whose expected probabilities were computed the same way in Python
 * (tools/generate_golden_fixture.py). Nothing else verifies this round-trip - the JVM
 * DSP parity tests stop at the normalized window, before any TFLite involvement.
 */
@RunWith(AndroidJUnit4::class)
class GestureClassifierInstrumentedTest {

    @Test
    fun matchesPythonDequantizedOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture =
            JSONObject(instrumentation.context.assets.open("classification_golden.json").bufferedReader().readText())

        val window = parseWindow(fixture.getJSONArray("normalizedWindow"))
        val expected = fixture.getJSONArray("expectedProbabilities")

        GestureClassifier(instrumentation.targetContext).use { classifier ->
            val actual = classifier.classify(window)
            assertEquals("doubleClench", expected.getDouble(0), actual.doubleClench.toDouble(), TOLERANCE)
            assertEquals("doublePinch", expected.getDouble(1), actual.doublePinch.toDouble(), TOLERANCE)
            assertEquals("nullClass", expected.getDouble(2), actual.nullClass.toDouble(), TOLERANCE)
        }
    }

    companion object {
        private const val TOLERANCE = 1e-2
    }
}
