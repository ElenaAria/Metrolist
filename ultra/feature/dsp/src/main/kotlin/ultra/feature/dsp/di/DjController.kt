package ultra.feature.dsp.di

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import ultra.feature.dsp.device.AudioOutputMonitor
import ultra.feature.dsp.device.AudioRouteClassifier
import ultra.feature.dsp.device.OutputRoute
import ultra.feature.dsp.engine.DjEngine
import ultra.feature.dsp.engine.DjProfile
import ultra.feature.dsp.engine.DjSnapshot
import ultra.feature.dsp.engine.DjStats
import ultra.feature.dsp.engine.ProfileId
import ultra.feature.dsp.settings.CrossfadePort
import ultra.feature.dsp.settings.CrossfadeState
import ultra.feature.dsp.settings.DjDecision
import ultra.feature.dsp.settings.DjSettings
import ultra.feature.dsp.settings.DjSettingsStore
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the engine and keeps its inputs current. Everything here is cheap and asynchronous: the audio
 * thread only ever reads one `@Volatile` [DjSnapshot], never waits on settings or the OS.
 *
 * Construction does no I/O (Hilt builds the multibinding sets at process start, before the app is up);
 * the work begins in [start], which the first audio sink and the DJ screen both call.
 */
@Singleton
class DjController
    @Inject
    constructor(
        @ApplicationContext context: Context,
        crossfade: Optional<CrossfadePort>,
    ) {
        data class UiState(
            val settings: DjSettings = DjSettings(),
            val route: OutputRoute = OutputRoute.UNKNOWN,
            val profile: DjProfile = DjDecision.profileFor(DjSettings(), OutputRoute.UNKNOWN),
            val crossfade: CrossfadeState = CrossfadeState.UNAVAILABLE,
            val crossfadeAvailable: Boolean = false,
            val snapshot: DjSnapshot = DjSnapshot.OFF,
        )

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val store = DjSettingsStore(context, scope)
        private val monitor = AudioOutputMonitor(context)
        private val crossfadePort: CrossfadePort? = crossfade.orElse(null)

        @Volatile
        private var snapshot: DjSnapshot = DjSnapshot.OFF

        val engine = DjEngine(snapshot = { snapshot })

        private val _ui = MutableStateFlow(UiState(crossfadeAvailable = crossfadePort != null))
        val ui: StateFlow<UiState> = _ui.asStateFlow()

        private var started = false

        @Synchronized
        fun start() {
            if (started) return
            started = true
            monitor.start()
            scope.launch {
                combine(
                    store.settings,
                    monitor.devices,
                    // flowOf, not emptyFlow: combine never emits if any input completes without a value.
                    crossfadePort?.state ?: flowOf(CrossfadeState.UNAVAILABLE),
                ) { settings, devices, crossfadeState ->
                    val route = AudioRouteClassifier.resolve(devices.connected, devices.active, settings.carDevices)
                    UiState(
                        settings = settings,
                        route = route,
                        profile = DjDecision.profileFor(settings, route),
                        crossfade = crossfadeState,
                        crossfadeAvailable = crossfadePort != null,
                        snapshot = DjDecision.snapshot(settings, route, crossfadeState),
                    )
                }.collect {
                    snapshot = it.snapshot
                    _ui.value = it
                }
            }
        }

        fun stats(): DjStats = engine.stats()

        fun refreshDevices() = monitor.refresh()

        fun setEnabled(enabled: Boolean) {
            scope.launch { store.setEnabled(enabled) }
        }

        fun setProfileOverride(profile: ProfileId?) {
            scope.launch { store.setProfileOverride(profile) }
        }

        fun setCarDevice(
            name: String,
            isCar: Boolean,
        ) {
            scope.launch { store.setCarDevice(name, isCar) }
        }

        fun enableCrossfade(durationMs: Int) {
            scope.launch { crossfadePort?.enable(durationMs) }
        }
    }
