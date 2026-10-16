package com.taiyeb.pocketguard

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat

/** Auto-arms when earbuds or a watch connect. Disconnects are handled by [GuardService] while armed. */
class BluetoothReceiver : BroadcastReceiver() {

    companion object {
        /** Earbuds, headphones, speakers and watches: the things you carry next to your phone. */
        fun isListeningDevice(device: BluetoothDevice): Boolean = try {
            when (device.bluetoothClass?.majorDeviceClass) {
                BluetoothClass.Device.Major.AUDIO_VIDEO, BluetoothClass.Device.Major.WEARABLE -> true
                else -> false
            }
        } catch (e: SecurityException) {
            false // Nearby devices permission not granted.
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!Prefs.autoArmEarbuds(context) || GuardState.phase.value != Phase.OFF) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        if (device == null || !isListeningDevice(device)) return
        try {
            GuardService.arm(context, auto = true)
        } catch (e: Exception) {
            // Background start refused (battery optimization still on).
        }
    }
}
