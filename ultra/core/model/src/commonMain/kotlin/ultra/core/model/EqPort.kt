package ultra.core.model

import kotlinx.coroutines.flow.StateFlow

public enum class EqFilterType { LowShelf, Peaking, HighShelf }

public data class EqBand(
    public val frequencyHz: Float,
    public val gainDb: Float,
    public val q: Float = 1f,
    public val type: EqFilterType = EqFilterType.Peaking,
) {
    init {
        require(frequencyHz > 0f) { "frequencyHz must be positive: $frequencyHz" }
        require(q > 0f) { "q must be positive: $q" }
    }
}

public data class EqState(
    public val enabled: Boolean = false,
    public val preampDb: Float = 0f,
    public val bands: List<EqBand> = emptyList(),
)

public data class EqPreset(
    public val name: String,
    public val preampDb: Float = 0f,
    public val bands: List<EqBand>,
) {
    init {
        require(name.isNotBlank()) { "preset name must not be blank" }
    }
}

/**
 * The equalizer as ultra features see it. An adapter in :app implements it over upstream's
 * equalizer service; hardware-aware profiles (wired, Bluetooth, car) switch presets through it, so
 * they never touch upstream's equalizer classes.
 */
public interface EqPort {
    public val state: StateFlow<EqState>

    public fun setEnabled(enabled: Boolean)

    public fun setPreampDb(preampDb: Float)

    public fun setBandGainDb(
        index: Int,
        gainDb: Float,
    )

    /** Replaces the bands and preamp with the preset's; the enabled flag is left as is. */
    public fun apply(preset: EqPreset)

    /** Zeroes every band and the preamp. */
    public fun reset()
}
