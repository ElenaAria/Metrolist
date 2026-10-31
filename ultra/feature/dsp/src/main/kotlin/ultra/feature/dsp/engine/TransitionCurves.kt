package ultra.feature.dsp.engine

import kotlin.math.pow

/**
 * The shapes of one transition as functions of progress `p` in 0..1 (0 = overlap begins, 1 = overlap
 * over). Pure functions so the musical decisions are testable without any audio.
 *
 * The "bass swap" is the classic DJ move: for the first third the outgoing track keeps its bass and the
 * incoming track has none, around the middle they trade places, afterwards the incoming track owns the
 * low end. Both sweeps share the same smoothstep window, so at any instant the two high-pass cutoffs
 * are mirror images and the combined low end stays roughly constant instead of doubling.
 */
object TransitionCurves {
    /** The cutoff at which a high-pass is "open": a 2nd-order filter at 20 Hz is inaudible on music. */
    const val BASS_FLOOR_HZ = 20f

    private const val SWAP_START = 0.30f
    private const val SWAP_END = 0.70f
    private const val ECHO_START = 0.10f
    private const val ECHO_END = 0.50f

    /** Hermite smoothstep: 0 at/below [edge0], 1 at/above [edge1], zero slope at both ends. */
    fun smoothstep(
        edge0: Float,
        edge1: Float,
        x: Float,
    ): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun logSweep(
        fromHz: Float,
        toHz: Float,
        t: Float,
    ): Float = fromHz * (toHz / fromHz).toDouble().pow(t.toDouble()).toFloat()

    /** Outgoing deck: bass leaves, high-pass rises from the floor to [swapHz]. */
    fun outgoingHighPassHz(
        p: Float,
        swapHz: Float,
    ): Float = logSweep(BASS_FLOOR_HZ, swapHz, smoothstep(SWAP_START, SWAP_END, p))

    /** Incoming deck: bass arrives, high-pass falls from [swapHz] to the floor. */
    fun incomingHighPassHz(
        p: Float,
        swapHz: Float,
    ): Float = logSweep(swapHz, BASS_FLOOR_HZ, smoothstep(SWAP_START, SWAP_END, p))

    /** 0..1 shape of the echo-out send; scaled by the profile's `echoWet`. */
    fun echoAmount(p: Float): Float = smoothstep(ECHO_START, ECHO_END, p)
}
