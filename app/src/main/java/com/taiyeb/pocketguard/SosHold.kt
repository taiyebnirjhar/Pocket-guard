package com.taiyeb.pocketguard

/**
 * The SOS key gesture: volume up held for [HOLD_MS]. One button, found by feel, and used by
 * nothing else (Android's own key shortcuts need two buttons). Pressing volume down as well
 * cancels it. [post] and [cancel] run a delayed task (a Handler on the phone, a fake clock in
 * tests). Main thread.
 */
class SosHold(
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onHold: (gesture: String) -> Unit,
) {
    companion object {
        /** Longer than a tap or a short volume change. */
        const val HOLD_MS = 2000L
    }

    private var upDown = false
    private var downDown = false
    private val fire = Runnable { if (upDown && !downDown) onHold("volume up held") }

    fun press(up: Boolean, down: Boolean) {
        val wasHolding = upDown && !downDown
        if (up) upDown = down else downDown = down
        val holding = upDown && !downDown
        if (holding && !wasHolding) post(fire, HOLD_MS)
        if (!holding) cancel(fire)
    }

    fun reset() {
        upDown = false
        downDown = false
        cancel(fire)
    }
}
