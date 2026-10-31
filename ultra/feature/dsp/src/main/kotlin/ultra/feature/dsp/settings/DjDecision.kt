package ultra.feature.dsp.settings

import ultra.feature.dsp.device.OutputKind
import ultra.feature.dsp.device.OutputRoute
import ultra.feature.dsp.engine.DjProfile
import ultra.feature.dsp.engine.DjProfiles
import ultra.feature.dsp.engine.DjSnapshot
import ultra.feature.dsp.engine.ProfileId

/**
 * The single place where user settings, the detected output and upstream's crossfade become what the
 * audio thread reads. Pure, so the whole device-aware switching rule is covered by unit tests.
 */
object DjDecision {
    fun profileIdFor(kind: OutputKind): ProfileId =
        when (kind) {
            OutputKind.SPEAKER -> ProfileId.SPEAKER
            OutputKind.WIRED -> ProfileId.WIRED
            // A USB output is usually a DAC or a USB headset: the least coloured preset.
            OutputKind.USB -> ProfileId.STUDIO
            OutputKind.BLUETOOTH -> ProfileId.BLUETOOTH
            OutputKind.CAR -> ProfileId.CAR
            // A TV or AV receiver, or something unrecognised: the neutral preset.
            OutputKind.HDMI, OutputKind.OTHER -> ProfileId.WIRED
        }

    fun profileFor(
        settings: DjSettings,
        route: OutputRoute,
    ): DjProfile = DjProfiles.forId(settings.profileOverride ?: profileIdFor(route.kind))

    fun snapshot(
        settings: DjSettings,
        route: OutputRoute,
        crossfade: CrossfadeState,
    ): DjSnapshot =
        DjSnapshot(
            // Both must hold: the user wants it, and upstream's crossfade is producing an overlap to shape.
            active = settings.enabled && crossfade.enabled,
            profile = profileFor(settings, route),
            transitionMs = crossfade.durationMs,
        )
}
