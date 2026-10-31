package ultra.feature.dsp.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ultra.feature.dsp.device.OutputKind
import ultra.feature.dsp.device.OutputRoute
import ultra.feature.dsp.engine.ProfileId

class DjDecisionTest {
    private fun route(kind: OutputKind) = OutputRoute(kind, "device", emptyList(), exact = true)

    @Test
    fun outputKindMapsToProfile() {
        assertEquals(ProfileId.SPEAKER, DjDecision.profileIdFor(OutputKind.SPEAKER))
        assertEquals(ProfileId.WIRED, DjDecision.profileIdFor(OutputKind.WIRED))
        assertEquals(ProfileId.STUDIO, DjDecision.profileIdFor(OutputKind.USB))
        assertEquals(ProfileId.BLUETOOTH, DjDecision.profileIdFor(OutputKind.BLUETOOTH))
        assertEquals(ProfileId.CAR, DjDecision.profileIdFor(OutputKind.CAR))
        assertEquals(ProfileId.WIRED, DjDecision.profileIdFor(OutputKind.HDMI))
        assertEquals(ProfileId.WIRED, DjDecision.profileIdFor(OutputKind.OTHER))
    }

    @Test
    fun manualOverrideBeatsTheDetectedRoute() {
        val settings = DjSettings(profileOverride = ProfileId.STUDIO)
        assertEquals(ProfileId.STUDIO, DjDecision.profileFor(settings, route(OutputKind.SPEAKER)).id)
    }

    @Test
    fun snapshotIsActiveOnlyWhenBothAreOn() {
        val on = DjSettings(enabled = true)
        val crossfade = CrossfadeState(enabled = true, durationMs = 6_000)
        assertTrue(DjDecision.snapshot(on, route(OutputKind.WIRED), crossfade).active)

        assertFalse(DjDecision.snapshot(on.copy(enabled = false), route(OutputKind.WIRED), crossfade).active)
        assertFalse(DjDecision.snapshot(on, route(OutputKind.WIRED), crossfade.copy(enabled = false)).active)
    }

    @Test
    fun transitionLengthComesFromTheCrossfade() {
        val snap = DjDecision.snapshot(DjSettings(), route(OutputKind.CAR), CrossfadeState(true, 8_000))
        assertEquals(8_000, snap.transitionMs)
        assertEquals(ProfileId.CAR, snap.profile.id)
    }
}
