package com.taiyeb.pocketguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/** Plain SMS from the phone's own SIM: works without mobile data, which a thief may switch off. */
object Sms {
    fun canSend(context: Context) =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Fire-and-forget, for the test message. Returns how many contacts it was handed to. */
    fun send(context: Context, numbers: List<String>, text: String): Int =
        numbers.count { sendOne(context, it, text, null) }

    /** Hands one message to the radio. [sent] receives the result of every part. */
    @SuppressLint("MissingPermission")
    fun sendOne(context: Context, number: String, text: String, sent: PendingIntent?): Boolean {
        if (!canSend(context)) return false
        return try {
            val manager = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = manager.divideMessage(text)
            val sentIntents = if (sent == null) null else ArrayList(parts.map { sent })
            manager.sendMultipartTextMessage(number, null, parts, sentIntents, null)
            true
        } catch (e: Exception) {
            false // No SIM, or no default SIM for SMS.
        }
    }
}

/**
 * Sends alerts and checks the radio's result for each one. Failed sends (airplane mode, no signal)
 * are retried every minute, and at once when [retryNow] is called. Main thread only.
 */
class SmsSender(private val context: Context) {

    companion object {
        private const val ACTION_SENT = "com.taiyeb.pocketguard.SMS_SENT"
        private const val EXTRA_ID = "id"
        private const val RETRY_MS = 60_000L
        private const val MAX_ATTEMPTS = 15
    }

    private class Message(
        val id: Int,
        val incidentId: Long,
        val number: String,
        val text: String,
        val onSent: () -> Unit,
    ) {
        var attempts = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private val inFlight = mutableMapOf<Int, Message>()
    private val waiting = mutableListOf<Message>()
    private var nextId = 1
    private val retry = Runnable { retryNow() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Multipart messages report once per part; the first result decides.
            val message = inFlight.remove(intent.getIntExtra(EXTRA_ID, -1)) ?: return
            val ok = resultCode == Activity.RESULT_OK
            Log.i(GuardService.TAG, "sms ${if (ok) "sent" else "failed ($resultCode)"} attempt=${message.attempts}")
            if (ok) message.onSent() else queue(message)
        }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** [onSent] runs once per contact whose message the network accepted. */
    fun send(incidentId: Long, numbers: List<String>, text: String, onSent: () -> Unit) {
        numbers.forEach { attempt(Message(nextId++, incidentId, it, text, onSent)) }
    }

    /** Drops queued messages for an incident, e.g. after the owner unlocked. */
    fun cancel(incidentId: Long) {
        waiting.removeAll { it.incidentId == incidentId }
        inFlight.values.removeAll { it.incidentId == incidentId }
    }

    fun retryNow() {
        handler.removeCallbacks(retry)
        val batch = waiting.toList()
        waiting.clear()
        batch.forEach(::attempt)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private fun attempt(message: Message) {
        message.attempts++
        val intent = Intent(ACTION_SENT).setPackage(context.packageName).putExtra(EXTRA_ID, message.id)
        val sent = PendingIntent.getBroadcast(
            context, message.id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        inFlight[message.id] = message
        if (!Sms.sendOne(context, message.number, message.text, sent)) {
            inFlight.remove(message.id)
            queue(message)
        }
    }

    private fun queue(message: Message) {
        if (message.attempts >= MAX_ATTEMPTS) return
        waiting += message
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, RETRY_MS)
    }
}
