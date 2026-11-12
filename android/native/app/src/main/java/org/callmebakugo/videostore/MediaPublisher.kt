package org.callmebakugo.videostore

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

class MediaPublisher(private val context: Context) {
    /** Journal the row before copying. Recovery also sweeps the insert/journal crash window. */
    fun publish(file: File, mime: String, cancelled: () -> Boolean, displayName: String = file.name, journal: (Uri) -> Unit): Uri {
        require(file.isFile) { "Download file is missing" }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/VideoStore")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        try {
            journal(uri)
            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check(!cancelled()) { "Cancelled" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(!cancelled()) { "Cancelled" }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
            return uri
        } catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
    }
    fun recover() {
        val resolver = context.contentResolver
        Jobs.snapshot().filter { it.pending.isNotEmpty() && it.destination.isEmpty() }.forEach { job ->
            val uri = Uri.parse(job.pending)
            val published = resolver.query(uri, arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null)?.use {
                it.moveToFirst() && it.getInt(0) == 0
            } ?: false
            if (published) Jobs.update(job.id) { it.copy(status = "Complete", progress = 100, uri = job.pending, pending = "") }
            else { resolver.delete(uri, null, null); Jobs.update(job.id) { it.copy(status = recoveredPublicationStatus(it.status, false), pending = "", progress = 0) } }
        }
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.IS_PENDING}=1 AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(context.packageName, "Download/VideoStore/"), null)?.use { rows ->
            while (rows.moveToNext()) resolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, rows.getLong(0)), null, null)
        }
        Jobs.snapshot().filter { it.status == "Complete" || it.status == "Cancelled" }.forEach { j ->
            File(context.filesDir, "downloads/${j.id}").deleteRecursively()
        }
        Jobs.snapshot().filter { it.status == "Cancelling" }.forEach { j -> Jobs.update(j.id) { it.copy(status = "Cancelled") } }
        Jobs.snapshot().filter { it.status in listOf("Reading playlist", "Fetching details", "Downloading", "Converting", "Saving", "Publishing") }.forEach { j -> Jobs.update(j.id) { it.copy(status = "Queued", progress = 0) } }
    }
}
