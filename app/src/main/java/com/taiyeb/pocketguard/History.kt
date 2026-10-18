package com.taiyeb.pocketguard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One alarm, from the first warning to the unlock that ended it. Mutated only on the main thread. */
data class Incident(
    val id: Long,
    val startedAt: Long,
    val trigger: Trigger,
    var escalated: Boolean = false,
    var endedAt: Long? = null,
    var ownerStopped: Boolean = false,
    /** Cancelled because the pocket shifted over the sensor, not a removal. */
    var flicker: Boolean = false,
    val photos: MutableList<String> = mutableListOf(),
    /** SOS recordings, in order (file names in [History.videosDir]). */
    val videos: MutableList<String> = mutableListOf(),
)

/** Theft history, newest first, stored as JSON in app-private storage. */
object History {
    private const val FILE = "incidents.json"
    private const val MAX = 50
    private const val DAY_MS = 24 * 60 * 60_000L

    /** SOS recordings kept on the phone at most; the oldest SOS's go first. */
    private const val MAX_VIDEO_BYTES = 1024L * 1024 * 1024

    /** An entry still open this long was left behind by a restart (no alarm runs half a day). */
    private const val STALE_OPEN_MS = 12 * 60 * 60_000L

    /** Unlisted files younger than this may still be on their way into the list. */
    private const val RECENT_FILE_MS = 5 * 60_000L

    /** Warnings that never escalated are kept a week, real alarms 90 days. */
    private fun expired(incident: Incident, now: Long) =
        now - incident.startedAt > (if (incident.escalated) 90 else 7) * DAY_MS

    /** Bumped on every write so screens showing the history reload it. */
    val version = MutableStateFlow(0)

    fun photosDir(context: Context) = File(context.filesDir, "intruders").apply { mkdirs() }

    fun videosDir(context: Context) = File(context.filesDir, "sos").apply { mkdirs() }

    fun load(context: Context): List<Incident> = read(context) ?: emptyList()

