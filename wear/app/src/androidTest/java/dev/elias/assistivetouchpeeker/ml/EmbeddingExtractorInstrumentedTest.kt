package dev.elias.assistivetouchpeeker.ml

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real on-device TFLite interpreter against a window whose expected embedding
 * was computed by feeding the same window through gesture_model_embedding_int8.tflite
 * via the Python TFLite interpreter (tools/generate_golden_fixture.py) - this is what
 * actually exercises native inference, which a plain JVM unit test cannot do.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingExtractorInstrumentedTest {

    @Test
    fun matchesPythonExportedEmbedding() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = JSONObject(instrumentation.context.assets.open("embedding_golden.json").bufferedReader().readText())

        val window = parseWindow(fixture.getJSONArray("normalizedWindow"))
        val expectedEmbedding = fixture.getJSONArray("expectedEmbedding").let { array ->
            FloatArray(array.length()) { array.getDouble(it).toFloat() }
        }

        EmbeddingExtractor(instrumentation.targetContext).use { extractor ->
            val actualEmbedding = extractor.embed(window)
            assertEquals(expectedEmbedding.size, actualEmbedding.size)
            for (i in expectedEmbedding.indices) {
                assertEquals("index $i", expectedEmbedding[i].toDouble(), actualEmbedding[i].toDouble(), TOLERANCE)
            }
        }
    }

    companion object {
        private const val TOLERANCE = 1e-2
    }
}

internal fun parseWindow(windowArray: org.json.JSONArray): Array<Array<FloatArray>> =
    Array(windowArray.length()) { t ->
        val channels = windowArray.getJSONArray(t)
        Array(channels.length()) { c ->
            val features = channels.getJSONArray(c)
            FloatArray(features.length()) { f -> features.getDouble(f).toFloat() }
        }
    }
