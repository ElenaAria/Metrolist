package ultra.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okio.Path.Companion.toPath

/** File name of ultra's preferences. Upstream's settings live in a different file. */
public const val ULTRA_PREFERENCES_FILE_NAME: String = "ultra.preferences_pb"

/** A typed preference with its default. Feature modules declare their own keys next to their own features. */
public class PrefKey<T> internal constructor(
    internal val key: Preferences.Key<T>,
    public val default: T,
) {
    public val name: String get() = key.name

    override fun toString(): String = "PrefKey($name)"
}

public fun booleanPrefKey(
    name: String,
    default: Boolean,
): PrefKey<Boolean> = PrefKey(booleanPreferencesKey(name), default)

public fun intPrefKey(
    name: String,
    default: Int,
): PrefKey<Int> = PrefKey(intPreferencesKey(name), default)

public fun longPrefKey(
    name: String,
    default: Long,
): PrefKey<Long> = PrefKey(longPreferencesKey(name), default)

public fun doublePrefKey(
    name: String,
    default: Double,
): PrefKey<Double> = PrefKey(doublePreferencesKey(name), default)

public fun stringPrefKey(
    name: String,
    default: String,
): PrefKey<String> = PrefKey(stringPreferencesKey(name), default)

/** Keys owned by the core layer. Only settings the core modules themselves read belong here. */
public object UltraPrefKeys {
    /** Whether background loudness analysis may run. */
    public val AnalysisEnabled: PrefKey<Boolean> = booleanPrefKey("core_analysis_enabled", default = true)

    /** The loudness every track is normalised towards, in LUFS. */
    public val NormalizationTargetLufs: PrefKey<Double> = doublePrefKey("core_normalization_target_lufs", default = -14.0)
}

/**
 * Typed access to ultra's DataStore. Reads fall back to the key's default, and [observe] only emits when
 * the value actually changes.
 */
public class UltraPreferences(
    private val store: DataStore<Preferences>,
) {
    public fun <T> observe(key: PrefKey<T>): Flow<T> = store.data.map { it[key.key] ?: key.default }.distinctUntilChanged()

    public suspend fun <T> get(key: PrefKey<T>): T = observe(key).first()

    public suspend fun <T> set(
        key: PrefKey<T>,
        value: T,
    ) {
        store.edit { it[key.key] = value }
    }

    /** Back to the default. */
    public suspend fun reset(key: PrefKey<*>) {
        store.edit { it.remove(key.key) }
    }

    public suspend fun clear() {
        store.edit { it.clear() }
    }
}

/**
 * Opens the DataStore stored at [path]. A process may have only one active DataStore per file, so create
 * this once (the DI layer should provide it as a singleton) and give it a long-lived [scope].
 */
public fun createUltraPreferencesDataStore(
    path: String,
    scope: CoroutineScope,
): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(
        scope = scope,
        produceFile = { path.toPath() },
    )
