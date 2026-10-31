package ultra.feature.dsp.processor

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import ultra.core.dsp.dbToAmplitude
import ultra.feature.dsp.dsp.DeckEffects
import ultra.feature.dsp.dsp.Pcm16
import ultra.feature.dsp.dsp.TpdfDither
import ultra.feature.dsp.engine.DeckPlan
import ultra.feature.dsp.engine.DeckRole
import ultra.feature.dsp.engine.DjDeck
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min

/**
 * The audio-chain half of the Smart DJ engine: one instance per audio sink (seam S6 asks for fresh
 * instances every time), registered with the [DjDeck] it was built for.
 *
 * ## Signal path
 * `16-bit PCM in -> Float32 (exact) -> bass swap / echo -> soft ceiling -> TPDF dither -> 16-bit PCM out`
 * All shaping happens in Float32, so intermediate values may exceed full scale without clipping; the
 * soft ceiling brings them back below it before the one and only rounding step.
 *
 * ## Why the output stays 16-bit
 * This was checked against media3 1.10.1 (the version this app ships), not assumed:
 *  - [androidx.media3.exoplayer.audio.DefaultAudioSink] only runs its processor chain, and so this
 *    class, when float output is *off*; with float output on, high-resolution PCM bypasses the whole
 *    chain (upstream's EQ and normalization included).
 *  - Even in the processing path, S6 sits in front of upstream's `SilenceSkippingAudioProcessor`, which
 *    accepts only 16-bit PCM and throws on anything else. A processor that emitted float here would
 *    make the sink's `configure()` throw and playback fail outright.
 * So the audio the processors see, and hand on, is 16-bit. The Float32 guarantee is about the DSP, not
 * the sink's output encoding.
 *
 * ## Safety rules
 *  - Anything it cannot handle (non-16-bit input, more than [MAX_CHANNELS] channels) makes it
 *    *inactive*, so the pipeline skips it, instead of throwing and breaking playback.
 *  - When no transition is shaping the audio the input is copied byte for byte: no conversion, no
 *    dither, no ceiling. Not touching audio is the default; every effect is the exception.
 */
class DjDeckAudioProcessor(
    private val deck: DjDeck,
) : BaseAudioProcessor() {
    private var effects: DeckEffects? = null
    private var channels = 0
    private var sampleRate = 0

    private val work = FloatArray(DeckEffects.CHUNK_FRAMES * MAX_CHANNELS)
    private val dither = TpdfDither()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.channelCount !in 1..MAX_CHANNELS ||
            inputAudioFormat.sampleRate < MIN_SAMPLE_RATE
        ) {
            effects = null
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        effects = DeckEffects(sampleRate, channels)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val fx = effects
        if (fx == null) {
            // Not expected (the pipeline skips inactive processors), but never drop audio.
            out.put(inputBuffer).flip()
            return
        }

        val frameBytes = BYTES_PER_SAMPLE * channels
        val frames = remaining / frameBytes
        val plan = deck.beginBlock(frames, sampleRate)

        if (plan.role == DeckRole.SOLO && fx.isIdle) {
            out.put(inputBuffer)
        } else {
            shape(inputBuffer, out, frames, plan, fx)
            // A partial trailing frame cannot happen with a well-formed stream; keep its bytes anyway.
            if (inputBuffer.hasRemaining()) out.put(inputBuffer)
        }
        out.flip()
    }

    private fun shape(
        input: ByteBuffer,
        out: ByteBuffer,
        frames: Int,
        plan: DeckPlan,
        fx: DeckEffects,
    ) {
        val ceiling = dbToAmplitude(plan.profile.ceilingDb.toDouble()).toFloat()
        val ditherOn = plan.profile.dither
        var guarded = false
        var done = 0
        while (done < frames) {
            val n = min(DeckEffects.CHUNK_FRAMES, frames - done)
            val samples = n * channels

            for (i in 0 until samples) {
                val lo = input.get().toInt() and 0xFF
                val hi = input.get().toInt()
                work[i] = Pcm16.toFloat(((hi shl 8) or lo).toShort().toInt())
            }

            // Progress at the middle of the chunk, interpolated across the block.
            val middle = (done + n / 2f) / frames
            val progress = plan.progressStart + (plan.progressEnd - plan.progressStart) * middle
            val processed = fx.process(work, n, plan.role, progress, plan.profile)

            for (i in 0 until samples) {
                var v = work[i]
                val pcm: Int
                if (processed) {
                    if (abs(v) > ceiling) guarded = true
                    v = Pcm16.softCeiling(v, ceiling)
                    pcm = if (ditherOn) Pcm16.toPcm(v, dither.next()) else Pcm16.toPcm(v)
                } else {
                    pcm = Pcm16.toPcm(v) // exact: the chunk was not touched
                }
                out.put(pcm.toByte())
                out.put((pcm shr 8).toByte())
            }
            done += n
        }
        if (guarded) deck.reportPeakGuard()
    }

    override fun onQueueEndOfStream() {
        deck.onEndOfStream()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // A flush is a seek or a stream start; the filter and delay memory belong to the old position.
        effects?.reset()
    }

    override fun onReset() {
        effects = null
        channels = 0
        sampleRate = 0
        deck.onReset()
    }

    companion object {
        const val MAX_CHANNELS = 8
        private const val MIN_SAMPLE_RATE = 8_000
        private const val BYTES_PER_SAMPLE = 2
    }
}
