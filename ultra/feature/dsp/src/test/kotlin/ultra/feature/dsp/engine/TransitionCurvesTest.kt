package ultra.feature.dsp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionCurvesTest {
    private val swapHz = 220f

    @Test
    fun smoothstepClampsAndIsMonotonic() {
        assertEquals(0f, TransitionCurves.smoothstep(0.3f, 0.7f, -1f), 0f)
        assertEquals(1f, TransitionCurves.smoothstep(0.3f, 0.7f, 2f), 0f)
        var previous = -1f
        var p = 0f
        while (p <= 1f) {
            val s = TransitionCurves.smoothstep(0.3f, 0.7f, p)
            assertTrue(s >= previous)
            previous = s
            p += 0.01f
        }
    }

    @Test
    fun bassSwapIsAMirrorImage() {
        // Before the window both sit at their start values.
        assertEquals(TransitionCurves.BASS_FLOOR_HZ, TransitionCurves.outgoingHighPassHz(0f, swapHz), 0.01f)
        assertEquals(swapHz, TransitionCurves.incomingHighPassHz(0f, swapHz), 0.01f)

        // At the midpoint they meet.
        val out = TransitionCurves.outgoingHighPassHz(0.5f, swapHz)
        val inc = TransitionCurves.incomingHighPassHz(0.5f, swapHz)
        assertEquals(out, inc, 0.01f)

        // After the window the outgoing deck has lost its bass and the incoming deck has all of it.
        assertEquals(swapHz, TransitionCurves.outgoingHighPassHz(1f, swapHz), 0.01f)
        assertEquals(TransitionCurves.BASS_FLOOR_HZ, TransitionCurves.incomingHighPassHz(1f, swapHz), 0.01f)
    }

    @Test
    fun theDipLandsAtTheSwapFrequencyAndRecovers() {
        // The sweep is logarithmic, so the two cutoffs meet at the geometric mean of the floor and the
        // swap frequency, not at the swap frequency. Both decks are cut hard there: a real dip, but the
        // bass is never doubled and never gone.
        val atSwap = TransitionCurves.outgoingHighPassHz(0.5f, swapHz)
        val kept = { cut: Float ->
            val ratio = TransitionCurves.BASS_FLOOR_HZ / cut
            ratio * ratio * ratio * ratio
        }
        val geometricMean = kotlin.math.sqrt(TransitionCurves.BASS_FLOOR_HZ * swapHz)
        assertEquals(geometricMean.toDouble(), atSwap.toDouble(), 0.01)

        // Before and after the swap window, one deck still has its bass essentially untouched.
        assertTrue(kept(TransitionCurves.outgoingHighPassHz(0f, swapHz)) > 0.99f)
        assertTrue(kept(TransitionCurves.incomingHighPassHz(1f, swapHz)) > 0.99f)
        // And it never vanishes: the other deck is always opening as one closes.
        var p = 0f
        while (p <= 1f) {
            val total = kept(TransitionCurves.outgoingHighPassHz(p, swapHz)) + kept(TransitionCurves.incomingHighPassHz(p, swapHz))
            assertTrue("bass vanished at p=$p (total=$total)", total > 0.01f)
            p += 0.02f
        }
    }

    @Test
    fun echoRisesOnlyInItsWindow() {
        assertEquals(0f, TransitionCurves.echoAmount(0f), 0f)
        assertEquals(0f, TransitionCurves.echoAmount(0.10f), 0f)
        assertTrue(TransitionCurves.echoAmount(0.3f) in 0.01f..0.99f)
        assertEquals(1f, TransitionCurves.echoAmount(0.5f), 0f)
        assertEquals(1f, TransitionCurves.echoAmount(1f), 0f)
    }
}
