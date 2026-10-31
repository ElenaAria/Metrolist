package ultra.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FftTest {
    @Test
    fun `size must be a power of two`() {
        assertFailsWith<IllegalArgumentException> { Fft(0) }
        assertFailsWith<IllegalArgumentException> { Fft(1) }
        assertFailsWith<IllegalArgumentException> { Fft(3) }
        assertFailsWith<IllegalArgumentException> { Fft(1000) }
        assertEquals(2, Fft(2).size)
        assertEquals(4096, Fft(4096).size)
    }

    @Test
    fun `an impulse has a flat spectrum`() {
        val fft = Fft(64)
        val re = DoubleArray(64).also { it[0] = 1.0 }
        val im = DoubleArray(64)

        fft.forward(re, im)

        for (k in 0 until 64) {
            assertEquals(1.0, re[k], 1e-12)
            assertEquals(0.0, im[k], 1e-12)
        }
    }

    @Test
    fun `it agrees with a naive DFT on random input`() {
        val n = 32
        val random = Random(5)
        val x = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val y = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val re = x.copyOf()
        val im = y.copyOf()

        Fft(n).forward(re, im)

        for (k in 0 until n) {
            var expectedRe = 0.0
            var expectedIm = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * PI * k * t / n
                expectedRe += x[t] * cos(angle) - y[t] * sin(angle)
                expectedIm += x[t] * sin(angle) + y[t] * cos(angle)
            }
            assertEquals(expectedRe, re[k], 1e-9, "re[$k]")
            assertEquals(expectedIm, im[k], 1e-9, "im[$k]")
        }
    }

    @Test
    fun `forward then inverse restores the signal`() {
        val n = 1024
        val random = Random(9)
        val x = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val re = x.copyOf()
        val im = DoubleArray(n)
        val fft = Fft(n)

        fft.forward(re, im)
        fft.inverse(re, im)

        for (i in 0 until n) {
            assertEquals(x[i], re[i], 1e-12)
            assertEquals(0.0, im[i], 1e-12)
        }
    }

    @Test
    fun `Parseval holds, so energy is preserved up to the 1 over N factor`() {
        val n = 512
        val random = Random(11)
        val x = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val re = x.copyOf()
        val im = DoubleArray(n)

        Fft(n).forward(re, im)

        val timeEnergy = x.sumOf { it * it }
        val frequencyEnergy = (0 until n).sumOf { re[it] * re[it] + im[it] * im[it] } / n
        assertEquals(timeEnergy, frequencyEnergy, 1e-9)
    }

    @Test
    fun `a bin-centred sine of amplitude 0·5 reads 0·5 at its bin and nothing elsewhere`() {
        val n = 1024
        val bin = 64
        val samples = DoubleArray(n) { 0.5 * sin(2.0 * PI * bin * it / n) }

        val spectrum = Fft(n).magnitudeSpectrum(samples)

        assertEquals(n / 2 + 1, spectrum.size)
        assertEquals(0.5, spectrum[bin], 1e-9)
        for (k in spectrum.indices) if (k != bin) assertTrue(spectrum[k] < 1e-9, "leak into bin $k: ${spectrum[k]}")
    }

    @Test
    fun `windowing keeps the amplitude calibrated`() {
        val n = 2048
        val bin = 100
        val samples = DoubleArray(n) { sin(2.0 * PI * bin * it / n) }
        val fft = Fft(n)

        for (type in WindowType.entries) {
            val spectrum = fft.magnitudeSpectrum(samples, window(type, n))
            assertEquals(1.0, spectrum[bin], 1e-9, "$type")
        }
    }

    @Test
    fun `a Hann window suppresses leakage far from an off-bin sine`() {
        val n = 2048
        val samples = DoubleArray(n) { sin(2.0 * PI * 100.5 * it / n) } // halfway between two bins
        val fft = Fft(n)

        val rectangular = fft.magnitudeSpectrum(samples)
        val hann = fft.magnitudeSpectrum(samples, window(WindowType.Hann, n))

        assertTrue(hann[600] < rectangular[600] / 100.0, "Hann should leak at least 40 dB less far away")
    }

    @Test
    fun `DC and Nyquist are not doubled`() {
        val n = 16
        val fft = Fft(n)

        assertEquals(0.25, fft.magnitudeSpectrum(DoubleArray(n) { 0.25 })[0], 1e-12)
        val alternating = DoubleArray(n) { if (it % 2 == 0) 1.0 else -1.0 }
        assertEquals(1.0, fft.magnitudeSpectrum(alternating)[n / 2], 1e-12)
    }

    @Test
    fun `bin frequencies span DC to Nyquist`() {
        assertEquals(0.0, binFrequencyHz(0, 1024, 48_000.0))
        assertEquals(24_000.0, binFrequencyHz(512, 1024, 48_000.0))
        assertEquals(3_000.0, binFrequencyHz(64, 1024, 48_000.0))
    }

    @Test
    fun `window shapes match their definitions`() {
        val hann = window(WindowType.Hann, 8)
        assertEquals(0.0, hann[0], 1e-15)
        assertEquals(1.0, hann[4], 1e-15)
        assertEquals(hann[1], hann[7], 1e-15) // periodic: symmetric about N/2

        val symmetric = window(WindowType.Hann, 9, periodic = false)
        assertEquals(0.0, symmetric.first(), 1e-15)
        assertEquals(0.0, symmetric.last(), 1e-15)
        assertEquals(1.0, symmetric[4], 1e-15)

        assertEquals(0.08, window(WindowType.Hamming, 16)[0], 1e-12)
        assertEquals(0.0, window(WindowType.Blackman, 16)[0], 1e-12)
        assertTrue(window(WindowType.Rectangular, 5).all { it == 1.0 })
        assertEquals(listOf(1.0), window(WindowType.Hann, 1).toList())
        assertFailsWith<IllegalArgumentException> { window(WindowType.Hann, 0) }
    }

    @Test
    fun `array length mismatches are rejected`() {
        val fft = Fft(8)

        assertFailsWith<IllegalArgumentException> { fft.forward(DoubleArray(8), DoubleArray(4)) }
        assertFailsWith<IllegalArgumentException> { fft.magnitudeSpectrum(DoubleArray(7)) }
        assertTrue(abs(fft.magnitudeSpectrum(DoubleArray(8)).sum()) == 0.0)
    }
}
