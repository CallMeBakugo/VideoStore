package org.callmebakugo.videostore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

const val DEFAULT_DESTINATION = "Downloads/VideoStore"
data class Destination(val tree: String = "", val label: String = DEFAULT_DESTINATION)
fun destinationFor(context: Context, format: Int): Destination {
    val key = destinationKind(format)
    val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    return Destination(settings.getString("${key}Tree", "").orEmpty(), settings.getString("${key}Name", DEFAULT_DESTINATION).orEmpty())
}
fun rememberDestination(context: Context, kind: String, uri: Uri, flags: Int) {
    require(kind in setOf("audio", "video"))
    check(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) { "The selected folder does not allow writing." }
    val grants = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    context.contentResolver.takePersistableUriPermission(uri, grants)
    val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    val label = context.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "Selected folder"
    context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("${kind}Tree", uri.toString()).putString("${kind}Name", label).apply()
}
