package ultra.feature.dsp.processor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ultra.feature.dsp.dsp.Pcm16
import ultra.feature.dsp.engine.DjEngine
import ultra.feature.dsp.engine.DjProfiles
import ultra.feature.dsp.engine.DjSnapshot
import ultra.feature.dsp.engine.ProfileId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/**
 * Drives the real media3 pipeline (`AudioProcessingPipeline`) with the same processor order upstream's
 * audio sink uses: our processor, then `SilenceSkippingAudioProcessor`, then `SonicAudioProcessor`.
 * If our output encoding ever stops being 16-bit, silence-skipping throws at configure time and these
 * tests fail.
 */
class DjDeckAudioProcessorTest {
    private val rate = 48_000
    private val channels = 2
    private val frameBytes = channels * 2

    private var active = true

    // Fresh per test, and with windows longer than any single feed here, so one test's audio never makes
    // another test's deck look like it went silent. The app's defaults are far shorter; see DjEngine.
    private lateinit var engine: DjEngine

    @Before
    fun setUp() {
        engine = DjEngine(snapshot = { DjSnapshot(active, DjProfiles.forId(ProfileId.WIRED), transitionMs = 2_000) }, staleMs = 30_000, outgoingGoneMs = 30_000)
    }

    /** The chain exactly as DefaultAudioSink assembles it around seam S6. */
    private fun chain(processor: DjDeckAudioProcessor): AudioProcessingPipeline =
        AudioProcessingPipeline(
            ImmutableList.of(processor, SilenceSkippingAudioProcessor(), SonicAudioProcessor()),
        ).also { it.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)); it.flush() }

    private fun tone(
        frames: Int,
        hz: Double,
        amplitude: Float,
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(frames * frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = (sin(2.0 * Math.PI * hz * i / rate) * amplitude).toFloat()
            val pcm = Pcm16.toPcm(s)
            repeat(channels) {
                buf.put(pcm.toByte())
                buf.put((pcm shr 8).toByte())
            }
        }
        buf.flip()
        return buf
    }

    private fun pump(
        pipeline: AudioProcessingPipeline,
        input: ByteBuffer,
    ): ByteArray {
        val out = ArrayList<Byte>()
        pipeline.queueInput(input)
        while (pipeline.output.hasRemaining()) pipeline.output.let { b -> while (b.hasRemaining()) out += b.get() }
        return out.toByteArray()
    }

    private fun samples(bytes: ByteArray): IntArray {
        val n = bytes.size / 2
        return IntArray(n) { i -> ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort().toInt() }
    }

    @Test
    fun theChainConfiguresAndStaysAt16Bit() {
        val pipeline = chain(DjDeckAudioProcessor(engine.newDeck()))
        assertTrue(pipeline.isOperational)
        assertEquals(C.ENCODING_PCM_16BIT, pipeline.outputAudioFormat.encoding)
        assertEquals(channels, pipeline.outputAudioFormat.channelCount)
        assertEquals(rate, pipeline.outputAudioFormat.sampleRate)
    }

    @Test
    fun aSoloDeckPassesAudioThroughByteForByte() {
        val pipeline = chain(DjDeckAudioProcessor(engine.newDeck()))
        val input = tone(frames = 4_800, hz = 440.0, amplitude = 0.5f)
        val original = input.duplicate()
        val out = pump(pipeline, input)

        val expected = ByteArray(original.remaining())
        original.get(expected)
        assertTrue("solo output differs from input", out.contentEquals(expected))
    }

    @Test
    fun aTransitionShapesBothDecksAndStaysInsideFullScale() {
        val outgoing = chain(DjDeckAudioProcessor(engine.newDeck()))
        // 4 s of audio makes this deck old enough to be the outgoing one.
        pump(outgoing, tone(frames = rate * 4, hz = 80.0, amplitude = 0.8f))

        val incoming = chain(DjDeckAudioProcessor(engine.newDeck()))
        // The swap happens across the middle of the transition. Measure in the second half, where the
        // outgoing deck has handed its bass over and the incoming deck has taken it. Both decks are fed
        // the same amount so they sit at the same progress.
        // A short block opens the transition; everything after it progresses both decks in step.
        pump(incoming, tone(frames = rate / 50, hz = 80.0, amplitude = 0.8f))
        val toLate = rate * 7 / 5
        pump(outgoing, tone(frames = toLate, hz = 80.0, amplitude = 0.8f))
        pump(incoming, tone(frames = toLate, hz = 80.0, amplitude = 0.8f))
        val outSamples = samples(pump(outgoing, tone(frames = rate / 5, hz = 80.0, amplitude = 0.8f)))
        val inSamples = samples(pump(incoming, tone(frames = rate / 5, hz = 80.0, amplitude = 0.8f)))

        assertTrue(engine.stats().mixing)
        assertTrue(outSamples.all { abs(it) <= 32767 })
        assertTrue(inSamples.all { abs(it) <= 32767 })

        fun rms(s: IntArray): Double {
            var sum = 0.0
            for (v in s) sum += v.toDouble() * v
            return kotlin.math.sqrt(sum / s.size)
        }
        val outLevel = rms(outSamples)
        val inLevel = rms(inSamples)
        assertTrue("outgoing bass not reduced ($outLevel vs $inLevel)", outLevel < inLevel / 3)
    }

    @Test
    fun theOutgoingDeckProducesAnEchoTail() {
        val outgoing = chain(DjDeckAudioProcessor(engine.newDeck()))
        pump(outgoing, tone(frames = rate * 4, hz = 300.0, amplitude = 0.6f))
        val incoming = chain(DjDeckAudioProcessor(engine.newDeck()))
        // The echo opens over the first half of the transition. Stop inside the second half, so the
        // measurement happens while the transition is still shaping the audio.
        val leadIn = rate * 6 / 5
        pump(outgoing, tone(frames = leadIn, hz = 300.0, amplitude = 0.6f))
        pump(incoming, tone(frames = leadIn, hz = 440.0, amplitude = 0.5f))
        val block = rate / 10
        pump(outgoing, tone(frames = block, hz = 300.0, amplitude = 0.9f))
        pump(incoming, tone(frames = block, hz = 440.0, amplitude = 0.5f)) // keeps the transition alive
        val tail = samples(pump(outgoing, tone(frames = rate / 2, hz = 300.0, amplitude = 0f)))

        // The high-pass keeps ringing for a few dozen milliseconds after the tone stops, so the echo
        // (375 ms, profile default) is measured after that ring has died, and before the repeat.
        val ringFrames = rate / 20
        val afterRing = tail.drop(ringFrames * channels).take((block - ringFrames) * channels).maxOf { abs(it) }
        val echo = tail.drop(block * channels).maxOf { abs(it) }
        assertTrue("expected near-silence between the tone and its repeat, got $afterRing", afterRing < 400)
        assertTrue("expected an echo tail, peak was $echo", echo > 1000)
    }

    @Test
    fun deactivatingReturnsToABitExactPassThrough() {
        val pipeline = chain(DjDeckAudioProcessor(engine.newDeck()))
        pump(pipeline, tone(frames = rate * 4, hz = 200.0, amplitude = 0.5f))
        val incoming = chain(DjDeckAudioProcessor(engine.newDeck()))
        pump(incoming, tone(frames = rate / 10, hz = 200.0, amplitude = 0.5f))
        assertTrue(engine.stats().mixing)

        active = false
        // A few blocks for the 80 ms release ramp to finish, then the output must be exact again.
        pump(pipeline, tone(frames = rate / 5, hz = 440.0, amplitude = 0.4f))
        val input = tone(frames = rate / 5, hz = 440.0, amplitude = 0.4f)
        val expected = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        assertTrue(pump(pipeline, input).contentEquals(expected))
        assertFalse(engine.stats().mixing)
    }

    @Test
    fun unsupportedFormatsMakeTheProcessorInactiveInsteadOfThrowing() {
        val processor = DjDeckAudioProcessor(engine.newDeck())
        val pipeline = AudioProcessingPipeline(ImmutableList.of(processor))
        pipeline.configure(AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT))
        pipeline.flush()
        assertFalse("a float input must be refused, not processed", pipeline.isOperational)

        val tooManyChannels = AudioProcessingPipeline(ImmutableList.of(DjDeckAudioProcessor(engine.newDeck())))
        tooManyChannels.configure(AudioFormat(rate, DjDeckAudioProcessor.MAX_CHANNELS + 1, C.ENCODING_PCM_16BIT))
        tooManyChannels.flush()
        assertFalse(tooManyChannels.isOperational)
    }

    @Test
    fun aHotSignalComesBackInsideFullScale() {
        val outgoing = chain(DjDeckAudioProcessor(engine.newDeck()))
        pump(outgoing, tone(frames = rate * 4, hz = 100.0, amplitude = 0.99f))
        val incoming = chain(DjDeckAudioProcessor(engine.newDeck()))
        pump(outgoing, tone(frames = rate * 4 / 5, hz = 100.0, amplitude = 0.99f))
        pump(incoming, tone(frames = rate * 4 / 5, hz = 100.0, amplitude = 0.99f))
        val out = samples(pump(outgoing, tone(frames = rate / 5, hz = 100.0, amplitude = 0.99f)))
        assertTrue(engine.stats().mixing)
        assertTrue(out.all { abs(it) <= 32767 })
        assertTrue("soft ceiling should have engaged", engine.stats().peakGuardBlocks > 0)
    }
}
