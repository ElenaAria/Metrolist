package ultra.feature.dsp.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ultra.feature.dsp.R
import ultra.feature.dsp.device.OutputKind
import ultra.feature.dsp.di.DjController
import ultra.feature.dsp.engine.DjStats
import ultra.feature.dsp.engine.ProfileId
import kotlin.math.roundToInt

/**
 * Body of the Smart DJ screen (the :app glue supplies top bar, back button and insets). Built to make an
 * on-the-go test legible: what output was detected, which profile that selected, whether upstream's
 * crossfade is armed, and live proof that a transition is actually being shaped.
 */
@Composable
internal fun DjScreen(controller: DjController) {
    LaunchedEffect(controller) {
        controller.start()
        controller.refreshDevices()
    }
    val ui by controller.ui.collectAsState()
    // Stats change on the audio threads; polling twice a second is plenty for a status line.
    val stats by produceState(initialValue = controller.stats(), controller) {
        while (true) {
            value = controller.stats()
            delay(STATS_POLL_MS)
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.ultra_dj_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        // ---- status ----
        Section(R.string.ultra_dj_section_status)
        Row2(stringResource(R.string.ultra_dj_status_label), statusText(ui.settings.enabled, ui.crossfade.enabled, stats))
        Row2(
            stringResource(R.string.ultra_dj_crossfade),
            when {
                !ui.crossfadeAvailable -> stringResource(R.string.ultra_dj_crossfade_unavailable)
                ui.crossfade.enabled -> stringResource(R.string.ultra_dj_crossfade_on, ui.crossfade.durationMs / 1000f)
                else -> stringResource(R.string.ultra_dj_crossfade_off)
            },
        )
        if (ui.crossfadeAvailable && !ui.crossfade.enabled) {
            Button(
                onClick = { controller.enableCrossfade(ARMING_CROSSFADE_MS) },
                modifier = Modifier.padding(vertical = 8.dp),
            ) { Text(stringResource(R.string.ultra_dj_crossfade_turn_on)) }
        }

        // ---- output ----
        Section(R.string.ultra_dj_section_output)
        val route = ui.route
        Row2(
            stringResource(R.string.ultra_dj_output_device),
            if (route.connected.isEmpty()) {
                stringResource(R.string.ultra_dj_output_none)
            } else {
                listOf(stringResource(kindLabel(route.kind)), route.name).filter { it.isNotBlank() }.joinToString(": ")
            },
        )
        Text(
            text = stringResource(if (route.exact) R.string.ultra_dj_output_source_os else R.string.ultra_dj_output_source_guess),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val profileName = stringResource(profileLabel(ui.profile.id))
        Row2(
            stringResource(R.string.ultra_dj_output_profile),
            stringResource(
                if (ui.settings.profileOverride == null) R.string.ultra_dj_profile_auto_suffix else R.string.ultra_dj_profile_manual_suffix,
                profileName,
            ),
        )
        if ((route.kind == OutputKind.BLUETOOTH || route.kind == OutputKind.CAR) && route.name.isNotBlank()) {
            SwitchRow(
                label = stringResource(R.string.ultra_dj_car_toggle, route.name),
                checked = route.kind == OutputKind.CAR,
                onChange = { controller.setCarDevice(route.name, it) },
            )
            Text(
                text = stringResource(R.string.ultra_dj_car_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- settings ----
        Section(R.string.ultra_dj_section_settings)
        SwitchRow(
            label = stringResource(R.string.ultra_dj_enabled),
            checked = ui.settings.enabled,
            onChange = controller::setEnabled,
        )
        Text(
            text = stringResource(R.string.ultra_dj_profile_choice),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        ProfileChoice(R.string.ultra_dj_profile_auto, selected = ui.settings.profileOverride == null) { controller.setProfileOverride(null) }
        ProfileId.entries.forEach { id ->
            ProfileChoice(profileLabel(id), selected = ui.settings.profileOverride == id) { controller.setProfileOverride(id) }
        }

        // ---- diagnostics ----
        Section(R.string.ultra_dj_section_diagnostics)
        Row2(stringResource(R.string.ultra_dj_diag_decks), stats.decksAlive.toString())
        Row2(stringResource(R.string.ultra_dj_diag_mixed), stats.transitionsMixed.toString())
        Row2(
            stringResource(R.string.ultra_dj_diag_last),
            if (stats.lastTransitionMs > 0) {
                stringResource(R.string.ultra_dj_diag_last_value, stats.lastTransitionMs / 1000f)
            } else {
                stringResource(R.string.ultra_dj_diag_none)
            },
        )
        Row2(stringResource(R.string.ultra_dj_diag_peak), stats.peakGuardBlocks.toString())
        Row2(stringResource(R.string.ultra_dj_diag_path), stringResource(R.string.ultra_dj_diag_path_value))
    }
}

@Composable
private fun statusText(
    enabled: Boolean,
    crossfadeOn: Boolean,
    stats: DjStats,
): String =
    when {
        !enabled -> stringResource(R.string.ultra_dj_status_off)
        !crossfadeOn -> stringResource(R.string.ultra_dj_status_waiting_crossfade)
        stats.mixing -> stringResource(R.string.ultra_dj_status_mixing, (stats.transitionProgress * 100).roundToInt())
        else -> stringResource(R.string.ultra_dj_status_armed)
    }

@StringRes
private fun kindLabel(kind: OutputKind): Int =
    when (kind) {
        OutputKind.SPEAKER -> R.string.ultra_dj_kind_speaker
        OutputKind.WIRED -> R.string.ultra_dj_kind_wired
        OutputKind.USB -> R.string.ultra_dj_kind_usb
        OutputKind.BLUETOOTH -> R.string.ultra_dj_kind_bluetooth
        OutputKind.CAR -> R.string.ultra_dj_kind_car
        OutputKind.HDMI -> R.string.ultra_dj_kind_hdmi
        OutputKind.OTHER -> R.string.ultra_dj_kind_other
    }

@StringRes
private fun profileLabel(id: ProfileId): Int =
    when (id) {
        ProfileId.SPEAKER -> R.string.ultra_dj_profile_speaker
        ProfileId.WIRED -> R.string.ultra_dj_profile_wired
        ProfileId.BLUETOOTH -> R.string.ultra_dj_profile_bluetooth
        ProfileId.CAR -> R.string.ultra_dj_profile_car
        ProfileId.STUDIO -> R.string.ultra_dj_profile_studio
    }

@Composable
private fun Section(
    @StringRes title: Int,
) {
    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Row2(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProfileChoice(
    @StringRes label: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text = stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}

private const val STATS_POLL_MS = 500L

/** What the one-tap button sets: long enough for a bass swap to be audible, short enough not to eat a song. */
private const val ARMING_CROSSFADE_MS = 6_000
