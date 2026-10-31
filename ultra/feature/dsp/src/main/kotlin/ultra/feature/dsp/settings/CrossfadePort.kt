package ultra.feature.dsp.settings

import kotlinx.coroutines.flow.Flow

/** Upstream's crossfade as the Smart DJ engine needs to see it. */
data class CrossfadeState(
    val enabled: Boolean,
    val durationMs: Int,
) {
    companion object {
        /** Used when nothing provides the port: no crossfade means no overlap, so the engine stays passive. */
        val UNAVAILABLE = CrossfadeState(enabled = false, durationMs = 5_000)
    }
}

/**
 * The only thing the DJ engine needs from upstream: whether the two-player crossfade is on, and for
 * how long. The crossfade itself (scheduling, the second player, the volume ramp) is upstream's; this
 * engine shapes it but cannot start it.
 *
 * Implemented in :app's ultra glue (it owns the upstream preference keys) and bound optionally, so
 * this module never depends on :app.
 */
interface CrossfadePort {
    val state: Flow<CrossfadeState>

    /** Turns the crossfade on with [durationMs]. A convenience so the DJ screen can arm the engine in one tap. */
    suspend fun enable(durationMs: Int)
}
