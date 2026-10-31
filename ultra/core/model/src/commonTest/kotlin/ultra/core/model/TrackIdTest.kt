package ultra.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackIdTest {
    @Test
    fun `a plain video id is a youtube id with no namespace`() {
        val id = TrackId.youtube("dQw4w9WgXcQ")

        assertTrue(id.isYouTube)
        assertNull(id.namespace)
        assertEquals("dQw4w9WgXcQ", id.rawId)
    }

    @Test
    fun `youtube ids can contain dashes and underscores without being mistaken for a namespace`() {
        val id = TrackId("a-b_c-D1234")

        assertTrue(id.isYouTube)
        assertEquals("a-b_c-D1234", id.rawId)
    }

    @Test
    fun `namespaced ids round trip`() {
        val id = TrackId.namespaced("subsonic", "al-42/tr-7")

        assertEquals("subsonic:al-42/tr-7", id.value)
        assertEquals("subsonic", id.namespace)
        assertEquals("al-42/tr-7", id.rawId)
        assertFalse(id.isYouTube)
    }

    @Test
    fun `the raw id may itself contain the separator`() {
        val id = TrackId.namespaced("jellyfin", "a:b:c")

        assertEquals("jellyfin", id.namespace)
        assertEquals("a:b:c", id.rawId)
    }

    @Test
    fun `blank ids and malformed namespaces are rejected`() {
        assertFailsWith<IllegalArgumentException> { TrackId(" ") }
        assertFailsWith<IllegalArgumentException> { TrackId.namespaced("", "x") }
        assertFailsWith<IllegalArgumentException> { TrackId.namespaced("a:b", "x") }
        assertFailsWith<IllegalArgumentException> { TrackId.namespaced("subsonic", "") }
    }
}
