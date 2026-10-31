package ultra.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ModelTest {
    private fun track(id: String) = Track(TrackId(id), title = "t-$id")

    @Test
    fun `artist names are joined for display`() {
        val track =
            Track(
                id = TrackId("a"),
                title = "Song",
                artists = listOf(ArtistRef(null, "A"), ArtistRef(ArtistId("2"), "B")),
            )

        assertEquals("A, B", track.artistNames)
        assertEquals("", track("x").artistNames)
    }

    @Test
    fun `a negative duration is rejected but an unknown one is fine`() {
        assertFailsWith<IllegalArgumentException> { Track(TrackId("a"), "t", durationMs = -1) }
        assertNull(Track(TrackId("a"), "t").durationMs)
    }

    @Test
    fun `the default playback state is idle and not playing`() {
        val state = PlaybackState()

        assertEquals(PlaybackStatus.Idle, state.status)
        assertFalse(state.isPlaying)
        assertNull(state.currentTrack)
        assertEquals(1f, state.speed)
        assertEquals(RepeatMode.Off, state.repeatMode)
    }

    @Test
    fun `queue index must point inside the queue`() {
        val tracks = listOf(track("a"), track("b"))

        assertEquals(-1, PlaybackQueue().currentIndex)
        assertEquals(1, PlaybackQueue(tracks, currentIndex = 1).currentIndex)
        assertFailsWith<IllegalArgumentException> { PlaybackQueue(tracks, currentIndex = 2) }
        assertFailsWith<IllegalArgumentException> { PlaybackQueue(emptyList(), currentIndex = 0) }
    }

    @Test
    fun `eq bands reject non physical parameters`() {
        assertFailsWith<IllegalArgumentException> { EqBand(frequencyHz = 0f, gainDb = 0f) }
        assertFailsWith<IllegalArgumentException> { EqBand(frequencyHz = 1000f, gainDb = 0f, q = 0f) }
        assertEquals(EqFilterType.Peaking, EqBand(1000f, 3f).type)
    }

    @Test
    fun `eq presets need a name`() {
        assertFailsWith<IllegalArgumentException> { EqPreset(name = " ", bands = emptyList()) }
        assertEquals(0f, EqPreset("Flat", bands = emptyList()).preampDb)
    }
}
