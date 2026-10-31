package ultra.feature.dsp.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ultra.feature.dsp.engine.DeckRole
import ultra.feature.dsp.engine.DjProfiles
import ultra.feature.dsp.engine.ProfileId
import kotlin.math.abs

class DeckEffectsTest {
    private val rate = 48_000
    private val channels = 2
    private val profile = DjProfiles.forId(ProfileId.WIRED)

    private fun tone(
        frames: Int,
        hz: Float,
    ): FloatArray {
        val out = FloatArray(frames * channels)
        for (i in 0 until frames) {
            val s = kotlin.math.sin(2.0 * Math.PI * hz * i / rate).toFloat() * 0.5f
            repeat(channels) { out[i * channels + it] = s }
        }
        return out
    }

    private fun rms(buf: FloatArray): Double {
        var sum = 0.0
        for (v in buf) sum += v * v
        return kotlin.math.sqrt(sum / buf.size)
    }

    @Test
    fun soloIsBitExactAndReportsUntouched() {
        val fx = DeckEffects(rate, channels)
        val input = tone(DeckEffects.CHUNK_FRAMES, 440f)
        val original = input.copyOf()
        val processed = fx.process(input, DeckEffects.CHUNK_FRAMES, DeckRole.SOLO, 0f, profile)
        assertFalse(processed)
        assertTrue(input.contentEquals(original))
        assertTrue(fx.isIdle)
    }

    @Test
    fun engagingChangesTheAudioAndReleasingReturnsToExact() {
        val fx = DeckEffects(rate, channels)
        val bass = tone(DeckEffects.CHUNK_FRAMES, 60f)
        val original = bass.copyOf()

        // A few chunks into the transition: the outgoing high-pass should be cutting bass.
        var changed = false
        repeat(20) { i ->
            if (fx.process(bass, DeckEffects.CHUNK_FRAMES, DeckRole.OUTGOING, progress = i / 40f, profile)) changed = true
        }
        assertTrue(changed)
        assertFalse(bass.contentEquals(original))

        // Release. The chain holds while its own tail rings, then ramps back to idle.
        var steps = 0
        while (!fx.isIdle && steps < 2_000) {
            fx.process(bass, DeckEffects.CHUNK_FRAMES, DeckRole.SOLO, 0f, profile)
            steps++
        }
        assertTrue("chain did not return to idle", fx.isIdle)
        val after = bass.copyOf()
        assertFalse(fx.process(bass, DeckEffects.CHUNK_FRAMES, DeckRole.SOLO, 0f, profile))
        assertTrue("idle chain modified the audio", bass.contentEquals(after))
    }

    @Test
    fun outgoingLosesBassWhileIncomingKeepsIt() {
        // One continuous tone, fed in slices. Restarting the phase every slice would measure the seam
        // between slices instead of the filter, so the whole tone is generated first.
        val totalFrames = rate / 5 // 200 ms: the blend opens in ~80 ms, the rest is steady state
        val measureFrom = rate / 10 // measure the second half only

        fun levelAt(
            role: DeckRole,
            progress: Float,
        ): Double {
            val fx = DeckEffects(rate, channels)
            val out = FloatArray(totalFrames * channels)
            var frame = 0
            while (frame < totalFrames) {
                val n = minOf(DeckEffects.CHUNK_FRAMES, totalFrames - frame)
                val slice = FloatArray(n * channels)
                for (i in 0 until n) {
                    val s = kotlin.math.sin(2.0 * Math.PI * 50 * (frame + i) / rate).toFloat() * 0.5f
                    repeat(channels) { slice[i * channels + it] = s }
                }
                fx.process(slice, n, role, progress, profile)
                slice.copyInto(out, frame * channels)
                frame += n
            }
            return rms(out.copyOfRange(measureFrom * channels, out.size))
        }

        val dry = levelAt(DeckRole.SOLO, 0f)
        // Early on, the outgoing deck still owns the bass and the incoming deck has none of it.
        assertTrue("outgoing should keep its bass early", levelAt(DeckRole.OUTGOING, 0.30f) > dry * 0.9)
        assertTrue("incoming should have no bass early", levelAt(DeckRole.INCOMING, 0.30f) < dry * 0.15)
        // They trade places.
        assertTrue("outgoing should have lost its bass", levelAt(DeckRole.OUTGOING, 0.70f) < dry * 0.15)
        assertTrue("incoming should own the bass late", levelAt(DeckRole.INCOMING, 0.70f) > dry * 0.9)
        // At the crossing both are cut, and by about the same amount.
        val mid = levelAt(DeckRole.OUTGOING, 0.50f)
        assertEquals(mid, levelAt(DeckRole.INCOMING, 0.50f), mid * 0.1)
    }

    @Test
    fun echoAddsEnergyAfterTheSourceStops() {
        val withEcho = DjProfiles.forId(ProfileId.WIRED)
        val fx = DeckEffects(rate, channels)
        val delayFrames = withEcho.echoDelayMs * rate / 1000

        // Feed a loud chunk, then silence for longer than the delay.
        val loud = FloatArray(DeckEffects.CHUNK_FRAMES * channels) { 0.8f }
        fx.process(loud, DeckEffects.CHUNK_FRAMES, DeckRole.OUTGOING, progress = 0.6f, withEcho)
        var peak = 0f
        var frames = 0
        while (frames < delayFrames + rate / 5) {
            val silence = FloatArray(DeckEffects.CHUNK_FRAMES * channels)
            fx.process(silence, DeckEffects.CHUNK_FRAMES, DeckRole.OUTGOING, progress = 0.8f, withEcho)
            peak = maxOf(peak, silence.maxOf { abs(it) })
            frames += DeckEffects.CHUNK_FRAMES
        }
        assertTrue("expected an echo tail, peak was $peak", peak > 0.05f)
    }

    @Test
    fun studioProfileHasNoEchoTail() {
        val studio = DjProfiles.forId(ProfileId.STUDIO)
        assertEquals(0f, studio.echoWet, 0f)
        val fx = DeckEffects(rate, channels)
        fx.process(FloatArray(DeckEffects.CHUNK_FRAMES * channels) { 0.8f }, DeckEffects.CHUNK_FRAMES, DeckRole.OUTGOING, 0.8f, studio)
        // Let the release finish (the blend closes at the same speed it opened), then the tail must be
        // gone: this profile has no echo, so nothing keeps sounding after the tone stops.
        var steps = 0
        while (!fx.isIdle && steps < 2_000) {
            fx.process(FloatArray(DeckEffects.CHUNK_FRAMES * channels), DeckEffects.CHUNK_FRAMES, DeckRole.SOLO, 0f, studio)
            steps++
        }
        assertTrue(fx.isIdle)
        val tail = FloatArray(DeckEffects.CHUNK_FRAMES * channels)
        fx.process(tail, DeckEffects.CHUNK_FRAMES, DeckRole.SOLO, 0f, studio)
        assertEquals(0f, tail.maxOf { abs(it) }, 1e-6f)
    }

    @Test
    fun outputStaysFiniteOnAHotSignal() {
        val fx = DeckEffects(rate, channels)
        val hot = FloatArray(DeckEffects.CHUNK_FRAMES * channels) { 3f }
        repeat(300) { i ->
            fx.process(hot, DeckEffects.CHUNK_FRAMES, DeckRole.OUTGOING, (i % 100) / 100f, profile)
            assertTrue(hot.all { it.isFinite() })
        }
    }
}
