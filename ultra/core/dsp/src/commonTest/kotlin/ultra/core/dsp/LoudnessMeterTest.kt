package ultra.core.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cases modelled on EBU Tech 3341 / 3342 ("minimum requirements" for loudness meters): the same kinds of
 * signal (997 Hz tones at known levels, with quiet sections to exercise the gates) and the same
 * +-0.1 LU tolerance.
 */
class LoudnessMeterTest {
    /** Feeds a continuous sine in one-second chunks so tests also exercise state carried across calls. */
    private class Tone(
        private val meter: LoudnessMeter,
        private val frequencyHz: Double = 997.0,
    ) {
        private var frame = 0L

        fun play(
            seconds: Double,
            dbfs: Double,
            channels: Set<Int> = (0 until meter.channels).toSet(),
            chunkFrames: Int = meter.sampleRate,
        ) {
            val amplitude = dbToAmplitude(dbfs)
            var remaining = (seconds * meter.sampleRate).toLong()
            while (remaining > 0) {
                val n = minOf(remaining, chunkFrames.toLong()).toInt()
                val buffer = FloatArray(n * meter.channels)
                for (i in 0 until n) {
                    val value = (amplitude * sin(2.0 * PI * frequencyHz * (frame + i) / meter.sampleRate)).toFloat()
                    for (c in channels) buffer[i * meter.channels + c] = value
                }
                meter.process(buffer)
                frame += n
                remaining -= n
            }
        }
    }

    private fun stereo(rate: Int = 48_000) = LoudnessMeter.stereo(rate)

    @Test
    fun `a stereo tone at -23 dBFS reads -23 LUFS in every window`() {
        val meter = stereo()

        Tone(meter).play(20.0, dbfs = -23.0)

        assertEquals(-23.0, meter.integratedLufs, 0.1)
        assertEquals(-23.0, meter.momentaryLufs, 0.1)
        assertEquals(-23.0, meter.shortTermLufs, 0.1)
    }

    @Test
    fun `a stereo tone at -33 dBFS reads -33 LUFS`() {
        val meter = stereo()

        Tone(meter).play(20.0, dbfs = -33.0)

        assertEquals(-33.0, meter.integratedLufs, 0.1)
    }

    @Test
    fun `the relative gate drops the quiet sections around loud programme`() {
        val meter = stereo()
        val tone = Tone(meter)

        tone.play(10.0, dbfs = -36.0)
        tone.play(60.0, dbfs = -23.0)
        tone.play(10.0, dbfs = -36.0)

        // Ungated, the 80 s mean would read about -24.2 LUFS. The relative gate (10 LU below the
        // abs-gated mean, about -34.2) removes the -36 sections, leaving -23.0.
        assertEquals(-23.0, meter.integratedLufs, 0.1)
    }

    @Test
    fun `a quiet section is kept when it is within 10 LU of the rest`() {
        val meter = stereo()
        val tone = Tone(meter)

        tone.play(20.0, dbfs = -23.0)
        tone.play(20.0, dbfs = -29.0)

        // Equal durations: mean energy = (1 + 10^-0.6) / 2 of the louder level, i.e. about -25.0 LUFS.
        val expected = -23.0 + 10.0 * log10((1.0 + dbToPower(-6.0)) / 2.0)
        assertEquals(expected, meter.integratedLufs, 0.15)
    }

    @Test
    fun `audio entirely below the absolute gate has no integrated loudness`() {
        val meter = stereo()

        Tone(meter).play(20.0, dbfs = -75.0)

        assertEquals(Double.NEGATIVE_INFINITY, meter.integratedLufs)
        assertEquals(-75.0, meter.momentaryLufs, 0.2) // the instantaneous readings are not gated
    }

    @Test
    fun `readings need enough audio before they exist`() {
        val meter = stereo()
        val tone = Tone(meter)

        tone.play(0.3, dbfs = -23.0)
        assertEquals(Double.NEGATIVE_INFINITY, meter.momentaryLufs)
        assertEquals(Double.NEGATIVE_INFINITY, meter.integratedLufs)

        tone.play(0.2, dbfs = -23.0) // 0.5 s in total
        assertTrue(meter.momentaryLufs.isFinite())
        assertEquals(Double.NEGATIVE_INFINITY, meter.shortTermLufs)

        tone.play(3.0, dbfs = -23.0)
        assertTrue(meter.shortTermLufs.isFinite())
    }

