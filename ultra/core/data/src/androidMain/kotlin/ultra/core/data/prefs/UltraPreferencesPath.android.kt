package ultra.core.data.prefs

import android.content.Context
import java.io.File

/** Where ultra's preferences live: `files/datastore/ultra.preferences_pb`, next to (but apart from) upstream's. */
public fun ultraPreferencesPath(context: Context): String = File(context.applicationContext.filesDir, "datastore/$ULTRA_PREFERENCES_FILE_NAME").absolutePath
