package com.taiyeb.pocketguard

import android.bluetooth.BluetoothClass
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat

/** Auto-arms when earbuds or a watch connect. Disconnects are handled by [GuardService] while armed. */
class BluetoothReceiver : BroadcastReceiver() {

    companion object {
        /** Earbuds, headphones, speakers and watches: the things you carry next to your phone. */
        @SuppressLint("MissingPermission")
        fun isListeningDevice(device: BluetoothDevice): Boolean = try {
            when (device.bluetoothClass?.majorDeviceClass) {
                BluetoothClass.Device.Major.AUDIO_VIDEO, BluetoothClass.Device.Major.WEARABLE -> true
                else -> false
            }
        } catch (e: SecurityException) {
            false // Nearby devices permission not granted.
        }

        /** Whether this device should drive earbud triggers: the user's picks, or any listening device. */
        @SuppressLint("MissingPermission")
        fun counts(context: Context, device: BluetoothDevice): Boolean {
            val trusted = Prefs.trustedDevices(context)
            if (trusted.isEmpty()) return isListeningDevice(device)
            return try {
                device.address in trusted
            } catch (e: SecurityException) {
                false
            }
        }

        /** Paired earbuds, headphones and watches as (name, address), for the device picker. */
        @SuppressLint("MissingPermission")
        fun pairedDevices(context: Context): List<Pair<String, String>> = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .filter { isListeningDevice(it) }
                .map { (it.name ?: it.address) to it.address }
                .sortedBy { it.first.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!Prefs.autoArmEarbuds(context) || GuardState.phase.value != Phase.OFF) return
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        if (device == null || !counts(context, device)) return
        try {
            GuardService.arm(context, auto = true)
        } catch (e: Exception) {
            // Background start refused (battery optimization still on).
        }
    }
}