    @Test
    fun `the momentary reading follows the signal while the integrated one lags`() {
        val meter = stereo()
        val tone = Tone(meter)

        tone.play(10.0, dbfs = -23.0)
        tone.play(1.0, dbfs = -13.0)

        assertEquals(-13.0, meter.momentaryLufs, 0.1)
        assertTrue(meter.integratedLufs < -20.0)
    }

    @Test
    fun `the result does not depend on how the audio is chunked`() {
        val whole = stereo()
        val pieces = stereo()

        Tone(whole).play(12.0, dbfs = -20.0, chunkFrames = 12 * 48_000)
        Tone(pieces).play(12.0, dbfs = -20.0, chunkFrames = 777) // a size that straddles 100 ms hops

        assertEquals(whole.integratedLufs, pieces.integratedLufs, 1e-9)
        assertEquals(whole.shortTermLufs, pieces.shortTermLufs, 1e-9)
        assertEquals(whole.momentaryLufs, pieces.momentaryLufs, 1e-9)
    }

    @Test
    fun `other sample rates give the same answer`() {
        for (rate in listOf(22_050, 44_100, 96_000)) {
            val meter = stereo(rate)

            Tone(meter).play(15.0, dbfs = -23.0)

            assertEquals(-23.0, meter.integratedLufs, 0.1, "at $rate Hz")
        }
    }

    @Test
    fun `a mono signal counts as a single channel`() {
        val meter = LoudnessMeter.mono(48_000)

        Tone(meter).play(15.0, dbfs = -23.0)

        // One channel carries half the energy of an identical stereo pair: 10 log10(2) = 3.01 LU lower.
        assertEquals(-23.0 - 10.0 * log10(2.0), meter.integratedLufs, 0.1)
    }

    @Test
    fun `surround channels weigh 1·5 dB more and the LFE channel is ignored`() {
        fun measure(channel: Int): Double {
            val meter = LoudnessMeter(48_000, ChannelWeights.Surround51)
            Tone(meter).play(10.0, dbfs = -26.0, channels = setOf(channel))
            return meter.integratedLufs
        }

        val front = measure(0)
        val surround = measure(4)

        assertEquals(10.0 * log10(1.41), surround - front, 0.01)
        assertEquals(Double.NEGATIVE_INFINITY, measure(3))
    }

    @Test
    fun `silence has no loudness and a zero peak`() {
        val meter = stereo()

        meter.process(FloatArray(48_000 * 2 * 5))

        assertEquals(Double.NEGATIVE_INFINITY, meter.momentaryLufs)
        assertEquals(Double.NEGATIVE_INFINITY, meter.shortTermLufs)
        assertEquals(Double.NEGATIVE_INFINITY, meter.integratedLufs)
        assertEquals(0.0, meter.samplePeak)
    }

    @Test
    fun `the sample peak tracks the loudest sample on any channel`() {
        val meter = stereo()

        Tone(meter).play(1.0, dbfs = -6.0, channels = setOf(1))

        assertEquals(dbToAmplitude(-6.0), meter.samplePeak, 0.001)
    }

    @Test
    fun `reset forgets everything`() {
        val meter = stereo()
        Tone(meter).play(10.0, dbfs = -20.0)

        meter.reset()

        assertEquals(Double.NEGATIVE_INFINITY, meter.integratedLufs)
        assertEquals(Double.NEGATIVE_INFINITY, meter.momentaryLufs)
        assertEquals(0.0, meter.samplePeak)
        Tone(meter).play(10.0, dbfs = -30.0)
        assertEquals(-30.0, meter.integratedLufs, 0.1) // and measures cleanly afterwards
    }

    @Test
    fun `invalid configuration and buffers are rejected`() {
        assertFailsWith<IllegalArgumentException> { LoudnessMeter(0, ChannelWeights.Stereo) }
        assertFailsWith<IllegalArgumentException> { LoudnessMeter(48_000, doubleArrayOf()) }
        assertFailsWith<IllegalArgumentException> { LoudnessMeter(48_000, doubleArrayOf(1.0, -1.0)) }
        assertFailsWith<IllegalArgumentException> { stereo().process(FloatArray(10), offset = 0, frames = 6) }
    }

    @Test
    fun `energy and loudness convert both ways`() {
        assertEquals(-0.691, LoudnessMeter.energyToLufs(1.0), 1e-12)
        assertEquals(1.0, LoudnessMeter.lufsToEnergy(LoudnessMeter.energyToLufs(1.0)), 1e-12)
        assertEquals(Double.NEGATIVE_INFINITY, LoudnessMeter.energyToLufs(0.0))
    }
}
