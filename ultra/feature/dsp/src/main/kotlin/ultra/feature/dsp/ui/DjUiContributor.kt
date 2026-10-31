package ultra.feature.dsp.ui

import dagger.Lazy
import ultra.feature.dsp.R
import ultra.feature.dsp.di.DjController
import ultra.hooks.DestinationContributor
import ultra.hooks.SettingsEntryContributor
import ultra.hooks.UltraDestination
import ultra.hooks.UltraSettingsEntry
import javax.inject.Inject

/** The Smart DJ screen (seam S8) and its row in the Settings list (seam S9). */
class DjUiContributor
    @Inject
    constructor(
        private val controller: Lazy<DjController>,
    ) : DestinationContributor,
        SettingsEntryContributor {
        override fun destinations(): List<UltraDestination> =
            listOf(UltraDestination(ROUTE, R.string.ultra_dj_title) { DjScreen(controller.get()) })

        override fun entries(): List<UltraSettingsEntry> =
            listOf(UltraSettingsEntry(ROUTE, R.string.ultra_dj_title, R.drawable.ultra_dj_ic))

        companion object {
            const val ROUTE = "ultra_dj"
        }
    }
