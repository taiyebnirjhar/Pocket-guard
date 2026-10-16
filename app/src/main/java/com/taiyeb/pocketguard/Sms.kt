package com.taiyeb.pocketguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager

/** Plain SMS from the phone's own SIM: works without mobile data, which a thief may switch off. */
object Sms {
    fun canSend(context: Context) =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Returns how many contacts the message was handed to. */
    @SuppressLint("MissingPermission")
    fun send(context: Context, numbers: List<String>, text: String): Int {
        if (numbers.isEmpty() || !canSend(context)) return 0
        val manager = try {
            if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } catch (e: Exception) {
            null
        } ?: return 0
        var sent = 0
        for (number in numbers) {
            try {
                manager.sendMultipartTextMessage(number, null, manager.divideMessage(text), null, null)
                sent++
            } catch (e: Exception) {
                // No SIM, no signal or no default SIM for SMS: try the next contact.
            }
        }
        return sent
    }
}
