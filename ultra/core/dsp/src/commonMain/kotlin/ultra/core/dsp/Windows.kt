package ultra.core.dsp

import kotlin.math.PI
import kotlin.math.cos

public enum class WindowType { Rectangular, Hann, Hamming, Blackman }

/**
 * Builds a window of [size] samples. [periodic] windows (the default) are the right choice for spectral
 * analysis, since they tile seamlessly across frames; symmetric ones (`periodic = false`) suit filter design.
 */
public fun window(
    type: WindowType,
    size: Int,
    periodic: Boolean = true,
): DoubleArray {
    require(size >= 1) { "size must be at least 1: $size" }
    if (size == 1) return doubleArrayOf(1.0)
    val denominator = (if (periodic) size else size - 1).toDouble()
    return DoubleArray(size) { n ->
        val phase = 2.0 * PI * n / denominator
        when (type) {
            WindowType.Rectangular -> 1.0
            WindowType.Hann -> 0.5 - 0.5 * cos(phase)
            WindowType.Hamming -> 0.54 - 0.46 * cos(phase)
            WindowType.Blackman -> 0.42 - 0.5 * cos(phase) + 0.08 * cos(2.0 * phase)
        }
    }
}
