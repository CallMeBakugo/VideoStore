package org.callmebakugo.videostore

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Job(val id: String, val url: String, val format: Int, val status: String = "Queued", val progress: Int = 0, val uri: String = "", val pending: String = "", val title: String = "", val thumbnail: String = "", val speed: String = "", val eta: String = "", val details: String = "", val message: String = "")
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
                items.add(Job(j.getString("id"), j.getString("url"), j.getInt("format").coerceIn(0, 4), j.getString("status"), j.optInt("progress"), j.optString("uri"), j.optString("pending"), j.optString("title"), j.optString("thumbnail"), details = j.optString("details"), message = j.optString("message")))
            }
        }
        loaded = true
    }
    @Synchronized fun snapshot(): List<Job> = items.toList()
    @Synchronized fun add(url: String, format: Int): Job {
        val job = Job(UUID.randomUUID().toString(), url, format)
        items.add(job); save(); return job
    }
    @Synchronized fun update(id: String, transform: (Job) -> Job) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) { items[index] = transform(items[index]); save() }
    }
    @Synchronized fun retry(id: String) = update(id) {
        it.copy(status = "Queued", speed = "", eta = "", details = "", message = "")
    }
    @Synchronized fun remove(id: String, context: Context) {
        items.removeAll { it.id == id }
        File(context.filesDir, "downloads/$id").deleteRecursively()
        save()
    }
    private fun save() {
        val array = JSONArray()
        items.forEach { j -> array.put(JSONObject().put("id", j.id).put("url", j.url).put("format", j.format).put("status", j.status).put("progress", j.progress).put("uri", j.uri).put("pending", j.pending).put("title", j.title).put("thumbnail", j.thumbnail).put("details", j.details).put("message", j.message)) }
        val stream = file.startWrite()
        try { stream.write(array.toString().toByteArray()); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
val formatLabels = listOf("Best video+audio", "Best ≤1080p", "Best ≤720p", "Audio only (mp3)", "Audio only (best)")
val formatSelectors = listOf("bv*+ba/b", "bv*[height<=1080]+ba/b[height<=1080]/b", "bv*[height<=720]+ba/b[height<=720]/b", "ba/b", "ba/b")
