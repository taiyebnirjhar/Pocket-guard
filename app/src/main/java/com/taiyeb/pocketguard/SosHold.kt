package com.taiyeb.pocketguard

/**
 * The SOS key gestures, from the volume keys going down and up: both held together for
 * [BOTH_MS], or volume up alone held for [UP_MS]. [post] and [cancel] run a delayed task (a
 * Handler on the phone, a fake clock in tests). Main thread.
 */
class SosHold(
    private val post: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onHold: (gesture: String) -> Unit,
) {
    companion object {
        const val BOTH_MS = 3000L

        /** Longer than a volume change: holding it this long is on purpose. */
        const val UP_MS = 3000L
    }

    private var upDown = false
    private var downDown = false
    private val fireBoth = Runnable { if (upDown && downDown) onHold("both volume keys held") }
    private val fireUp = Runnable { if (upDown && !downDown) onHold("volume up held") }

    fun press(up: Boolean, down: Boolean) {
        val wasBoth = upDown && downDown
        val wasUpAlone = upDown && !downDown
        if (up) upDown = down else downDown = down
        val both = upDown && downDown
        val upAlone = upDown && !downDown
        if (both && !wasBoth) post(fireBoth, BOTH_MS)
        if (!both) cancel(fireBoth)
        // Pressing volume down too switches to the two-key hold; letting it go starts the count again.
        if (upAlone && !wasUpAlone) post(fireUp, UP_MS)
        if (!upAlone) cancel(fireUp)
    }

    fun reset() {
        upDown = false
        downDown = false
        cancel(fireBoth)
        cancel(fireUp)
    }
}
