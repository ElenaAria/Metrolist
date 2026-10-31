package ultra.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/** The builder for ultra.db in the app's standard databases directory. Finish it with [buildUltraDatabase]. */
public fun ultraDatabaseBuilder(context: Context): RoomDatabase.Builder<UltraDatabase> {
    val appContext = context.applicationContext
    return Room.databaseBuilder<UltraDatabase>(
        context = appContext,
        name = appContext.getDatabasePath(ULTRA_DATABASE_NAME).absolutePath,
    )
}
