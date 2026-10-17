package com.taiyeb.pocketguard

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last few hundred things the guard saw and decided, with the time of each, so a field test
 * can show why it did or didn't warn. In memory only: it lives as long as the app's process.
 */
object DetectionLog {
    private const val MAX = 400

    class Entry(val at: Long, val text: String)

    private val entries = ArrayDeque<Entry>()

    /** Bumped on every change, for the screen to redraw. */
    val version = MutableStateFlow(0)

    fun add(text: String, at: Long = System.currentTimeMillis()) {
        synchronized(entries) {
            entries.addLast(Entry(at, text))
            while (entries.size > MAX) entries.removeFirst()
        }
        version.value++
    }

    /** Newest first. */
    fun snapshot(): List<Entry> = synchronized(entries) { entries.reversed() }

    fun clear() {
        synchronized(entries) { entries.clear() }
        version.value++
    }

    fun time(entry: Entry): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.at))

    /** Oldest first, one line each: for pasting into a bug report. */
    fun asText(): String = snapshot().asReversed().joinToString("\n") { "${time(it)}  ${it.text}" }
}
