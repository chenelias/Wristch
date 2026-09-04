package dev.elias.assistivetouchpeeker.detection

import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Training has randomness, so these assert learning behaviour rather than golden values:
 * the head must separate held-out samples it never saw, and must not simply answer
 * "negative" for everything despite the heavy class imbalance it trains under.
 */
class GestureHeadTest {

    @Test
    fun separatesHeldOutSamplesFromTwoClusters() {
        val random = Random(7)
        val positiveCenter = FloatArray(EMBEDDING_SIZE) { if (it % 3 == 0) 2f else 0.2f }
        val negativeCenter = FloatArray(EMBEDDING_SIZE) { if (it % 3 == 0) 0.2f else 1.5f }

        val trainPositives = List(25) { jitter(positiveCenter, random) }
        val trainNegatives = List(300) { jitter(negativeCenter, random) }
        val head = GestureHead.train(trainPositives, trainNegatives, random)

        val heldOutPositives = List(20) { jitter(positiveCenter, random) }
        val heldOutNegatives = List(20) { jitter(negativeCenter, random) }

        val positiveHits = heldOutPositives.count { head.score(it) > 0.5f }
        val negativeHits = heldOutNegatives.count { head.score(it) > 0.5f }

        assertTrue("expected most held-out positives to score high, got $positiveHits/20", positiveHits >= 18)
        assertTrue("expected few held-out negatives to score high, got $negativeHits/20", negativeHits <= 2)
    }

    @Test
    fun classImbalanceDoesNotCollapseToAlwaysNegative() {
        val random = Random(11)
        val positiveCenter = FloatArray(EMBEDDING_SIZE) { if (it < 10) 3f else 0f }
        val negativeCenter = FloatArray(EMBEDDING_SIZE) { if (it < 10) 0f else 1f }

        // Deliberately extreme imbalance: 5 positives against 400 negatives.
        val head = GestureHead.train(
            positives = List(5) { jitter(positiveCenter, random) },
            negatives = List(400) { jitter(negativeCenter, random) },
            random = random,
        )

        val positiveScore = head.score(positiveCenter)
        assertTrue("positive center should still score above 0.5, got $positiveScore", positiveScore > 0.5f)
    }

    @Test
    fun survivesJsonRoundTrip() {
        val random = Random(3)
        val head = GestureHead.train(
            positives = List(10) { jitter(FloatArray(EMBEDDING_SIZE) { 1f }, random) },
            negatives = List(50) { jitter(FloatArray(EMBEDDING_SIZE) { -1f }, random) },
            random = random,
        )
        val probe = FloatArray(EMBEDDING_SIZE) { 0.9f }

        val restored = GestureHead.fromJson(head.toJson())

        assertTrue(
            "restored head should score identically",
            kotlin.math.abs(head.score(probe) - restored.score(probe)) < 1e-6f,
        )
    }

    private fun jitter(center: FloatArray, random: Random): FloatArray =
        FloatArray(center.size) { center[it] + random.nextGaussian().toFloat() * 0.25f }

    companion object {
        private const val EMBEDDING_SIZE = 120
    }
}
