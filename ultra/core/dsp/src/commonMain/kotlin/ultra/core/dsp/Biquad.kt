package ultra.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order IIR section `H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)`; `a0` is
 * implicitly 1. The designers below follow Robert Bristow-Johnson's "Audio EQ Cookbook".
 */
public data class BiquadCoefficients(
    public val b0: Double,
    public val b1: Double,
    public val b2: Double,
    public val a1: Double,
    public val a2: Double,
) {
    /** True when both poles lie strictly inside the unit circle (the Jury criterion for a quadratic). */
    public val isStable: Boolean get() = abs(a2) < 1.0 && abs(a1) < 1.0 + a2

    /** Linear gain `|H(e^jw)|` at [frequencyHz] for the given [sampleRate]. */
    public fun magnitude(
        frequencyHz: Double,
        sampleRate: Double,
    ): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)
        val numRe = b0 + b1 * cosW + b2 * cos2W
        val numIm = -(b1 * sinW + b2 * sin2W)
        val denRe = 1.0 + a1 * cosW + a2 * cos2W
        val denIm = -(a1 * sinW + a2 * sin2W)
        return sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm))
    }

    /** Gain in decibels at [frequencyHz]. */
    public fun magnitudeDb(
        frequencyHz: Double,
        sampleRate: Double,
    ): Double = amplitudeToDb(magnitude(frequencyHz, sampleRate))

    public companion object {
        public val Identity: BiquadCoefficients = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        public fun lowPass(
            sampleRate: Double,
            frequencyHz: Double,
            q: Double = 1.0 / sqrt(2.0),
        ): BiquadCoefficients =
            design(sampleRate, frequencyHz, q) { cosW, alpha ->
                val b = (1.0 - cosW) / 2.0
                Raw(b, 1.0 - cosW, b, 1.0 + alpha, -2.0 * cosW, 1.0 - alpha)
            }

        public fun highPass(
            sampleRate: Double,
            frequencyHz: Double,
            q: Double = 1.0 / sqrt(2.0),
        ): BiquadCoefficients =
            design(sampleRate, frequencyHz, q) { cosW, alpha ->
                val b = (1.0 + cosW) / 2.0
                Raw(b, -(1.0 + cosW), b, 1.0 + alpha, -2.0 * cosW, 1.0 - alpha)
            }

        public fun notch(
            sampleRate: Double,
            frequencyHz: Double,
            q: Double = 1.0,
        ): BiquadCoefficients =
            design(sampleRate, frequencyHz, q) { cosW, alpha ->
                Raw(1.0, -2.0 * cosW, 1.0, 1.0 + alpha, -2.0 * cosW, 1.0 - alpha)
            }

        /** Bell filter: boosts or cuts by [gainDb] around [frequencyHz]; unity gain far away from it. */
        public fun peaking(
            sampleRate: Double,
            frequencyHz: Double,
            q: Double,
            gainDb: Double,
        ): BiquadCoefficients =
            design(sampleRate, frequencyHz, q) { cosW, alpha ->
                val a = dbToAmplitude(gainDb / 2.0) // 10^(dB/40)
                Raw(1.0 + alpha * a, -2.0 * cosW, 1.0 - alpha * a, 1.0 + alpha / a, -2.0 * cosW, 1.0 - alpha / a)
            }

        /** Shelf that applies [gainDb] below [frequencyHz] and leaves frequencies far above it alone. */
        public fun lowShelf(
            sampleRate: Double,
            frequencyHz: Double,
            q: Double,
            gainDb: Double,
        ): BiquadCoefficients =
            design(sampleRate, frequencyHz, q) { cosW, alpha ->
                val a = dbToAmplitude(gainDb / 2.0)
                val t = 2.0 * sqrt(a) * alpha
                Raw(
                    b0 = a * ((a + 1.0) - (a - 1.0) * cosW + t),
                    b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW),
                    b2 = a * ((a + 1.0) - (a - 1.0) * cosW - t),
                    a0 = (a + 1.0) + (a - 1.0) * cosW + t,
                    a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW),
                    a2 = (a + 1.0) + (a - 1.0) * cosW - t,
                )
            }

        /** Shelf that applies [gainDb] above [frequencyHz] and leaves frequencies far below it alone. */
        public fun highShelf(
            sampleRate: Double,
            frequencyHz: Double,
            q: Double,
            gainDb: Double,
        ): BiquadCoefficients =
            design(sampleRate, frequencyHz, q) { cosW, alpha ->
                val a = dbToAmplitude(gainDb / 2.0)
                val t = 2.0 * sqrt(a) * alpha
                Raw(
                    b0 = a * ((a + 1.0) + (a - 1.0) * cosW + t),
                    b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW),
                    b2 = a * ((a + 1.0) + (a - 1.0) * cosW - t),
                    a0 = (a + 1.0) - (a - 1.0) * cosW + t,
                    a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW),
                    a2 = (a + 1.0) - (a - 1.0) * cosW - t,
                )
            }

        private class Raw(
            val b0: Double,
            val b1: Double,
            val b2: Double,
            val a0: Double,
            val a1: Double,
            val a2: Double,
        )

        private inline fun design(
            sampleRate: Double,
            frequencyHz: Double,
            q: Double,
            raw: (cosW: Double, alpha: Double) -> Raw,
        ): BiquadCoefficients {
            require(sampleRate > 0.0) { "sampleRate must be positive: $sampleRate" }
            require(frequencyHz > 0.0 && frequencyHz < sampleRate / 2.0) {
                "frequencyHz must be between 0 and Nyquist (${sampleRate / 2.0}): $frequencyHz"
            }
            require(q > 0.0) { "q must be positive: $q" }
            val w = 2.0 * PI * frequencyHz / sampleRate
            val r = raw(cos(w), sin(w) / (2.0 * q))
            return BiquadCoefficients(r.b0 / r.a0, r.b1 / r.a0, r.b2 / r.a0, r.a1 / r.a0, r.a2 / r.a0)
        }
    }
}

