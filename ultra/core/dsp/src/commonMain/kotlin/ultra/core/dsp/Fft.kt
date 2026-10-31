package ultra.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT of a fixed power-of-two [size]. The twiddle factors and the
 * bit-reversal table are computed once, so [forward] and [inverse] allocate nothing and one instance
 * can serve a whole analyzer. Not thread-safe only in that callers must not share the arrays they pass.
 */
public class Fft(
    public val size: Int,
) {
    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two >= 2: $size" }
    }

    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }
    private val bitReversed =
        IntArray(size).also { table ->
            val bits = size.countTrailingZeroBits()
            for (i in 0 until size) {
                var reversed = 0
                for (b in 0 until bits) if (i and (1 shl b) != 0) reversed = reversed or (1 shl (bits - 1 - b))
                table[i] = reversed
            }
        }

    /** `X[k] = sum x[n] e^(-2 pi i k n / N)`, unscaled. [re] and [im] are overwritten with the result. */
    public fun forward(
        re: DoubleArray,
        im: DoubleArray,
    ) {
        transform(re, im, inverse = false)
    }

    /** The inverse transform, scaled by `1/N` so that `inverse(forward(x)) == x`. */
    public fun inverse(
        re: DoubleArray,
        im: DoubleArray,
    ) {
        transform(re, im, inverse = true)
        val scale = 1.0 / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(
        re: DoubleArray,
        im: DoubleArray,
        inverse: Boolean,
    ) {
        require(re.size == size && im.size == size) { "arrays must have exactly $size elements" }
        for (i in 0 until size) {
            val j = bitReversed[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = sign * sinTable[k * step]
                    val top = start + k
                    val bottom = top + half
                    val vr = re[bottom] * wr - im[bottom] * wi
                    val vi = re[bottom] * wi + im[bottom] * wr
                    re[bottom] = re[top] - vr
                    im[bottom] = im[top] - vi
                    re[top] += vr
                    im[top] += vi
                }
                start += length
            }
            length *= 2
        }
    }

    /**
     * Amplitude spectrum of a real signal: `size / 2 + 1` bins from DC to Nyquist, scaled so that a sine
     * of amplitude 1 sitting on a bin centre reads 1.0 whatever the [window] (the window's coherent gain
     * is divided out). [samples] must hold exactly [size] values.
     */
    public fun magnitudeSpectrum(
        samples: DoubleArray,
        window: DoubleArray? = null,
    ): DoubleArray {
        require(samples.size == size) { "expected $size samples, got ${samples.size}" }
        require(window == null || window.size == size) { "window must have $size elements" }
        val re = DoubleArray(size) { samples[it] * (window?.get(it) ?: 1.0) }
        val im = DoubleArray(size)
        forward(re, im)
        val windowSum = window?.sum() ?: size.toDouble()
        return DoubleArray(size / 2 + 1) { bin ->
            val magnitude = hypot(re[bin], im[bin]) / windowSum
            // Real input splits a sine's energy between +f and -f, except at DC and Nyquist.
            if (bin == 0 || bin == size / 2) magnitude else 2.0 * magnitude
        }
    }
}

/** The centre frequency of FFT [bin] for an FFT of [fftSize] points at [sampleRate]. */
public fun binFrequencyHz(
    bin: Int,
    fftSize: Int,
    sampleRate: Double,
): Double = bin * sampleRate / fftSize
