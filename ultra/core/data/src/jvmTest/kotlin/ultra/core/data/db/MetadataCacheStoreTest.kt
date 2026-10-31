package ultra.core.data.db

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MetadataCacheStoreTest {
    private lateinit var db: UltraDatabase
    private lateinit var store: MetadataCacheStore
    private var now = 1_000_000L

    @BeforeTest
    fun open() {
        db = ultraInMemoryDatabaseBuilder().buildUltraDatabase()
        store = MetadataCacheStore(db.metadataCacheDao(), nowEpochMs = { now })
    }

    @AfterTest
    fun close() {
        db.close()
    }

    @Test
    fun `a stored value is returned until it expires`() =
        runBlocking {
            store.put("lyrics:abc", "la la la", ttl = 10.minutes)

            assertEquals("la la la", store.get("lyrics:abc"))
            now += 10.minutes.inWholeMilliseconds - 1
            assertEquals("la la la", store.get("lyrics:abc")) // one millisecond before expiry
            now += 1
            assertNull(store.get("lyrics:abc")) // expiry is exclusive: expired at exactly expiresAt
        }

    @Test
    fun `an unknown key is null`() =
        runBlocking {
            assertNull(store.get("nope"))
        }

    @Test
    fun `putting again replaces the value and restarts the clock`() =
        runBlocking {
            store.put("k", "v1", ttl = 1.minutes)
            now += 50.seconds.inWholeMilliseconds
            store.put("k", "v2", ttl = 1.minutes)
            now += 50.seconds.inWholeMilliseconds // 100 s after the first put, 50 s after the second

            assertEquals("v2", store.get("k"))
            assertEquals(1, store.size())
        }

    @Test
    fun `getOrPut produces once while fresh and again after expiry`() =
        runBlocking {
            var produced = 0
            suspend fun fetch() = store.getOrPut("k", ttl = 1.hours) { "value #${++produced}" }

            assertEquals("value #1", fetch())
            assertEquals("value #1", fetch())
            now += 2.hours.inWholeMilliseconds
            assertEquals("value #2", fetch())
            assertEquals(2, produced)
        }

    @Test
    fun `a failing producer caches nothing`() =
        runBlocking {
            assertFailsWith<IllegalStateException> { store.getOrPut("k", 1.hours) { error("network down") } }

            assertNull(store.get("k"))
            assertEquals(0, store.size())
        }

    @Test
    fun `purging removes only what expired`() =
        runBlocking {
            store.put("short-1", "a", ttl = 1.minutes)
            store.put("short-2", "b", ttl = 1.minutes)
            store.put("long", "c", ttl = 1.hours)
            now += 5.minutes.inWholeMilliseconds
            assertEquals(3, store.size()) // expired rows are still stored until purged

            assertEquals(2, store.purgeExpired())

            assertEquals(1, store.size())
            assertEquals("c", store.get("long"))
        }

    @Test
    fun `remove and clear`() =
        runBlocking {
            store.put("a", "1", 1.hours)
            store.put("b", "2", 1.hours)

            store.remove("a")
            assertNull(store.get("a"))
            assertEquals("2", store.get("b"))

            store.clear()
            assertEquals(0, store.size())
        }

    @Test
    fun `a ttl must be positive and finite`() =
        runBlocking {
            assertFailsWith<IllegalArgumentException> { store.put("k", "v", Duration.ZERO) }
            assertFailsWith<IllegalArgumentException> { store.put("k", "v", (-1).seconds) }
            assertFailsWith<IllegalArgumentException> { store.put("k", "v", Duration.INFINITE) }
            assertEquals(0, store.size())
        }
}
