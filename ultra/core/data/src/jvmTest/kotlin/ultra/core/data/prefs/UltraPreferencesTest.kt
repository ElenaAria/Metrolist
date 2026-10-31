package ultra.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class UltraPreferencesTest {
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: UltraPreferences

    private fun path() = File(dir, ULTRA_PREFERENCES_FILE_NAME).absolutePath

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeTest
    fun open() {
        dir = Files.createTempDirectory("ultra-prefs-test").toFile()
        scope = newScope()
        store = createUltraPreferencesDataStore(path(), scope)
        prefs = UltraPreferences(store)
    }

    @AfterTest
    fun close() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        dir.deleteRecursively()
    }

    @Test
    fun `unset keys read as their defaults`() =
        runBlocking {
            assertEquals(true, prefs.get(UltraPrefKeys.AnalysisEnabled))
            assertEquals(-14.0, prefs.get(UltraPrefKeys.NormalizationTargetLufs))
        }

    @Test
    fun `values of every supported type round trip`() =
        runBlocking {
            val flag = booleanPrefKey("flag", default = false)
            val count = intPrefKey("count", default = 0)
            val big = longPrefKey("big", default = 0L)
            val ratio = doublePrefKey("ratio", default = 0.0)
            val text = stringPrefKey("text", default = "")

            prefs.set(flag, true)
            prefs.set(count, 7)
            prefs.set(big, 1L shl 40)
            prefs.set(ratio, -23.5)
            prefs.set(text, "ultra")

            assertEquals(true, prefs.get(flag))
            assertEquals(7, prefs.get(count))
            assertEquals(1L shl 40, prefs.get(big))
            assertEquals(-23.5, prefs.get(ratio))
            assertEquals("ultra", prefs.get(text))
        }

    @Test
    fun `reset returns a key to its default and clear resets everything`() =
        runBlocking {
            prefs.set(UltraPrefKeys.NormalizationTargetLufs, -18.0)
            prefs.set(UltraPrefKeys.AnalysisEnabled, false)

            prefs.reset(UltraPrefKeys.NormalizationTargetLufs)
            assertEquals(-14.0, prefs.get(UltraPrefKeys.NormalizationTargetLufs))
            assertEquals(false, prefs.get(UltraPrefKeys.AnalysisEnabled)) // untouched

            prefs.clear()
            assertEquals(true, prefs.get(UltraPrefKeys.AnalysisEnabled))
        }

    @Test
    fun `observers see changes to their own key only and never a repeat`() =
        runBlocking {
            withTimeout(15.seconds) {
                val seen = Channel<Double>(Channel.UNLIMITED)
                val collector = launch { prefs.observe(UltraPrefKeys.NormalizationTargetLufs).collect { seen.send(it) } }

                assertEquals(-14.0, seen.receive()) // the default, immediately
                prefs.set(UltraPrefKeys.AnalysisEnabled, false) // unrelated key: no emission
                prefs.set(UltraPrefKeys.NormalizationTargetLufs, -14.0) // same value: no emission
                prefs.set(UltraPrefKeys.NormalizationTargetLufs, -16.0)
                assertEquals(-16.0, seen.receive()) // the very next emission is the real change

                collector.cancel()
            }
        }

    @Test
    fun `values survive reopening the file`() =
        runBlocking {
            prefs.set(UltraPrefKeys.NormalizationTargetLufs, -20.0)
            scope.coroutineContext[Job]!!.cancelAndJoin() // one active DataStore per file: release it first

            scope = newScope()
            val reopened = UltraPreferences(createUltraPreferencesDataStore(path(), scope))

            assertEquals(-20.0, reopened.get(UltraPrefKeys.NormalizationTargetLufs))
        }

    @Test
    fun `the file is named apart from upstream's settings and keys are distinct`() {
        assertEquals("ultra.preferences_pb", ULTRA_PREFERENCES_FILE_NAME)
        assertNotEquals(UltraPrefKeys.AnalysisEnabled.name, UltraPrefKeys.NormalizationTargetLufs.name)
        assertTrue(File(path()).name != "settings.preferences_pb")
    }
}
