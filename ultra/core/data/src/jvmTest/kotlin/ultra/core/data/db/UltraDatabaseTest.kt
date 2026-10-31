package ultra.core.data.db

import androidx.room.useReaderConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class UltraDatabaseTest {
    private lateinit var db: UltraDatabase

    @BeforeTest
    fun open() {
        db = ultraInMemoryDatabaseBuilder().buildUltraDatabase()
    }

    @AfterTest
    fun close() {
        db.close()
    }

    private fun analysis(
        id: String,
        lufs: Double? = -14.2,
        version: Int = 1,
    ) = TrackAnalysisEntity(id, integratedLufs = lufs, samplePeak = 0.93, analyzerVersion = version, analyzedAtEpochMs = 1_000)

    @Test
    fun `an analysis can be stored, replaced and read back`() =
        runBlocking {
            val dao = db.trackAnalysisDao()
            assertNull(dao.find("yt-1"))

            dao.upsert(analysis("yt-1", lufs = -14.2))
            dao.upsert(analysis("yt-1", lufs = -9.5)) // upsert replaces by primary key

            assertEquals(-9.5, dao.find("yt-1")?.integratedLufs)
            assertEquals(1, dao.count())
        }

    @Test
    fun `unmeasurable loudness is stored as null`() =
        runBlocking {
            val dao = db.trackAnalysisDao()

            dao.upsert(analysis("silent", lufs = null))

            assertNull(dao.find("silent")?.integratedLufs)
            assertEquals(0.93, dao.find("silent")?.samplePeak)
        }

    @Test
    fun `stale analyser versions can be purged`() =
        runBlocking {
            val dao = db.trackAnalysisDao()
            dao.upsert(analysis("old-1", version = 1))
            dao.upsert(analysis("old-2", version = 1))
            dao.upsert(analysis("current", version = 3))

            val removed = dao.deleteOlderThanVersion(3)

            assertEquals(2, removed)
            assertEquals(listOf("current"), listOf("old-1", "old-2", "current").filter { dao.find(it) != null })
        }

    @Test
    fun `delete removes one analysis`() =
        runBlocking {
            val dao = db.trackAnalysisDao()
            dao.upsert(analysis("a"))
            dao.upsert(analysis("b"))

            dao.delete("a")

            assertNull(dao.find("a"))
            assertEquals(1, dao.count())
        }

    @Test
    fun `observers are told about every change`() =
        runBlocking {
            withTimeout(15.seconds) {
                val dao = db.trackAnalysisDao()
                val seen = Channel<TrackAnalysisEntity?>(Channel.UNLIMITED)
                val collector = launch { dao.observe("yt-1").collect { seen.send(it) } }

                assertNull(seen.receive()) // nothing stored yet
                val first = analysis("yt-1", lufs = -14.0)
                dao.upsert(first)
                assertEquals(first, seen.receive())
                val second = analysis("yt-1", lufs = -8.0)
                dao.upsert(second)
                assertEquals(second, seen.receive())
                dao.delete("yt-1")
                assertNull(seen.receive())

                collector.cancel()
            }
        }

    @Test
    fun `the schema holds exactly the two ultra tables and none of upstream's`() =
        runBlocking {
            val tables =
                db.useReaderConnection { connection ->
                    connection.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table'") { statement ->
                        buildList { while (statement.step()) add(statement.getText(0)) }
                    }
                }

            val own = tables.filterNot { it.startsWith("room_") || it.startsWith("sqlite_") || it.startsWith("android_") }
            assertEquals(listOf("metadata_cache", "track_analysis"), own.sorted())
        }

    @Test
    fun `data survives closing and reopening a file on disk`() {
        val dir = Files.createTempDirectory("ultra-db-test").toFile()
        try {
            val file = File(dir, ULTRA_DATABASE_NAME)

            val first = ultraDatabaseBuilder(file).buildUltraDatabase()
            runBlocking { first.trackAnalysisDao().upsert(analysis("persisted", lufs = -11.0)) }
            first.close()

            val second = ultraDatabaseBuilder(file).buildUltraDatabase()
            try {
                assertEquals(-11.0, runBlocking { second.trackAnalysisDao().find("persisted")?.integratedLufs })
            } finally {
                second.close()
            }
            assertEquals("ultra.db", ULTRA_DATABASE_NAME)
            assertTrue(file.exists() && file.length() > 0)
        } finally {
            dir.deleteRecursively()
        }
    }
}
