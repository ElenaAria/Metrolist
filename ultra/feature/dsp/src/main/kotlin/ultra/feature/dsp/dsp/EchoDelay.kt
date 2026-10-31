package ultra.feature.dsp.dsp

import ultra.feature.dsp.engine.DjProfile
import kotlin.math.PI
import kotlin.math.exp

/**
 * Feedback delay with a one-pole low-pass in the loop ("damped echo"): each repeat is darker than the
 * last, which is what makes a DJ echo-out sit behind the music instead of fighting it.
 *
 * ```
 *   x ──┬──────────────────────────► + ──► out = x + wet * lp
 *       │                            ▲
 *       └─► + ─► [ delay ] ─► lp ─┬──┘
 *           ▲                     │
 *           └──── feedback ◄──────┘
 * ```
 * Float32 delay line. The loop gain (`feedback`) is capped below 1 by [DjProfile], so repeats always
 * decay. Interleaved layout, one damping state per channel.
 */
internal class EchoDelay(
    private val sampleRate: Int,
    private val channels: Int,
) {
    private val lineFrames = sampleRate * DjProfile.MAX_ECHO_DELAY_MS / 1000 + 1
    private val line = FloatArray(lineFrames * channels)
    private val damp = FloatArray(channels)
    private var writeFrame = 0

    /** Smoothing coefficient of the loop low-pass for a cutoff of [hz]. */
    fun dampingCoefficient(hz: Float): Float = 1f - exp(-2.0 * PI * hz / sampleRate).toFloat()

    /**
     * Adds the echo to [buffer] in place.
     *
     * @param delayFrames loop delay, 1 until [lineFrames]
     * @param dampCoef from [dampingCoefficient]
     * @param wet level of the echo added to the output (the loop itself always runs at [feedback])
     */
    fun process(
        buffer: FloatArray,
        frames: Int,
        delayFrames: Int,
        feedback: Float,
        dampCoef: Float,
        wet: Float,
    ) {
        val delay = delayFrames.coerceIn(1, lineFrames - 1)
        var write = writeFrame
        for (i in 0 until frames) {
            var read = write - delay
            if (read < 0) read += lineFrames
            val readBase = read * channels
            val writeBase = write * channels
            val sampleBase = i * channels
            for (c in 0 until channels) {
                val delayed = line[readBase + c]
                val filtered = damp[c] + dampCoef * (delayed - damp[c])
                damp[c] = filtered
                val x = buffer[sampleBase + c]
                // The repeat is fed back, not added on top of itself, so the level falls by `feedback` each time.
                line[writeBase + c] = x + feedback * filtered
                buffer[sampleBase + c] = x + wet * feedback * filtered
            }
            write++
            if (write == lineFrames) write = 0
        }
        writeFrame = write
    }

    fun clear() {
        line.fill(0f)
        damp.fill(0f)
        writeFrame = 0
    }
}
