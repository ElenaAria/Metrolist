package ultra.core.dsp

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KWeightingTest {
    // The coefficient table published in ITU-R BS.1770-4 (Annex 1) for 48 kHz.
    @Test
    fun `at 48 kHz the pre-filter matches the published ITU coefficients`() {
        val stage1 = KWeighting.preFilter(48_000.0)

        assertEquals(1.53512485958697, stage1.b0, 1e-8)
        assertEquals(-2.69169618940638, stage1.b1, 1e-8)
        assertEquals(1.19839281085285, stage1.b2, 1e-8)
        assertEquals(-1.69065929318241, stage1.a1, 1e-8)
        assertEquals(0.73248077421585, stage1.a2, 1e-8)
    }

    @Test
    fun `at 48 kHz the RLB high-pass matches the published ITU coefficients`() {
        val stage2 = KWeighting.highPass(48_000.0)

        assertEquals(1.0, stage2.b0)
        assertEquals(-2.0, stage2.b1)
        assertEquals(1.0, stage2.b2)
        assertEquals(-1.99004745483398, stage2.a1, 1e-8)
        assertEquals(0.99007225036621, stage2.a2, 1e-8)
    }

    @Test
    fun `the filters are stable at every common sample rate`() {
        for (rate in listOf(8_000.0, 11_025.0, 16_000.0, 22_050.0, 32_000.0, 44_100.0, 48_000.0, 88_200.0, 96_000.0, 192_000.0)) {
            assertTrue(KWeighting.stages(rate).all { it.isStable }, "unstable at $rate Hz")
        }
    }

    @Test
    fun `the weighting curve has the shape the standard describes`() {
        for (rate in listOf(44_100.0, 48_000.0, 96_000.0)) {
            val curve = BiquadChain(KWeighting.stages(rate))

            // About +0.691 dB at 997 Hz: this is exactly what the -0.691 offset in the LUFS formula cancels.
            // Exact at 48 kHz (see the coefficient tests); at other rates the bilinear mapping of the
            // shelf drifts a little (-0.017 dB at 96 kHz), hence 0.025 rather than a tighter tolerance.
            assertEquals(0.691, curve.magnitudeDb(997.0, rate), 0.025, "997 Hz at $rate")
            // The head-shadow shelf: roughly +4 dB through the top of the band.
            assertEquals(4.0, curve.magnitudeDb(10_000.0, rate), 0.35, "10 kHz at $rate")
            // The RLB high-pass is about 6 dB down at its 38 Hz corner and keeps falling below.
            assertEquals(-6.0, curve.magnitudeDb(38.0, rate), 1.0, "38 Hz at $rate")
        }
    }

    @Test
    fun `the RLB stage follows the analogue second-order high-pass it was derived from`() {
        // |H(jw)|^2 = r^4 / ((1 - r^2)^2 + (r / Q)^2) with r = f / f0: computed here independently of the
        // bilinear-transform code under test. 10 Hz comes out near -23.8 dB, 38.1 Hz near -6 dB.
        val f0 = 38.13547087602444
        val q = 0.5003270373238773

        fun analogDb(frequencyHz: Double): Double {
            val r = frequencyHz / f0
            return powerToDb(r.pow(4) / ((1.0 - r * r).pow(2) + (r / q).pow(2)))
        }

        for (rate in listOf(48_000.0, 96_000.0)) {
            val stage = KWeighting.highPass(rate)
            for (frequency in listOf(5.0, 10.0, 20.0, f0, 100.0)) {
                // Frequency warping from the bilinear transform is far below 0.05 dB down here.
                assertEquals(analogDb(frequency), stage.magnitudeDb(frequency, rate), 0.05, "$frequency Hz at $rate")
            }
        }
        assertEquals(-23.8, analogDb(10.0), 0.1)
    }
}
