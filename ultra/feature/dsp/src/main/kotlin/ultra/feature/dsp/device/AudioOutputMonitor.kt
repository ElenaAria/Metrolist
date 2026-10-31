package ultra.feature.dsp.device

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the OS audio manager for output changes (Bluetooth earbuds connecting, a cable plugged in,
 * a car head unit appearing) and publishes the raw facts; classification lives in
 * [AudioRouteClassifier] so the logic stays testable without a device.
 *
 * Uses `AudioDeviceCallback` (no permission needed). Callbacks arrive on the main looper and only
 * re-read two cheap lists, so there is nothing to throttle. It is started once and lives as long as
 * the process, which is the same lifetime as the player service it serves.
 */
class AudioOutputMonitor(
    context: Context,
) {
    data class Devices(
        val connected: List<OutputDevice>,
        /** What the OS says media is routed to (API 33+), null where the API does not exist. */
        val active: List<OutputDevice>?,
    )

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _devices = MutableStateFlow(Devices(emptyList(), null))
    val devices: StateFlow<Devices> = _devices.asStateFlow()

    private var started = false

    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
        }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        try {
            audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        } catch (e: RuntimeException) {
            // Without the callback we still have the one-off read below; never let this break playback.
            Log.w(TAG, "Could not register for audio device changes", e)
        }
        refresh()
    }

    /** Re-reads the lists now (also useful when the screen opens). */
    fun refresh() {
        try {
            val connected =
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.toOutputDevice() }
            val active =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    audioManager.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES).map { it.toOutputDevice() }
                } else {
                    null
                }
            _devices.value = Devices(connected, active)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not read audio output devices", e)
        }
    }

    private fun AudioDeviceInfo.toOutputDevice() = OutputDevice(id = id, type = type, name = productName?.toString().orEmpty())

    private companion object {
        const val TAG = "UltraDj"
        val MEDIA_ATTRIBUTES: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
    }
}
