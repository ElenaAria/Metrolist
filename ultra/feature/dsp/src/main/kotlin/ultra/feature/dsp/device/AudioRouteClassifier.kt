package ultra.feature.dsp.device

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo

/** Coarse kind of audio output, which is all a profile needs to know. */
enum class OutputKind { SPEAKER, WIRED, USB, BLUETOOTH, CAR, HDMI, OTHER }

/** A platform-independent snapshot of one `AudioDeviceInfo`, so classification is testable on the JVM. */
data class OutputDevice(
    val id: Int,
    /** An `AudioDeviceInfo.TYPE_*` constant. */
    val type: Int,
    val name: String,
)

/**
 * @property exact true when the OS itself told us where media would be routed (API 33+); false when
 *   the route was inferred from the list of connected outputs, which is a best guess when several
 *   external outputs are connected at once.
 */
data class OutputRoute(
    val kind: OutputKind,
    val name: String,
    val connected: List<OutputDevice>,
    val exact: Boolean,
) {
    companion object {
        val UNKNOWN = OutputRoute(OutputKind.OTHER, "", emptyList(), exact = false)
    }
}

/**
 * Turns "what is plugged in / routed" into an [OutputKind].
 *
 * ## What can and cannot be known
 * Android exposes the device *type* reliably (speaker, wired, USB, Bluetooth A2DP/LE, HDMI, automotive
 * bus), but nothing that says "this Bluetooth sink is a car stereo". The only real signal is the
 * Bluetooth Class of Device, which needs the BLUETOOTH_CONNECT permission (not requested by upstream)
 * and which not every head unit sets correctly. So a Bluetooth output is classified as [OutputKind.CAR]
 * if (a) its name contains a well-known car/brand word as a whole word, or (b) the user marked this
 * device name as a car in the settings. Both are heuristics and are labelled as such in the UI.
 */
@SuppressLint("InlinedApi") // constants are inlined at compile time; unknown newer types fall to OTHER
object AudioRouteClassifier {
    /** Whole-word matches only, so "Carbon" or "Carl's buds" are not cars. */
    private val carWords =
        setOf(
            "car", "auto", "carplay", "bmw", "audi", "mercedes", "benz", "vw", "volkswagen", "skoda", "seat",
            "porsche", "opel", "ford", "toyota", "lexus", "honda", "mazda", "nissan", "subaru", "hyundai", "kia",
            "volvo", "tesla", "renault", "peugeot", "citroen", "fiat", "jeep", "mini", "uconnect", "sync", "mmi",
        )

    fun kindOf(
        type: Int,
        name: String,
        carDevices: Set<String> = emptySet(),
    ): OutputKind =
        when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            -> OutputKind.SPEAKER

            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            -> OutputKind.WIRED

            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            -> OutputKind.USB

            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
            -> if (looksLikeCar(name, carDevices)) OutputKind.CAR else OutputKind.BLUETOOTH

            // Automotive bus: Android Automotive's own speakers.
            AudioDeviceInfo.TYPE_BUS -> OutputKind.CAR

            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            -> OutputKind.HDMI

            else -> OutputKind.OTHER
        }

    fun looksLikeCar(
        name: String,
        carDevices: Set<String>,
    ): Boolean {
        if (name in carDevices) return true
        return name.lowercase().split(Regex("[^a-z0-9]+")).any { it in carWords }
    }

    /**
     * Picks the output media is (probably) going to.
     *
     * @param active what the OS reports for media routing (API 33+), or null where unavailable
     * @param connected every connected output; used when [active] is null or empty
     */
    fun resolve(
        connected: List<OutputDevice>,
        active: List<OutputDevice>?,
        carDevices: Set<String> = emptySet(),
    ): OutputRoute {
        val exact = !active.isNullOrEmpty()
        val winner =
            if (!active.isNullOrEmpty()) {
                active.first()
            } else {
                // Best guess without the OS answer: external outputs beat the built-in speaker, and a
                // wired/USB connection beats Bluetooth (plugging a cable in is a deliberate act).
                connected.maxByOrNull { priority(kindOf(it.type, it.name, carDevices)) }
            }
        return if (winner == null) {
            OutputRoute.UNKNOWN.copy(connected = connected)
        } else {
            OutputRoute(kindOf(winner.type, winner.name, carDevices), winner.name, connected, exact)
        }
    }

    private fun priority(kind: OutputKind): Int =
        when (kind) {
            OutputKind.USB -> 5
            OutputKind.WIRED -> 4
            OutputKind.BLUETOOTH, OutputKind.CAR -> 3
            OutputKind.HDMI -> 2
            OutputKind.OTHER -> 1
            OutputKind.SPEAKER -> 0
        }
}
