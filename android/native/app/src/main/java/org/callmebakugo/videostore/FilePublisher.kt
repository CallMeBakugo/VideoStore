package org.callmebakugo.videostore

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** MediaStore for the default location; persisted document-tree grants for user-selected folders. */
class FilePublisher(private val context: Context) {
    private val resolver = context.contentResolver
    fun publish(job: Job, file: File, mime: String, name: String, cancelled: () -> Boolean): Uri {
        if (job.destination.isEmpty()) return MediaPublisher(context).publish(file, mime, cancelled, name) { uri ->
            Jobs.update(job.id) { it.copy(pending = uri.toString()) }
        }
        val tree = Uri.parse(job.destination)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // A per-job prefix lets recovery find creation interrupted before the returned URI was journaled.
        val displayName = "VideoStore-${job.id}-$name"
        Jobs.update(job.id) { it.copy(pendingName = displayName, pendingComplete = false) }
        var uri: Uri? = null
        try {
            check(!cancelled()) { "Cancelled" }
            uri = checkNotNull(DocumentsContract.createDocument(resolver, root, mime, displayName)) { "Could not create a file in the selected folder" }
            Jobs.update(job.id) { it.copy(pending = uri.toString()) }
            checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
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
            // A closed output stream must be recorded before Complete; recovery preserves this document.
            Jobs.update(job.id) { it.copy(pendingComplete = true) }
            return uri
        } catch (error: Throwable) {
            try {
                if (uri != null) check(DocumentsContract.deleteDocument(resolver, uri))
                Jobs.update(job.id) { it.copy(pending = "", pendingName = "", pendingComplete = false) }
            } catch (_: Exception) { /* Keep the journal so recovery can retry cleanup. */ }
            throw error
        }
    }
    fun recover() {
        Jobs.snapshot().filter { it.destination.isNotEmpty() && (it.pending.isNotEmpty() || it.pendingName.isNotEmpty()) }.forEach { job ->
            try {
                val documents = findPendingDocuments(job)
                if (job.pendingComplete && job.pending.isNotEmpty() && documents.any { it.toString() == job.pending }) {
                    Jobs.update(job.id) { it.copy(status = "Complete", progress = 100, uri = job.pending, pending = "", pendingName = "", pendingComplete = false) }
                } else {
                    documents.forEach { check(DocumentsContract.deleteDocument(resolver, it)) { "Could not remove an incomplete file from the selected folder" } }
                    Jobs.update(job.id) { it.copy(status = recoveredPublicationStatus(it.status, false), pending = "", pendingName = "", pendingComplete = false) }
                }
            } catch (error: Exception) {
                Jobs.update(job.id) { it.copy(status = "Failed", message = "Cannot access the selected folder. Restore access to it, then retry.", details = error.stackTraceToString()) }
            }
        }
        MediaPublisher(context).recover()
    }
    private fun findPendingDocuments(job: Job): List<Uri> {
        val tree = Uri.parse(job.destination)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val found = mutableListOf<Uri>()
        checkNotNull(resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)).use { rows ->
            while (rows.moveToNext()) {
                if (rows.getString(1).startsWith("VideoStore-${job.id}-")) {
                    found.add(DocumentsContract.buildDocumentUriUsingTree(tree, rows.getString(0)))
                }
            }
        }
        if (job.pending.isNotEmpty() && found.none { it.toString() == job.pending }) {
            val pending = Uri.parse(job.pending)
            try {
                resolver.query(pending, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { row ->
                    if (row.moveToFirst()) found.add(pending)
                }
            } catch (_: java.io.FileNotFoundException) { /* The incomplete document was already removed. */ }
        }
        return found
    }
}

fun deleteSavedFile(context: Context, uri: Uri) {
    if (DocumentsContract.isDocumentUri(context, uri)) check(DocumentsContract.deleteDocument(context.contentResolver, uri)) { "File could not be deleted" }
    else context.contentResolver.delete(uri, null, null)
}
