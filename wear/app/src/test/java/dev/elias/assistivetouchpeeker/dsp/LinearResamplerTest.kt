package dev.elias.assistivetouchpeeker.dsp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Compares [linearResample] against `numpy.interp` on the same irregular series. */
class LinearResamplerTest {

    @Test
    fun matchesNumpyInterp() {
        val fixture = JSONObject(readResource("resampler_golden.json"))
        val sourceTimestamps = fixture.getJSONArray("sourceTimestamps").toDoubleArray()
        val sourceValues = fixture.getJSONArray("sourceValues").toFloatArray()
        val targetTimestamps = fixture.getJSONArray("targetTimestamps").toDoubleArray()
        val expected = fixture.getJSONArray("expectedValues").toFloatArray()

        val actual = linearResample(sourceTimestamps, sourceValues, targetTimestamps)

        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("index $i", expected[i], actual[i], TOLERANCE)
        }
    }

    companion object {
        private const val TOLERANCE = 1e-4f
    }
}

private fun readResource(name: String): String =
    checkNotNull(object {}.javaClass.classLoader?.getResourceAsStream(name)) { "Missing test resource: $name" }
        .bufferedReader()
        .readText()

private fun org.json.JSONArray.toDoubleArray(): DoubleArray = DoubleArray(length()) { getDouble(it) }

private fun org.json.JSONArray.toFloatArray(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }
