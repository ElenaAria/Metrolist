package ultra.core.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** Per-channel weights `G` of ITU-R BS.1770-4 for common layouts. The LFE channel is excluded (weight 0). */
public object ChannelWeights {
    public val Mono: DoubleArray get() = doubleArrayOf(1.0)
    public val Stereo: DoubleArray get() = doubleArrayOf(1.0, 1.0)

    /** L, R, C, LFE, Ls, Rs. The surround channels get +1.5 dB (1.41). */
    public val Surround51: DoubleArray get() = doubleArrayOf(1.0, 1.0, 1.0, 0.0, 1.41, 1.41)
}

/**
 * Loudness measurement per ITU-R BS.1770-4 and EBU R128, fed with interleaved float PCM.
 *
 * Audio is K-weighted, squared, and averaged over 100 ms sub-blocks. A *momentary* reading is the last
 * 400 ms (4 sub-blocks), a *short-term* reading the last 3 s (30 sub-blocks), and the *integrated*
 * reading is the gated mean over every 400 ms block with 75 % overlap: blocks quieter than -70 LUFS are
 * dropped, then blocks more than 10 LU below the mean of the rest.
 *
 * Readings are `Double.NEGATIVE_INFINITY` until enough audio has been seen (or when it is all silence).
 * Not thread-safe; feed it from one thread. Memory grows by one double per 100 ms of integrated audio.
 */
public class LoudnessMeter(
    public val sampleRate: Int,
    channelWeights: DoubleArray,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channelWeights.isNotEmpty()) { "at least one channel is required" }
        require(channelWeights.all { it >= 0.0 }) { "channel weights must not be negative" }
    }

    public val channels: Int = channelWeights.size

    private val weights = channelWeights.copyOf()
    private val hopFrames = (sampleRate + 5) / 10 // frames in 100 ms, rounded
    private val filters = Array(channels) { BiquadChain(KWeighting.stages(sampleRate.toDouble())) }
    private val sumSquares = DoubleArray(channels)
    private val peaks = DoubleArray(channels)
    private var framesInHop = 0

    private val recent = DoubleArray(SHORT_TERM_HOPS) // weighted mean square of the last 100 ms hops, as a ring
    private var recentNext = 0
    private var recentCount = 0
    private var blocks = DoubleArray(INITIAL_BLOCK_CAPACITY) // energy of each 400 ms gating block
    private var blockCount = 0

    /** Loudness of the last 400 ms. */
    public val momentaryLufs: Double
        get() = if (recentCount >= MOMENTARY_HOPS) energyToLufs(meanOfLast(MOMENTARY_HOPS)) else Double.NEGATIVE_INFINITY

    /** Loudness of the last 3 s. */
    public val shortTermLufs: Double
        get() = if (recentCount >= SHORT_TERM_HOPS) energyToLufs(meanOfLast(SHORT_TERM_HOPS)) else Double.NEGATIVE_INFINITY

    /** Gated loudness of everything since construction or the last [reset]. */
    public val integratedLufs: Double
        get() {
            val absoluteGate = lufsToEnergy(ABSOLUTE_GATE_LUFS)
            var sum = 0.0
            var count = 0
            for (i in 0 until blockCount) {
                if (blocks[i] > absoluteGate) {
                    sum += blocks[i]
                    count++
                }
            }
            if (count == 0) return Double.NEGATIVE_INFINITY
            val relativeGate = lufsToEnergy(energyToLufs(sum / count) + RELATIVE_GATE_LU)

            var gatedSum = 0.0
            var gatedCount = 0
            for (i in 0 until blockCount) {
                if (blocks[i] > absoluteGate && blocks[i] > relativeGate) {
                    gatedSum += blocks[i]
                    gatedCount++
                }
            }
            return if (gatedCount == 0) Double.NEGATIVE_INFINITY else energyToLufs(gatedSum / gatedCount)
        }

    /** Largest absolute sample seen on any channel (linear, 1.0 is full scale). */
    public val samplePeak: Double get() = peaks.max()

    /**
     * Measures [frames] frames of interleaved audio starting at [offset]. Chunk boundaries do not matter:
     * feeding a signal in pieces gives the same readings as feeding it at once.
     */
    public fun process(
        interleaved: FloatArray,
        offset: Int = 0,
        frames: Int = (interleaved.size - offset) / channels,
    ) {
        require(offset >= 0 && frames >= 0 && offset + frames.toLong() * channels <= interleaved.size) {
            "range of $frames frames at offset $offset does not fit a buffer of ${interleaved.size} samples"
        }
        var position = offset
        repeat(frames) {
            for (channel in 0 until channels) {
                val sample = interleaved[position++].toDouble()
                val magnitude = abs(sample)
                if (magnitude > peaks[channel]) peaks[channel] = magnitude
                val weighted = filters[channel].process(sample)
                sumSquares[channel] += weighted * weighted
            }
            if (++framesInHop == hopFrames) completeHop()
        }
    }

    public fun reset() {
        filters.forEach { it.reset() }
        sumSquares.fill(0.0)
        peaks.fill(0.0)
        framesInHop = 0
        recentNext = 0
        recentCount = 0
        blockCount = 0
    }

    private fun completeHop() {
        var energy = 0.0
        for (channel in 0 until channels) energy += weights[channel] * sumSquares[channel] / hopFrames
        sumSquares.fill(0.0)
        framesInHop = 0

        recent[recentNext] = energy
        recentNext = (recentNext + 1) % SHORT_TERM_HOPS
        if (recentCount < SHORT_TERM_HOPS) recentCount++

        if (recentCount >= MOMENTARY_HOPS) {
            if (blockCount == blocks.size) blocks = blocks.copyOf(blocks.size * 2)
            blocks[blockCount++] = meanOfLast(MOMENTARY_HOPS)
        }
    }

    private fun meanOfLast(hops: Int): Double {
        var sum = 0.0
        for (i in 1..hops) sum += recent[(recentNext - i + SHORT_TERM_HOPS) % SHORT_TERM_HOPS]
        return sum / hops
    }

    public companion object {
        public const val ABSOLUTE_GATE_LUFS: Double = -70.0
        public const val RELATIVE_GATE_LU: Double = -10.0

        private const val MOMENTARY_HOPS = 4 // 400 ms
        private const val SHORT_TERM_HOPS = 30 // 3 s
        private const val INITIAL_BLOCK_CAPACITY = 1024

        public fun mono(sampleRate: Int): LoudnessMeter = LoudnessMeter(sampleRate, ChannelWeights.Mono)

        public fun stereo(sampleRate: Int): LoudnessMeter = LoudnessMeter(sampleRate, ChannelWeights.Stereo)

        /** `L = -0.691 + 10 log10(energy)`. The offset makes a 997 Hz tone read the same as its RMS level. */
        public fun energyToLufs(energy: Double): Double = if (energy <= 0.0) Double.NEGATIVE_INFINITY else -0.691 + 10.0 * log10(energy)

        public fun lufsToEnergy(lufs: Double): Double = 10.0.pow((lufs + 0.691) / 10.0)
    }
}
