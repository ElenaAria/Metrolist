package ultra.feature.dsp.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import ultra.feature.dsp.engine.ProfileId
import java.io.File
import java.io.IOException

/**
 * @property profileOverride null = pick the profile from the output device automatically.
 * @property carDevices Bluetooth device names the user marked as car audio.
 */
data class DjSettings(
    val enabled: Boolean = true,
    val profileOverride: ProfileId? = null,
    val carDevices: Set<String> = emptySet(),
)

/**
 * Persists [DjSettings] in its own file (`ultra_dj.preferences_pb`), separate from upstream's
 * "settings" store, so nothing here can corrupt or migrate upstream's preferences.
 *
 * The parent directory is created before DataStore opens the file: upstream hit crashes from a
 * missing `datastore/` directory on some ROMs (see its own DataStore.kt), and this store does not
 * need to rediscover that.
 */
class DjSettingsStore(
    context: Context,
    scope: CoroutineScope,
) {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(context.applicationContext.filesDir, "datastore").apply { mkdirs() }.resolve("$FILE_NAME.preferences_pb")
        }

    val settings: Flow<DjSettings> =
        dataStore.data
            .catch { e ->
                // A corrupt or unreadable file must not take the feature down: fall back to defaults.
                if (e is IOException) emit(emptyPreferences()) else throw e
            }.map { prefs ->
                DjSettings(
                    enabled = prefs[ENABLED] ?: true,
                    profileOverride = prefs[PROFILE]?.let { name -> ProfileId.entries.firstOrNull { it.name == name } },
                    carDevices = prefs[CAR_DEVICES] ?: emptySet(),
                )
            }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    /** Pass null to return to automatic, device-based selection. */
    suspend fun setProfileOverride(profile: ProfileId?) {
        dataStore.edit { if (profile == null) it.remove(PROFILE) else it[PROFILE] = profile.name }
    }

    suspend fun setCarDevice(
        name: String,
        isCar: Boolean,
    ) {
        if (name.isBlank()) return
        dataStore.edit {
            val current = it[CAR_DEVICES] ?: emptySet()
            it[CAR_DEVICES] = if (isCar) current + name else current - name
        }
    }

    private companion object {
        const val FILE_NAME = "ultra_dj"
        val ENABLED = booleanPreferencesKey("enabled")
        val PROFILE = stringPreferencesKey("profile")
        val CAR_DEVICES = stringSetPreferencesKey("car_devices")
    }
}
