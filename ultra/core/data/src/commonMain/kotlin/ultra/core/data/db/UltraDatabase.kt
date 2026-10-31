package ultra.core.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/** File name of ultra's database. Upstream's own database has a different name, so the two can never collide. */
public const val ULTRA_DATABASE_NAME: String = "ultra.db"

/**
 * Ultra's database. Add entities here and bump [version] together with a migration; the exported
 * schema in `schemas/` is the contract migrations are tested against.
 */
@Database(
    entities = [TrackAnalysisEntity::class, MetadataCacheEntity::class],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(UltraDatabaseConstructor::class)
public abstract class UltraDatabase : RoomDatabase() {
    public abstract fun trackAnalysisDao(): TrackAnalysisDao

    public abstract fun metadataCacheDao(): MetadataCacheDao
}

/** Room generates the `actual` for each platform. */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
public expect object UltraDatabaseConstructor : RoomDatabaseConstructor<UltraDatabase> {
    override fun initialize(): UltraDatabase
}

/**
 * Finishes a platform builder (`ultraDatabaseBuilder` on Android and JVM). The bundled SQLite driver
 * makes the database engine and version identical on every platform instead of whatever the OS ships.
 */
public fun RoomDatabase.Builder<UltraDatabase>.buildUltraDatabase(): UltraDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
