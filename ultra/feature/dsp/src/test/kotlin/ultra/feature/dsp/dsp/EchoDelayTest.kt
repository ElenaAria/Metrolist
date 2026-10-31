package ultra.feature.dsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EchoDelayTest {
    private val rate = 48_000
    private val channels = 2

    private fun impulse(frames: Int): FloatArray = FloatArray(frames * channels) { i -> if (i < channels) 1f else 0f }

    @Test
    fun aClickComesBackOncePerDelayAndDecays() {
        val echo = EchoDelay(rate, channels)
        val delayFrames = rate / 4 // 250 ms
        val total = delayFrames * 4
        val out = FloatArray(total * channels)

        var done = 0
        while (done < total) {
            val n = minOf(1000, total - done)
            val chunk = FloatArray(n * channels)
            if (done == 0) repeat(channels) { chunk[it] = 1f }
            echo.process(chunk, n, delayFrames, feedback = 0.5f, dampCoef = 1f, wet = 1f)
            chunk.copyInto(out, done * channels)
            done += n
        }

        // The direct signal, then a repeat every delay, each half the previous (feedback 0.5, no damping).
        for (repeat in 0..3) {
            val at = repeat * delayFrames * channels
            val expected = Math.pow(0.5, repeat.toDouble()).toFloat()
            assertEquals("repeat $repeat", expected, out[at], 1e-5f)
            assertEquals(expected, out[at + 1], 1e-5f)
            if (repeat > 0) assertEquals(0f, out[at - channels], 0f) // nothing just before the repeat
        }
    }

    @Test
    fun wetScalesOnlyTheOutput() {
        fun render(wet: Float): Float {
            val echo = EchoDelay(rate, channels)
            val out = impulse(rate)
            echo.process(out, rate, delayFrames = rate / 2, feedback = 0.5f, dampCoef = 1f, wet = wet)
            return out[rate / 2 * channels]
        }
        assertEquals(0f, render(0f), 1e-6f)
        assertEquals(0.5f, render(1f), 1e-5f)
        assertEquals(0.25f, render(0.5f), 1e-5f)
    }

    @Test
    fun dampingDarkensTheRepeats() {
        // A sustained tone: with the low-pass wide open the echo builds up; with it nearly closed it stays
        // far smaller, because every repeat is attenuated.
        fun level(dampCoef: Float): Float {
            val echo = EchoDelay(rate, 1)
            val frames = rate
            val input = FloatArray(frames) { i -> if (i % 2 == 0) 0.5f else -0.5f }
            echo.process(input, frames, delayFrames = 100, feedback = 0.8f, dampCoef = dampCoef, wet = 1f)
            return input.takeLast(200).maxOf { abs(it) }
        }
        assertTrue(level(0.05f) < level(1f) / 2f)
    }

    @Test
    fun clearRemovesTheTail() {
        val echo = EchoDelay(rate, 1)
        echo.process(floatArrayOf(1f), 1, delayFrames = 100, feedback = 0.5f, dampCoef = 1f, wet = 0f)
        echo.clear()
        val out = FloatArray(200)
        echo.process(out, 200, delayFrames = 100, feedback = 0.5f, dampCoef = 1f, wet = 1f)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun channelsStayIndependent() {
        val echo = EchoDelay(rate, channels)
        val chunk = FloatArray(channels) { c -> if (c == 0) 1f else 0f }
        val out = FloatArray(rate * channels)
        var done = 0
        while (done < rate) {
            val n = minOf(500, rate - done)
            val block = if (done == 0) chunk + FloatArray((n - 1) * channels) else FloatArray(n * channels)
            echo.process(block, n, delayFrames = rate / 2, feedback = 0.5f, dampCoef = 1f, wet = 1f)
            block.copyInto(out, done * channels)
            done += n
        }
        val repeat = rate / 2 * channels
        assertEquals(0.5f, out[repeat], 1e-5f) // left channel
        assertEquals(0f, out[repeat + 1], 1e-6f) // right channel never had a signal
    }
}
