package ultra.feature.dsp.dsp

import ultra.core.dsp.Biquad
import ultra.core.dsp.BiquadCoefficients
import ultra.feature.dsp.engine.DeckRole
import ultra.feature.dsp.engine.DjProfile
import ultra.feature.dsp.engine.TransitionCurves
import kotlin.math.abs

/**
 * The effect chain of one deck: bass-swap high-pass, echo-out, and the wet/dry blend that makes
 * engaging and disengaging click-free. Works on interleaved Float32 chunks of at most [CHUNK_FRAMES]
 * frames; the high-pass recursion (from :ultra-core-dsp) keeps double-precision state, because a 20 Hz
 * high-pass at 48 kHz has poles very close to z = 1 where a float32 recursion is noticeably noisier.
 *
 * ## The amount ramp
 * Everything the chain adds is blended in by [amount], which slews towards 1 (engaged) or 0 (released)
 * instead of jumping. Engaging takes 10 ms, releasing 80 ms. When it is at 0 the chain does not touch
 * the audio at all, and [process] says so, so the caller can keep the exact-copy path.
 */
internal class DeckEffects(
    private val sampleRate: Int,
    private val channels: Int,
) {
    private val highPass = Array(channels) { Biquad(BiquadCoefficients.Identity) }
    private val echo = EchoDelay(sampleRate, channels)
    private val dry = FloatArray(CHUNK_FRAMES * channels)

    private var amount = 0f
    private var cutoffHz = TransitionCurves.BASS_FLOOR_HZ
    private var filterPrimed = false
    private var echoDirty = false

    private val engageStep = 1f / (ENGAGE_SECONDS * sampleRate)

    /** True when the chain adds nothing and holds no state worth keeping. */
    val isIdle: Boolean get() = amount == 0f

    fun reset() {
        amount = 0f
        clearState()
    }

    /**
     * Shapes [work] (interleaved, [frames] frames) in place.
     *
     * @return false if the chunk was left untouched (idle and not engaging); true if it was processed
     *   and therefore needs the soft ceiling/dither on the way back to PCM.
     */
    fun process(
        work: FloatArray,
        frames: Int,
        role: DeckRole,
        progress: Float,
        profile: DjProfile,
    ): Boolean {
        require(frames in 1..CHUNK_FRAMES) { "chunk of $frames frames" }
        val releasing = role == DeckRole.SOLO
        if (releasing && amount == 0f) return false

        val samples = frames * channels
        System.arraycopy(work, 0, dry, 0, samples)

        if (profile.bassSwap) {
            val wanted =
                when (role) {
                    DeckRole.OUTGOING -> TransitionCurves.outgoingHighPassHz(progress, profile.swapHz)
                    DeckRole.INCOMING -> TransitionCurves.incomingHighPassHz(progress, profile.swapHz)
                    // Releasing: hold the cutoff where the transition ended, so the tail keeps its tone
                    // instead of sweeping back to flat while it fades out.
                    DeckRole.SOLO -> cutoffHz
                }
            // Retune only on a real change: coefficient design is a handful of trig calls.
            if (!filterPrimed || abs(wanted - cutoffHz) > cutoffHz * RETUNE_THRESHOLD) {
                val coefficients = BiquadCoefficients.highPass(sampleRate.toDouble(), wanted.toDouble())
                for (filter in highPass) filter.coefficients = coefficients
                cutoffHz = wanted
                filterPrimed = true
            }
            for (c in 0 until channels) highPass[c].process(work, offset = c, length = frames, stride = channels)
        }

        if (role == DeckRole.OUTGOING && profile.echoWet > 0f) {
            echo.process(
                buffer = work,
                frames = frames,
                delayFrames = profile.echoDelayMs * sampleRate / 1000,
                feedback = profile.echoFeedback,
                dampCoef = echo.dampingCoefficient(profile.echoDampingHz),
                wet = profile.echoWet * TransitionCurves.echoAmount(progress),
            )
            echoDirty = true
        } else if (echoDirty) {
            echo.clear()
            echoDirty = false
        }

        // The blend moves at one speed in both directions, deliberately the slow one. The effect's own
        // output (a ringing high-pass, a repeating echo) dies away over tens of milliseconds, and the
        // blend must not fade out faster than that or it cuts the tail off with a click.
        var diverged = false
        for (i in 0 until frames) {
            amount = if (!releasing) minOf(1f, amount + engageStep) else maxOf(0f, amount - engageStep)
            val base = i * channels
            for (c in 0 until channels) {
                val j = base + c
                val d = dry[j]
                var y = d + amount * (work[j] - d)
                if (!y.isFinite()) {
                    y = d
                    diverged = true
                }
                work[j] = y
            }
        }

        // A non-finite value would live on in the filter and delay state forever; drop the state instead.
        if (diverged || (releasing && amount == 0f)) clearState()
        return true
    }

    private fun clearState() {
        for (filter in highPass) filter.reset()
        echo.clear()
        filterPrimed = false
        echoDirty = false
    }

    companion object {
        const val CHUNK_FRAMES = 64
        /** How long the effect blend takes to open or to close. Matches the decay of the effects. */
        private const val ENGAGE_SECONDS = 0.080f
        private const val RETUNE_THRESHOLD = 0.002f
    }
}
