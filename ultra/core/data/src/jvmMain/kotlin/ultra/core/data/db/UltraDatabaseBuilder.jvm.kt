package ultra.core.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/** The builder for a database file, e.g. `File(appDataDir, ULTRA_DATABASE_NAME)`. Finish it with [buildUltraDatabase]. */
public fun ultraDatabaseBuilder(file: File): RoomDatabase.Builder<UltraDatabase> = Room.databaseBuilder<UltraDatabase>(name = file.absolutePath)

/** A throwaway in-memory database, for tests. */
public fun ultraInMemoryDatabaseBuilder(): RoomDatabase.Builder<UltraDatabase> = Room.inMemoryDatabaseBuilder<UltraDatabase>()
