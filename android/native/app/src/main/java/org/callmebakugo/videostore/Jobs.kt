package org.callmebakugo.videostore

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Job(val id: String, val url: String, val format: Int, val status: String = "Queued", val progress: Int = 0, val uri: String = "", val pending: String = "", val title: String = "", val thumbnail: String = "", val speed: String = "", val eta: String = "", val details: String = "", val message: String = "", val destination: String = "", val destinationLabel: String = DEFAULT_DESTINATION,
    val playlist: Boolean = false, val playlistIndex: Int = 0, val parentId: String = "", val expanded: Boolean = false,
    val pendingName: String = "", val pendingComplete: Boolean = false)
object Jobs {
    val items = mutableStateListOf<Job>()
    private lateinit var file: AtomicFile
    private var loaded = false
    @Synchronized fun load(context: Context) {
        if (loaded) return
        file = AtomicFile(File(context.filesDir, "queue.json"))
        if (file.baseFile.exists()) {
            val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
            for (i in 0 until array.length()) {
                val j = array.getJSONObject(i)
                items.add(Job(j.getString("id"), j.getString("url"), j.getInt("format").coerceIn(0, 4), j.getString("status"), j.optInt("progress"), j.optString("uri"), j.optString("pending"), j.optString("title"), j.optString("thumbnail"), details = j.optString("details"), message = j.optString("message"),
                    destination = j.optString("destination"), destinationLabel = j.optString("destinationLabel", DEFAULT_DESTINATION),
                    playlist = j.optBoolean("playlist"), playlistIndex = j.optInt("playlistIndex"), parentId = j.optString("parentId"), expanded = j.optBoolean("expanded"),
                    pendingName = j.optString("pendingName"), pendingComplete = j.optBoolean("pendingComplete")))
            }
        }
        loaded = true
    }
    @Synchronized fun snapshot(): List<Job> = items.toList()
    @Synchronized fun addAll(urls: List<String>, format: Int, destination: Destination, playlist: Boolean): List<Job> {
        val jobs = urls.map { Job(UUID.randomUUID().toString(), it, format, destination = destination.tree, destinationLabel = destination.label, playlist = playlist) }
        items.addAll(jobs); save(); return jobs
    }
    /** Parent and all children commit together, so replay after process death cannot duplicate expansion. */
    @Synchronized fun expand(id: String, entries: List<PlaylistEntry>) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0 || items[index].expanded || items[index].status in setOf("Cancelled", "Cancelling")) return
        val parent = items[index]
        val children = playlistChildren(parent, entries)
        items[index] = parent.copy(status = "Added ${children.size} items to queue", expanded = true, speed = "", eta = "")
        items.addAll(index + 1, children)
        save()
    }
    @Synchronized fun update(id: String, transform: (Job) -> Job) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) { items[index] = transform(items[index]); save() }
    }
    @Synchronized fun retry(id: String) = update(id) {
        it.copy(status = "Queued", speed = "", eta = "", details = "", message = "")
    }
    @Synchronized fun remove(id: String, context: Context) {
        val job = items.firstOrNull { it.id == id }
        check(job == null || (job.pending.isEmpty() && job.pendingName.isEmpty())) { "Restore folder access and retry the unfinished save before removing this entry." }
        items.removeAll { it.id == id }
        File(context.filesDir, "downloads/$id").deleteRecursively()
        save()
    }
    private fun save() {
        val array = JSONArray()
        items.forEach { j -> array.put(JSONObject().put("id", j.id).put("url", j.url).put("format", j.format).put("status", j.status).put("progress", j.progress).put("uri", j.uri).put("pending", j.pending).put("title", j.title).put("thumbnail", j.thumbnail).put("details", j.details).put("message", j.message)
            .put("destination", j.destination).put("destinationLabel", j.destinationLabel).put("playlist", j.playlist).put("playlistIndex", j.playlistIndex)
            .put("parentId", j.parentId).put("expanded", j.expanded).put("pendingName", j.pendingName).put("pendingComplete", j.pendingComplete)) }
        val stream = file.startWrite()
        try { stream.write(array.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
val formatLabels = listOf("Best video+audio", "Best ≤1080p", "Best ≤720p", "Audio only (mp3)", "Audio only (best)")
val formatSelectors = listOf("bv*+ba/b", "bv*[height<=1080]+ba/b[height<=1080]/b", "bv*[height<=720]+ba/b[height<=720]/b", "ba/b", "ba/b")
