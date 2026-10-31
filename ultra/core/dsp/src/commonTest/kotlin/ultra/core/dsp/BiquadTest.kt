package ultra.core.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BiquadTest {
    private val fs = 48_000.0

    @Test
    fun `a peaking filter gains exactly gainDb at its centre and nothing at DC`() {
        for (frequency in listOf(60.0, 1000.0, 4000.0, 12_000.0)) {
            for (q in listOf(0.5, 1.0, 4.0)) {
                for (gain in listOf(-12.0, -3.0, 6.0, 18.0)) {
                    val filter = BiquadCoefficients.peaking(fs, frequency, q, gain)

                    assertEquals(gain, filter.magnitudeDb(frequency, fs), 1e-9, "f=$frequency q=$q gain=$gain")
                    assertEquals(0.0, filter.magnitudeDb(0.0, fs), 1e-9)
                }
            }
        }
    }

    @Test
    fun `a low shelf applies the gain at DC and none at Nyquist`() {
        val filter = BiquadCoefficients.lowShelf(fs, 200.0, 0.707, gainDb = 9.0)

        assertEquals(9.0, filter.magnitudeDb(0.0, fs), 1e-9)
        assertEquals(0.0, filter.magnitudeDb(fs / 2, fs), 1e-9)
        assertEquals(4.5, filter.magnitudeDb(200.0, fs), 0.01) // the corner sits halfway
    }

    @Test
    fun `a high shelf applies the gain at Nyquist and none at DC`() {
        val filter = BiquadCoefficients.highShelf(fs, 8000.0, 0.707, gainDb = -6.0)

        assertEquals(-6.0, filter.magnitudeDb(fs / 2, fs), 1e-9)
        assertEquals(0.0, filter.magnitudeDb(0.0, fs), 1e-9)
        assertEquals(-3.0, filter.magnitudeDb(8000.0, fs), 0.01)
    }

    @Test
    fun `a butterworth low pass is 3 dB down at the cutoff and unity at DC`() {
        val filter = BiquadCoefficients.lowPass(fs, 1000.0)

        assertEquals(0.0, filter.magnitudeDb(0.0, fs), 1e-9)
        assertEquals(-10.0 * log10(2.0), filter.magnitudeDb(1000.0, fs), 1e-9)
        assertTrue(filter.magnitudeDb(8000.0, fs) < -30.0)
    }

    @Test
    fun `a butterworth high pass is 3 dB down at the cutoff and unity at Nyquist`() {
        val filter = BiquadCoefficients.highPass(fs, 1000.0)

        assertEquals(0.0, filter.magnitudeDb(fs / 2, fs), 1e-9)
        assertEquals(-10.0 * log10(2.0), filter.magnitudeDb(1000.0, fs), 1e-9)
        assertTrue(filter.magnitudeDb(0.0, fs) < -200.0, "DC must be blocked completely")
    }

    @Test
    fun `a notch removes its centre frequency and leaves the rest`() {
        val filter = BiquadCoefficients.notch(fs, 1000.0, q = 10.0)

        assertTrue(filter.magnitudeDb(1000.0, fs) < -200.0)
        assertEquals(0.0, filter.magnitudeDb(100.0, fs), 0.05)
        assertEquals(0.0, filter.magnitudeDb(10_000.0, fs), 0.05)
    }

    @Test
    fun `every design across a wide parameter sweep is stable`() {
        for (rate in listOf(8_000.0, 44_100.0, 48_000.0, 192_000.0)) {
            for (fraction in listOf(0.0005, 0.01, 0.1, 0.25, 0.45, 0.499)) {
                val frequency = rate * fraction
                for (q in listOf(0.1, 0.707, 1.0, 10.0, 50.0)) {
                    val filters =
                        listOf(
                            BiquadCoefficients.lowPass(rate, frequency, q),
                            BiquadCoefficients.highPass(rate, frequency, q),
                            BiquadCoefficients.notch(rate, frequency, q),
                        ) +
                            listOf(-24.0, 0.0, 24.0).flatMap { gain ->
                                listOf(
                                    BiquadCoefficients.peaking(rate, frequency, q, gain),
                                    BiquadCoefficients.lowShelf(rate, frequency, q, gain),
                                    BiquadCoefficients.highShelf(rate, frequency, q, gain),
                                )
                            }
                    assertTrue(filters.all { it.isStable }, "unstable at fs=$rate f=$frequency q=$q")
                }
            }
        }
    }

    @Test
    fun `filtering a sine matches the analytic gain in the time domain`() {
        val gainDb = 6.0
        val filter = Biquad(BiquadCoefficients.peaking(fs, 1000.0, 1.0, gainDb))
        val samples = 48_000
        val input = DoubleArray(samples) { sin(2.0 * PI * 1000.0 * it / fs) }

        val output = DoubleArray(samples) { filter.process(input[it]) }

        // Skip the first half second of transient; compare RMS over 500 whole cycles.
        fun rms(x: DoubleArray) = sqrt((24_000 until samples).sumOf { x[it] * x[it] } / (samples - 24_000))
        assertEquals(gainDb, 20.0 * log10(rms(output) / rms(input)), 0.001)
    }

    @Test
    fun `the identity filter passes samples through untouched`() {
        val filter = Biquad(BiquadCoefficients.Identity)

        assertEquals(0.25, filter.process(0.25))
        assertEquals(-1.0, filter.process(-1.0))
    }

    @Test
    fun `reset clears the filter memory`() {
        val filter = Biquad(BiquadCoefficients.lowPass(fs, 500.0))
        filter.process(1.0) // an impulse leaves energy ringing in the state

        filter.reset()

        assertEquals(0.0, filter.process(0.0))
    }

    @Test
    fun `in place processing with a stride filters each interleaved channel independently`() {
        val left = BiquadCoefficients.lowPass(fs, 1000.0)
        val right = BiquadCoefficients.highPass(fs, 1000.0)
        val frames = 256
        val interleaved = FloatArray(frames * 2) { (sin(2.0 * PI * 440.0 * (it / 2) / fs) * if (it % 2 == 0) 1.0 else 0.5).toFloat() }
        val expected = interleaved.copyOf()

        val leftFilter = Biquad(left)
        val rightFilter = Biquad(right)
        leftFilter.process(interleaved, offset = 0, length = frames, stride = 2)
        rightFilter.process(interleaved, offset = 1, length = frames, stride = 2)

        val referenceLeft = Biquad(left)
        val referenceRight = Biquad(right)
        for (n in 0 until frames) {
            expected[2 * n] = referenceLeft.process(expected[2 * n].toDouble()).toFloat()
            expected[2 * n + 1] = referenceRight.process(expected[2 * n + 1].toDouble()).toFloat()
        }
        assertContentEquals(expected, interleaved)
    }

    @Test
    fun `a chain applies its stages in series and sums their gains`() {
        val stages = listOf(BiquadCoefficients.peaking(fs, 1000.0, 1.0, 6.0), BiquadCoefficients.peaking(fs, 1000.0, 1.0, -2.0))
        val chain = BiquadChain(stages)

        assertEquals(2, chain.size)
        assertEquals(4.0, chain.magnitudeDb(1000.0, fs), 1e-9)

        val reference = stages.map { Biquad(it) }
        val impulse = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
        for (x in impulse) assertEquals(reference[1].process(reference[0].process(x)), chain.process(x), 1e-15)
    }

    @Test
    fun `designs reject frequencies outside the audible band and silly Q`() {
        assertFailsWith<IllegalArgumentException> { BiquadCoefficients.lowPass(fs, fs / 2) }
        assertFailsWith<IllegalArgumentException> { BiquadCoefficients.lowPass(fs, 0.0) }
        assertFailsWith<IllegalArgumentException> { BiquadCoefficients.peaking(fs, 1000.0, 0.0, 3.0) }
        assertFailsWith<IllegalArgumentException> { BiquadCoefficients.peaking(0.0, 1000.0, 1.0, 3.0) }
    }

    @Test
    fun `decibel helpers round trip and treat silence as negative infinity`() {
        assertEquals(1.0, dbToAmplitude(0.0))
        assertEquals(2.0, dbToAmplitude(amplitudeToDb(2.0)), 1e-12)
        assertEquals(6.0206, amplitudeToDb(2.0), 1e-4)
        assertEquals(3.0103, powerToDb(2.0), 1e-4)
        assertEquals(2.0, dbToPower(powerToDb(2.0)), 1e-12)
        assertEquals(Double.NEGATIVE_INFINITY, amplitudeToDb(0.0))
        assertEquals(Double.NEGATIVE_INFINITY, powerToDb(-1.0))
    }
}
