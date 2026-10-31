package ultra.feature.dsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Pcm16Test {
    @Test
    fun everyInt16ValueRoundTripsExactly() {
        for (v in -32768..32767) {
            assertEquals(v, Pcm16.toPcm(Pcm16.toFloat(v)))
        }
    }

    @Test
    fun outOfRangeValuesSaturateInsteadOfWrapping() {
        assertEquals(32767, Pcm16.toPcm(2f))
        assertEquals(-32768, Pcm16.toPcm(-2f))
        assertEquals(32767, Pcm16.toPcm(Float.POSITIVE_INFINITY))
        assertEquals(-32768, Pcm16.toPcm(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun nanBecomesSilence() {
        assertEquals(0, Pcm16.toPcm(Float.NaN))
        assertEquals(0, Pcm16.toPcm(Float.NaN, ditherLsb = 0.4f))
    }

    @Test
    fun ditherMovesTheResultByAtMostOneLsb() {
        val dither = TpdfDither()
        for (v in listOf(-30000, -1, 0, 1, 12345, 32766)) {
            val x = Pcm16.toFloat(v)
            repeat(200) {
                val out = Pcm16.toPcm(x, dither.next())
                assertTrue("dither moved $v to $out", abs(out - v) <= 1)
            }
        }
    }

    @Test
    fun ditherIsUnbiasedAndTriangular() {
        val dither = TpdfDither(seed = 42)
        val n = 200_000
        val values = FloatArray(n) { dither.next() }
        val mean = values.sum() / n
        val variance = values.sumOf { ((it - mean) * (it - mean)).toDouble() } / n
        assertEquals(0.0, mean.toDouble(), 0.01)
        // Two independent uniform variables: variance = 2 * (1/12) = 1/6.
        assertEquals(1.0 / 6.0, variance, 0.002)
        assertTrue(values.all { it > -1f && it < 1f })
    }

    @Test
    fun softCeilingIsIdentityBelowTheThreshold() {
        val t = 0.9f
        for (x in listOf(0f, 0.1f, -0.5f, 0.9f, -0.9f)) {
            assertEquals(x, Pcm16.softCeiling(x, t), 0f)
        }
    }

    @Test
    fun softCeilingNeverReachesFullScaleAndIsSmooth() {
        val t = 0.9f
        var previous = 0f
        var x = t
        while (x < 4f) {
            val y = Pcm16.softCeiling(x, t)
            assertTrue("ceiling exceeded at x=$x (y=$y)", y < 1f)
            assertTrue("not monotonic at x=$x", y >= previous)
            previous = y
            x += 0.01f
        }
        // The negative side mirrors it.
        assertEquals(-Pcm16.softCeiling(1.5f, t), Pcm16.softCeiling(-1.5f, t), 1e-6f)
        // Derivative at the knee is 1: the step right after the knee matches the step before it.
        val below = Pcm16.softCeiling(t, t) - Pcm16.softCeiling(t - 1e-4f, t)
        val above = Pcm16.softCeiling(t + 1e-4f, t) - Pcm16.softCeiling(t, t)
        assertEquals(below, above, 1e-7f)
    }
}
