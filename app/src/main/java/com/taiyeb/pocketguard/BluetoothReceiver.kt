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
            val type = device.bluetoothClass
            when {
                type == null -> false
                type.majorDeviceClass == BluetoothClass.Device.Major.WEARABLE -> true
                // Car stereos and speakers stay behind when you walk off: they must not count.
                type.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO -> type.deviceClass in setOf(
                    BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                    BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                    BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
                    BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED,
                )
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
        if (device == null || !isPaired(device) || !counts(context, device)) return
        try {
            GuardService.arm(context, auto = true)
        } catch (e: Exception) {
            Notifications.showRearm(context) // Background start refused: battery optimization still on.
        }
    }

    /** Only the user's own paired devices; another app could fake the broadcast with any device. */
    @SuppressLint("MissingPermission")
    private fun isPaired(device: BluetoothDevice) = try {
        device.bondState == BluetoothDevice.BOND_BONDED
    } catch (e: SecurityException) {
        false
    }
}
