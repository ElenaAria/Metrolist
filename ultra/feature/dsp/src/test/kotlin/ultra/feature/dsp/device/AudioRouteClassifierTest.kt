package ultra.feature.dsp.device

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRouteClassifierTest {
    private fun device(
        type: Int,
        name: String = "",
        id: Int = 1,
    ) = OutputDevice(id, type, name)

    @Test
    fun builtInOutputsAreSpeaker() {
        assertEquals(OutputKind.SPEAKER, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ""))
        assertEquals(OutputKind.SPEAKER, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, ""))
    }

    @Test
    fun wiredOutputs() {
        assertEquals(OutputKind.WIRED, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "WH-1000XM5"))
        assertEquals(OutputKind.WIRED, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, ""))
        assertEquals(OutputKind.WIRED, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_LINE_ANALOG, ""))
    }

    @Test
    fun usbOutputs() {
        assertEquals(OutputKind.USB, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_USB_DEVICE, "DAC"))
        assertEquals(OutputKind.USB, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_USB_HEADSET, ""))
    }

    @Test
    fun bluetoothIsBluetoothUnlessTheNameSaysCar() {
        assertEquals(OutputKind.BLUETOOTH, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "WF-1000XM5"))
        assertEquals(OutputKind.BLUETOOTH, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_BLE_HEADSET, "Nothing Ear"))
        assertEquals(OutputKind.CAR, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "BMW 320i"))
        assertEquals(OutputKind.CAR, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Toyota Audio"))
    }

    @Test
    fun carDetectionMatchesWholeWordsOnly() {
        assertFalse(AudioRouteClassifier.looksLikeCar("Carbon", emptySet()))
        assertFalse(AudioRouteClassifier.looksLikeCar("Carl's buds", emptySet()))
        assertTrue(AudioRouteClassifier.looksLikeCar("My Car", emptySet()))
        assertTrue(AudioRouteClassifier.looksLikeCar("Audi MMI", emptySet()))
    }

    @Test
    fun userMarkedNamesAreCars() {
        val marked = setOf("Living Room Speaker")
        assertEquals(OutputKind.CAR, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Living Room Speaker", marked))
        // Marking only affects Bluetooth outputs, never a wired one.
        assertEquals(OutputKind.WIRED, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Living Room Speaker", marked))
    }

    @Test
    fun busIsCarAndHdmiIsHdmi() {
        assertEquals(OutputKind.CAR, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_BUS, ""))
        assertEquals(OutputKind.HDMI, AudioRouteClassifier.kindOf(AudioDeviceInfo.TYPE_HDMI, "TV"))
    }

    @Test
    fun unknownTypeIsOther() {
        assertEquals(OutputKind.OTHER, AudioRouteClassifier.kindOf(9999, "mystery"))
    }

    @Test
    fun activeRouteWinsAndIsExact() {
        val connected = listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, id = 1), device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "buds", id = 2))
        val route = AudioRouteClassifier.resolve(connected, active = listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "buds", id = 2)))
        assertEquals(OutputKind.BLUETOOTH, route.kind)
        assertEquals("buds", route.name)
        assertTrue(route.exact)
    }

    @Test
    fun withoutAnActiveRouteTheGuessPrefersWiredOverBluetoothOverSpeaker() {
        val connected =
            listOf(
                device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, id = 1),
                device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "buds", id = 2),
                device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "cans", id = 3),
            )
        val route = AudioRouteClassifier.resolve(connected, active = null)
        assertEquals(OutputKind.WIRED, route.kind)
        assertFalse(route.exact)

        val noCable = connected.filter { it.id != 3 }
        assertEquals(OutputKind.BLUETOOTH, AudioRouteClassifier.resolve(noCable, active = null).kind)
    }

    @Test
    fun emptyActiveListFallsBackToTheGuess() {
        val connected = listOf(device(AudioDeviceInfo.TYPE_USB_DEVICE, "DAC"))
        val route = AudioRouteClassifier.resolve(connected, active = emptyList())
        assertEquals(OutputKind.USB, route.kind)
        assertFalse(route.exact)
    }

    @Test
    fun nothingConnectedIsUnknown() {
        val route = AudioRouteClassifier.resolve(emptyList(), active = null)
        assertEquals(OutputKind.OTHER, route.kind)
        assertTrue(route.connected.isEmpty())
    }
}
