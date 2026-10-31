package ultra.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceTest {
    private fun piped(
        host: String,
        priority: Int = 0,
    ) = ApiInstance.create(InstanceKind.Piped, "https://$host", priority)

    @Test
    fun `create normalises user input and derives a stable id`() {
        val instance = ApiInstance.create(InstanceKind.Invidious, "  https://inv.example.org///  ", label = "Example")

        assertEquals("https://inv.example.org", instance.baseUrl)
        assertEquals("invidious:https://inv.example.org", instance.id)
        assertEquals("Example", instance.label)
        assertTrue(instance.enabled)
    }

    @Test
    fun `url joins a path with exactly one slash`() {
        val instance = piped("api.example.org")

        assertEquals("https://api.example.org/streams/abc", instance.url("/streams/abc"))
        assertEquals("https://api.example.org/streams/abc", instance.url("streams/abc"))
    }

    @Test
    fun `malformed instances are rejected`() {
        assertFailsWith<IllegalArgumentException> { ApiInstance.create(InstanceKind.Piped, "ftp://example.org") }
        assertFailsWith<IllegalArgumentException> { ApiInstance.create(InstanceKind.Piped, "example.org") }
        assertFailsWith<IllegalArgumentException> { ApiInstance.create(InstanceKind.Piped, "https://") }
        assertFailsWith<IllegalArgumentException> { ApiInstance("", InstanceKind.Piped, "https://example.org") }
        assertFailsWith<IllegalArgumentException> { ApiInstance("x", InstanceKind.Piped, "https://example.org/") }
    }

    @Test
    fun `a homelab server on a LAN address over plain http is allowed`() {
        val home = ApiInstance.create(InstanceKind.Homelab, "http://192.168.1.20:4533")

        assertEquals("http://192.168.1.20:4533/rest/ping", home.url("rest/ping"))
    }

    @Test
    fun `the registry upserts in place, removes, toggles and reprioritises`() {
        val a = piped("a.example")
        val b = piped("b.example")
        val registry = InstanceRegistry(listOf(a, b))

        registry.upsert(a.copy(label = "renamed"))
        assertEquals(listOf("renamed", null), registry.instances.value.map { it.label }) // kept its position

        registry.upsert(piped("c.example"))
        assertEquals(3, registry.instances.value.size)

        assertTrue(registry.setEnabled(b.id, false))
        assertFalse(registry.find(b.id)!!.enabled)
        assertTrue(registry.setPriority(b.id, 7))
        assertEquals(7, registry.find(b.id)!!.priority)
        assertFalse(registry.setEnabled("nope", true))

        assertTrue(registry.remove(a.id))
        assertFalse(registry.remove(a.id))
        assertNull(registry.find(a.id))
    }

    @Test
    fun `the registry filters by kind`() {
        val registry =
            InstanceRegistry(
                listOf(
                    piped("a.example"),
                    ApiInstance.create(InstanceKind.Invidious, "https://i.example"),
                    ApiInstance.create(InstanceKind.Homelab, "http://nas.local"),
                ),
            )

        assertEquals(listOf(InstanceKind.Piped), registry.instances(InstanceKind.Piped).map { it.kind })
        assertEquals(1, registry.instances(InstanceKind.Homelab).size)
    }

    @Test
    fun `duplicate ids are rejected on construction and replacement`() {
        val a = piped("a.example")

        assertFailsWith<IllegalArgumentException> { InstanceRegistry(listOf(a, a.copy(priority = 1))) }
        val registry = InstanceRegistry()
        assertFailsWith<IllegalArgumentException> { registry.replaceAll(listOf(a, a)) }
        registry.replaceAll(listOf(a))
        assertEquals(listOf(a), registry.instances.value)
    }
}
