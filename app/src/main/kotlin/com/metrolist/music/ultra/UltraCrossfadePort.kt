package com.metrolist.music.ultra

import android.content.Context
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ultra.feature.dsp.settings.CrossfadePort
import ultra.feature.dsp.settings.CrossfadeState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Lets the Smart DJ engine see (and arm) upstream's crossfade without :ultra-feature-dsp depending on
 * :app. This package is the only place that knows upstream's preference keys; it reads the very keys
 * upstream's own crossfade settings screen writes, and writes them through upstream's own
 * `safeDataStoreEdit`, so the two always agree.
 *
 * Defaults mirror MusicService (`CrossfadeEnabledKey ?: false`, `CrossfadeDurationKey ?: 5f` seconds).
 */
@Singleton
class UpstreamCrossfadePort
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CrossfadePort {
        // A getter, not an initializer: the DataStore is only touched once somebody collects.
        override val state: Flow<CrossfadeState>
            get() =
                context.dataStore.data
                    .map { prefs ->
                        CrossfadeState(
                            enabled = prefs[CrossfadeEnabledKey] ?: false,
                            durationMs = ((prefs[CrossfadeDurationKey] ?: DEFAULT_SECONDS) * 1000f).roundToInt(),
                        )
                    }.distinctUntilChanged()

        override suspend fun enable(durationMs: Int) {
            context.safeDataStoreEdit {
                it[CrossfadeEnabledKey] = true
                it[CrossfadeDurationKey] = durationMs / 1000f
            }
        }

        private companion object {
            const val DEFAULT_SECONDS = 5f
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class UltraAppBindingsModule {
    @Binds
    abstract fun crossfadePort(impl: UpstreamCrossfadePort): CrossfadePort
}
