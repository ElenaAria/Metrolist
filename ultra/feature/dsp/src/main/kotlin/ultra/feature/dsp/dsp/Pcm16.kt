package ultra.feature.dsp.dsp

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * Conversions between the 16-bit PCM the audio sink hands us and the Float32 domain all ultra DSP runs
 * in. The scale is the same power of two in both directions, so int -> float -> int is exact for every
 * one of the 65536 values (float32 has 24 bits of mantissa), and the Float32 stage adds no rounding
 * until the single, deliberate rounding on the way out.
 */
internal object Pcm16 {
    const val SCALE = 32768f
    const val INV_SCALE = 1f / 32768f

    fun toFloat(sample: Int): Float = sample * INV_SCALE

    /** Rounds to the nearest 16-bit value, saturating (never wrapping). NaN becomes silence. */
    fun toPcm(x: Float): Int {
        if (x != x) return 0
        return (x * SCALE).roundToInt().coerceIn(-32768, 32767)
    }

    /** Same, with [ditherLsb] (in units of one 16-bit LSB) added before the rounding. */
    fun toPcm(
        x: Float,
        ditherLsb: Float,
    ): Int {
        if (x != x) return 0
        return (x * SCALE + ditherLsb).roundToInt().coerceIn(-32768, 32767)
    }

    /**
     * Identity up to [threshold], then bends smoothly towards 1.0 and never reaches it.
     *
     * For |x| > t: `t + (1 - t) * tanh((|x| - t) / (1 - t))`. Value and first derivative are continuous at
     * the knee (the slope there is exactly 1), so there is no audible kink and nothing below the threshold
     * is touched. Because tanh saturates, the output can never exceed full scale, so overs created by
     * filter overshoot or echo build-up cannot clip in the int16 conversion or wrap around.
     */
    fun softCeiling(
        x: Float,
        threshold: Float,
    ): Float {
        val a = abs(x)
        if (a <= threshold) return x
        // Computed in double: float32 tanh saturates to exactly 1 a little past the knee, which would put
        // the result on the full-scale boundary this function promises never to reach.
        val knee = (1.0 - threshold).coerceAtLeast(1e-3)
        val y = threshold + knee * tanh((a - threshold) / knee)
        val capped = minOf(y, CEILING_MAX)
        return (if (x < 0f) -capped else capped).toFloat()
    }

    /** The most this function will ever return: one float32 step under full scale. */
    private const val CEILING_MAX = 0.999999
}

/**
 * TPDF dither: the sum of two independent uniform variables, +-1 LSB peak, variance 1/6 LSB^2. This is
 * the standard shape because it decorrelates the rounding error from the signal (no distortion, only a
 * constant noise floor of about -98 dBFS RMS) and makes the error's mean and power independent of the
 * signal level.
 *
 * A seeded xorshift32 instead of java.util.Random: allocation-free, branch-free and deterministic, so
 * tests can assert the statistics exactly.
 */
internal class TpdfDither(
    seed: Int = 0x2545F491,
) {
    private var state = if (seed == 0) 1 else seed

    private fun nextUnit(): Float {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return (x ushr 8) * (1f / 16_777_216f)
    }

    /** One dither sample in LSB units, in (-1, 1). */
    fun next(): Float = nextUnit() - nextUnit()
}
