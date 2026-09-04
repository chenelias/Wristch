package dev.elias.assistivetouchpeeker.ml

import android.content.Context
import org.json.JSONArray

/**
 * Fixed pools of embeddings bundled from PixelWatchAssistiveTouch's own training data
 * (`gesture_ml/train.py::_export_embedding_pool`):
 *
 * - `negative_embeddings.json` - Null-class (everyday motion). Serves double duty as the
 *   negatives a custom gesture's head trains against, and as a cheap stand-in for the
 *   paper's "confused with common daily activities" sanity check (Section 4.3), using
 *   nearest-neighbor distance rather than its full offline HDBSCAN clustering.
 * - `base_gesture_embeddings.json` - DoubleClench/DoublePinch. Also trained against as
 *   negatives, so a custom gesture's head learns it is not one of the built-ins;
 *   without these, performing a double pinch could score high on a custom head.
 */
fun loadEmbeddingPool(context: Context, assetName: String): Array<FloatArray> {
    val json = context.assets.open(assetName).bufferedReader().readText()
    val root = JSONArray(json)
    return Array(root.length()) { i ->
        val entry = root.getJSONArray(i)
        FloatArray(entry.length()) { entry.getDouble(it).toFloat() }
    }
}
