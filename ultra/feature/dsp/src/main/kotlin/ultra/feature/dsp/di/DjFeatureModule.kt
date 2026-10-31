package ultra.feature.dsp.di

import androidx.media3.common.audio.AudioProcessor
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ultra.feature.dsp.processor.DjDeckAudioProcessor
import ultra.feature.dsp.settings.CrossfadePort
import ultra.feature.dsp.ui.DjUiContributor
import ultra.hooks.AudioProcessorsContributor
import ultra.hooks.DestinationContributor
import ultra.hooks.SettingsEntryContributor
import javax.inject.Inject

/**
 * Seam S6 contributor. `create()` runs once per audio sink: at player creation, and again for the
 * second player upstream's crossfade builds. Every call returns a fresh processor on a fresh deck,
 * which is what the S6 contract requires (processors are stateful and cannot be shared).
 *
 * The controller is [Lazy] on purpose: Hilt builds this class while the multibinding sets are folded
 * into UltraHooks at process start, long before any audio exists, and that must stay cheap.
 */
class DjAudioProcessorsContributor
    @Inject
    constructor(
        private val controller: Lazy<DjController>,
    ) : AudioProcessorsContributor {
        override fun create(): List<AudioProcessor> {
            val dj = controller.get()
            dj.start()
            return listOf(DjDeckAudioProcessor(dj.engine.newDeck()))
        }
    }

/** The feature's contributions to the three seams it uses (S6, S8, S9), plus its one optional dependency. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DjFeatureModule {
    @Binds
    @IntoSet
    abstract fun audioProcessors(impl: DjAudioProcessorsContributor): AudioProcessorsContributor

    @Binds
    @IntoSet
    abstract fun destinations(impl: DjUiContributor): DestinationContributor

    @Binds
    @IntoSet
    abstract fun settingsEntries(impl: DjUiContributor): SettingsEntryContributor

    /** Provided by :app's glue (it owns upstream's preference keys); absent means "no crossfade info". */
    @BindsOptionalOf
    abstract fun crossfadePort(): CrossfadePort
}
