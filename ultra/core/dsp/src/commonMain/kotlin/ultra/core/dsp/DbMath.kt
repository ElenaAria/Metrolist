package ultra.core.dsp

import kotlin.math.log10
import kotlin.math.pow

/** Converts decibels to a linear amplitude (voltage) ratio: `10^(db / 20)`. */
public fun dbToAmplitude(db: Double): Double = 10.0.pow(db / 20.0)

/** Converts a linear amplitude ratio to decibels. Zero (or negative) amplitude is negative infinity. */
public fun amplitudeToDb(amplitude: Double): Double = if (amplitude <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(amplitude)

/** Converts decibels to a linear power ratio: `10^(db / 10)`. */
public fun dbToPower(db: Double): Double = 10.0.pow(db / 10.0)

/** Converts a linear power ratio to decibels. Zero (or negative) power is negative infinity. */
public fun powerToDb(power: Double): Double = if (power <= 0.0) Double.NEGATIVE_INFINITY else 10.0 * log10(power)
