package ultra.core.dsp

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.tan

/**
 * The K-weighting filter of ITU-R BS.1770-4: a "pre-filter" high shelf (about +4 dB above 1.7 kHz, modelling
 * the head) followed by the RLB high-pass (about 38 Hz). The standard publishes coefficients for 48 kHz
 * only; for other rates they are derived from the filters' analogue parameters with a bilinear transform,
 * using the same constants as libebur128 (the EBU's reference implementation), which reproduces the
 * published 48 kHz table.
 */
public object KWeighting {
    private const val SHELF_FREQUENCY_HZ = 1681.974450955533
    private const val SHELF_GAIN_DB = 3.999843853973347
    private const val SHELF_Q = 0.7071752369554196
    private const val SHELF_BAND_EXPONENT = 0.4996667741545416

    private const val HIGH_PASS_FREQUENCY_HZ = 38.13547087602444
    private const val HIGH_PASS_Q = 0.5003270373238773

    /** Stage 1: the high-shelf pre-filter. */
    public fun preFilter(sampleRate: Double): BiquadCoefficients {
        require(sampleRate > 0.0) { "sampleRate must be positive: $sampleRate" }
        val k = tan(PI * SHELF_FREQUENCY_HZ / sampleRate)
        val vh = dbToAmplitude(SHELF_GAIN_DB)
        val vb = vh.pow(SHELF_BAND_EXPONENT)
        val a0 = 1.0 + k / SHELF_Q + k * k
        return BiquadCoefficients(
            b0 = (vh + vb * k / SHELF_Q + k * k) / a0,
            b1 = 2.0 * (k * k - vh) / a0,
            b2 = (vh - vb * k / SHELF_Q + k * k) / a0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / SHELF_Q + k * k) / a0,
        )
    }

    /** Stage 2: the revised low-frequency B-curve (RLB) high-pass. Its numerator is exactly `1 - 2z^-1 + z^-2`. */
    public fun highPass(sampleRate: Double): BiquadCoefficients {
        require(sampleRate > 0.0) { "sampleRate must be positive: $sampleRate" }
        val k = tan(PI * HIGH_PASS_FREQUENCY_HZ / sampleRate)
        val a0 = 1.0 + k / HIGH_PASS_Q + k * k
        return BiquadCoefficients(
            b0 = 1.0,
            b1 = -2.0,
            b2 = 1.0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / HIGH_PASS_Q + k * k) / a0,
        )
    }

    /** Both stages, in the order the signal passes through them. */
    public fun stages(sampleRate: Double): List<BiquadCoefficients> = listOf(preFilter(sampleRate), highPass(sampleRate))
}