    /** Null when the file exists but can't be read, so [save] doesn't overwrite it with one entry. */
    private fun read(context: Context): List<Incident>? {
        return try {
            val file = File(context.filesDir, FILE)
            if (!file.exists()) return emptyList()
            val array = JSONArray(file.readText())
            // One damaged entry must not cost the rest.
            (0 until array.length()).mapNotNull {
                try {
                    fromJson(array.getJSONObject(it))
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            null // Also before the first unlock after a restart, when app storage is locked.
        }
    }

    // save, prune and clear each read, change and write the whole list: one at a time (prune runs on
    // a background thread at app start while an alarm may be saving on the main thread).

    @Synchronized
    fun save(context: Context, incident: Incident) {
        try {
            val now = System.currentTimeMillis()
            val earlier = read(context)
            if (earlier == null) {
                // Unreadable: keep it aside for inspection instead of silently losing it, and keep
                // every photo and recording too (they're no longer listed, but they're evidence).
                File(context.filesDir, FILE).renameTo(File(context.filesDir, "$FILE.damaged"))
            }
            val all = (earlier ?: emptyList()).filter { it.id != incident.id }.toMutableList()
            all.add(0, incident)
            val kept = all.filter { it.id == incident.id || !expired(it, now) }
                .sortedByDescending { it.startedAt }
                .take(MAX)
            if (earlier != null) deleteUnlisted(context, kept, keep = setOf(incident.id))
            write(context, kept)
        } catch (e: Exception) {
            // Storage locked before the first unlock; the alarm and SMS still work.
        }
    }

    /**
     * Drops expired entries and their files, and the oldest recordings past [MAX_VIDEO_BYTES]
     * (run at app start, so a phone that never alarms again still cleans up).
     */
    @Synchronized
    fun prune(context: Context) {
        try {
            val now = System.currentTimeMillis()
            val all = read(context) ?: return
            val kept = all.filter { !expired(it, now) }.sortedByDescending { it.startedAt }.take(MAX)
            var changed = kept.size != all.size
            // Left open by a restart mid-alarm (the alarm came back as a new entry): close it, so
            // it can expire like the others.
            for (incident in kept) {
                if (incident.endedAt == null && now - incident.startedAt > STALE_OPEN_MS) {
                    incident.endedAt = incident.startedAt
                    changed = true
                }
            }
            var bytes = kept.sumOf { i -> i.videos.sumOf { File(videosDir(context), it).length() } }
            for (incident in kept.asReversed()) {
                if (bytes <= MAX_VIDEO_BYTES) break
                if (incident.endedAt == null || incident.videos.isEmpty()) continue // Still recording.
                bytes -= incident.videos.sumOf { File(videosDir(context), it).length() }
                incident.videos.clear()
                changed = true
            }
            // Entries still running may be writing a file they don't list yet.
            deleteUnlisted(context, kept, keep = kept.filter { it.endedAt == null }.map { it.id }.toSet())
            if (changed) {
                write(context, kept)
                version.value++
            }
        } catch (e: Exception) {
            // Storage locked.
        }
    }

    /**
     * Photos and recordings of no listed entry; [keep]: entries still adding files. Files written in
     * the last few minutes stay too: the last SOS piece is listed only once it finishes saving,
     * after its entry has ended.
     */
    private fun deleteUnlisted(context: Context, kept: List<Incident>, keep: Set<Long>) {
        val recent = System.currentTimeMillis() - RECENT_FILE_MS
        val keptPhotos = kept.flatMap { it.photos }.toSet()
        photosDir(context).listFiles()
            ?.filter { f -> f.name !in keptPhotos && keep.none { f.name.startsWith("i${it}_") || f.name.startsWith("i$it.") } }
            ?.filter { it.lastModified() < recent }
            ?.forEach { it.delete() }
        val keptVideos = kept.flatMap { it.videos }.toSet()
        videosDir(context).listFiles()
            ?.filter { f -> f.name !in keptVideos && keep.none { f.name.startsWith("s${it}_") } }
            ?.filter { it.lastModified() < recent }
            ?.forEach { it.delete() }
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE).delete()
            photosDir(context).listFiles()?.forEach { it.delete() }
            videosDir(context).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // Storage locked.
        }
        version.value++
    }

    private fun write(context: Context, incidents: List<Incident>) {
        val array = JSONArray()
        incidents.forEach { array.put(toJson(it)) }
        val file = File(context.filesDir, FILE)
        val temp = File(context.filesDir, "$FILE.tmp")
        temp.writeText(array.toString())
        temp.renameTo(file)
        version.value++
    }

    private fun toJson(incident: Incident) = JSONObject().apply {
        put("id", incident.id)
        put("startedAt", incident.startedAt)
        put("trigger", incident.trigger.name)
        put("escalated", incident.escalated)
        incident.endedAt?.let { put("endedAt", it) }
        put("ownerStopped", incident.ownerStopped)
        put("flicker", incident.flicker)
        put("photos", JSONArray().apply { incident.photos.forEach { put(it) } })
        put("videos", JSONArray().apply { incident.videos.forEach { put(it) } })
    }

    private fun fromJson(json: JSONObject): Incident? {
        val trigger = Trigger.entries.firstOrNull { it.name == json.optString("trigger") } ?: return null
        val photos = json.optJSONArray("photos")
        val videos = json.optJSONArray("videos")
        return Incident(
            id = json.getLong("id"),
            startedAt = json.getLong("startedAt"),
            trigger = trigger,
            escalated = json.optBoolean("escalated"),
            endedAt = if (json.has("endedAt")) json.getLong("endedAt") else null,
            ownerStopped = json.optBoolean("ownerStopped"),
            flicker = json.optBoolean("flicker"),
            photos = (0 until (photos?.length() ?: 0)).map { photos!!.getString(it) }.toMutableList(),
            videos = (0 until (videos?.length() ?: 0)).map { videos!!.getString(it) }.toMutableList(),
        )
    }
}