/**
 * One biquad with its own state, in transposed direct form II (good numerical behaviour in doubles).
 * Not thread-safe: use one instance per channel and call it from a single audio thread.
 * [coefficients] may be replaced at any time to retune live without clearing the state.
 */
public class Biquad(
    public var coefficients: BiquadCoefficients,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    public fun process(input: Double): Double {
        val c = coefficients
        val output = c.b0 * input + z1
        z1 = c.b1 * input - c.a1 * output + z2
        z2 = c.b2 * input - c.a2 * output
        return output
    }

    /**
     * Filters `length` samples in place, starting at [offset] and stepping by [stride]. For interleaved
     * audio with `n` channels, give each channel its own [Biquad] and call with `offset = channel`,
     * `stride = n`.
     */
    public fun process(
        buffer: FloatArray,
        offset: Int = 0,
        length: Int = buffer.size - offset,
        stride: Int = 1,
    ) {
        var index = offset
        repeat(length) {
            buffer[index] = process(buffer[index].toDouble()).toFloat()
            index += stride
        }
    }

    public fun reset() {
        z1 = 0.0
        z2 = 0.0
    }
}

/** Biquads in series, such as a multi-band equalizer or the two K-weighting stages. */
public class BiquadChain(
    stages: List<BiquadCoefficients>,
) {
    private val filters = Array(stages.size) { Biquad(stages[it]) }

    public val size: Int get() = filters.size

    public fun process(input: Double): Double {
        var sample = input
        for (filter in filters) sample = filter.process(sample)
        return sample
    }

    /** Replaces one stage's coefficients, keeping its state. */
    public fun setStage(
        index: Int,
        coefficients: BiquadCoefficients,
    ) {
        filters[index].coefficients = coefficients
    }

    public fun reset() {
        for (filter in filters) filter.reset()
    }

    /** Combined gain in decibels at [frequencyHz]: the sum of the stages' gains. */
    public fun magnitudeDb(
        frequencyHz: Double,
        sampleRate: Double,
    ): Double = filters.sumOf { it.coefficients.magnitudeDb(frequencyHz, sampleRate) }
}
