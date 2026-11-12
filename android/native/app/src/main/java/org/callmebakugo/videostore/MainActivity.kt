package org.callmebakugo.videostore

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

private data class Submission(val urls: List<String>, val format: Int, val destination: Destination, val playlist: Boolean)

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf("")
    private var shareRevision by mutableIntStateOf(0)
    private fun sharedUrls(intent: Intent): String = Regex("https?://[^\\s<>]+").findAll(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()).map { it.value }.distinct().joinToString("\n")
    private fun startQueue() = startForegroundService(Intent(this, DownloadService::class.java))
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Jobs.load(this)
        shared = sharedUrls(intent)
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var url by rememberSaveable { mutableStateOf(shared) }
                LaunchedEffect(shareRevision) { if (shareRevision > 0 && shared.isNotEmpty()) url = shared }
                var format by rememberSaveable { mutableIntStateOf(settings.getInt("format", 4).coerceIn(0, 4)) }
                var playlist by rememberSaveable { mutableStateOf(false) }
                var wifiOnly by remember { mutableStateOf(settings.getBoolean("wifiOnly", false)) }
                var expanded by remember { mutableStateOf(false) }
                var foldersExpanded by rememberSaveable { mutableStateOf(false) }
                var folderKind by rememberSaveable { mutableStateOf("video") }
                var folderRevision by remember { mutableIntStateOf(0) }
                var error by remember { mutableStateOf("") }
                var duplicate by remember { mutableStateOf<Submission?>(null) }
                var remove by remember { mutableStateOf<Job?>(null) }
                var delete by remember { mutableStateOf<Job?>(null) }
                val scope = rememberCoroutineScope()
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    val data = result.data
                    val uri = data?.data
                    if (result.resultCode == Activity.RESULT_OK && uri != null) {
                        val kind = folderKind
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { rememberDestination(this@MainActivity, kind, uri, data.flags) }
                                folderRevision++
                                error = ""
                            } catch (_: Exception) { error = "Could not keep access to this folder. Choose a writable folder and try again." }
                        }
                    }
                }
                val videoDestination = remember(folderRevision) { destinationFor(this@MainActivity, 0) }
                val audioDestination = remember(folderRevision) { destinationFor(this@MainActivity, 4) }
                fun open(job: Job, share: Boolean = false) {
                    try {
                        val uri = Uri.parse(job.uri)
                        contentResolver.openFileDescriptor(uri, "r")?.close() ?: error("File is missing")
                        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                        val action = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                            .apply { clipData = ClipData.newRawUri("Media", uri) }
                        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        action.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(if (share) Intent.createChooser(action, "Share media") else action)
                    } catch (_: Exception) { error = "This file is unavailable, or no installed app can open it." }
                }
                fun matches(job: Job, submission: Submission, link: String) = sameSubmission(job, link, submission.format, submission.destination.tree, submission.playlist)
                fun enqueue(submission: Submission, skipExisting: Boolean = false) {
                    val links = submission.urls.filter { link -> !skipExisting || Jobs.snapshot().none { matches(it, submission, link) } }
                    if (links.isNotEmpty()) {
                        Jobs.addAll(links, submission.format, submission.destination, submission.playlist)
                        startQueue()
                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !settings.getBoolean("notificationAsked", false)) {
                            settings.edit().putBoolean("notificationAsked", true).apply()
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                val active = Jobs.items.filter { it.status in activeStatuses }
                val history = Jobs.items.filter { it.status !in activeStatuses }.reversed()
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.safeDrawingPadding().fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item(key = "controls") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("VideoStore", style = MaterialTheme.typography.headlineLarge)
                                OutlinedTextField(url, { url = it }, label = { Text("Links — one per line") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                                    trailingIcon = { TextButton(onClick = {
                                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.let { url = it.getItemAt(0).coerceToText(this@MainActivity).toString() }
                                    }) { Text("Paste") } })
                                Box {
                                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(formatLabels[format]) }
                                    DropdownMenu(expanded, { expanded = false }) {
                                        formatLabels.forEachIndexed { index, label -> DropdownMenuItem(text = {
                                            Column { Text(label); Text(formatDescriptions[index], style = MaterialTheme.typography.bodySmall) }
                                        }, onClick = { format = index; settings.edit().putInt("format", index).apply(); expanded = false }) }
                                    }
                                }
                                Text(formatDescriptions[format], style = MaterialTheme.typography.bodySmall)
                                Row {
                                    Checkbox(playlist, { playlist = it })
                                    Text("Download full playlists", modifier = Modifier.padding(top = 12.dp))
                                }
                                Button(onClick = {
                                    try {
                                        val submission = Submission(parseInputUrls(url), format, if (format >= 3) audioDestination else videoDestination, playlist)
                                        error = ""
                                        if (submission.urls.any { link -> Jobs.snapshot().any { matches(it, submission, link) } }) duplicate = submission
                                        else enqueue(submission)
                                    } catch (exception: IllegalArgumentException) { error = exception.message.orEmpty() }
                                }, modifier = Modifier.fillMaxWidth()) { Text(if (playlist) "Add playlists to queue" else "Download") }
                                Text("Save to: ${(if (format >= 3) audioDestination else videoDestination).label}", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { foldersExpanded = !foldersExpanded }) { Text(if (foldersExpanded) "Hide download folders" else "Download folders…") }
                                if (foldersExpanded) {
                                    listOf("video" to videoDestination, "audio" to audioDestination).forEach { (kind, destination) ->
                                        Text("${kind.replaceFirstChar { it.uppercase() }}: ${destination.label}", style = MaterialTheme.typography.bodyMedium)
                                        Row {
                                            OutlinedButton(onClick = {
                                                folderKind = kind
                                                folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
                                            }) { Text("Choose folder") }
                                            if (destination.tree.isNotEmpty()) TextButton(onClick = {
                                                settings.edit().remove("${kind}Tree").remove("${kind}Name").apply(); folderRevision++
                                            }) { Text("Use default") }
                                        }
                                    }
                                    Text("Folder changes apply to new downloads. Android may require choosing a subfolder.", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Wi-Fi only", modifier = Modifier.padding(top = 12.dp))
                                    Switch(wifiOnly, { wifiOnly = it; settings.edit().putBoolean("wifiOnly", it).apply(); if (Jobs.items.any { j -> j.status in activeStatuses }) startQueue() })
                                }
                                Text("Downloads run one at a time and continue when you switch apps or turn off the screen.", style = MaterialTheme.typography.bodySmall)
                                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (active.isNotEmpty()) {
                            item(key = "active-heading") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Queue (${active.size})", style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = { startQueue() }) { Text("Resume queue") }
                            } }
                            items(active, key = { it.id }) { job -> DownloadCard(job, cancel = {
                                startForegroundService(Intent(this@MainActivity, DownloadService::class.java).setAction("cancel").putExtra("id", job.id))
                            }) }
                        }
                        if (history.isNotEmpty()) item(key = "history-heading") { Text("History", style = MaterialTheme.typography.titleLarge) }
                        items(history, key = { it.id }) { job -> DownloadCard(job, retry = { Jobs.retry(job.id); startQueue() }, open = { open(job) }, share = { open(job, true) }, remove = { remove = job }, delete = { delete = job }) }
                    }
                }
                duplicate?.let { submission ->
                    val existing = submission.urls.mapNotNull { link -> Jobs.items.lastOrNull { matches(it, submission, link) } }
                    AlertDialog(onDismissRequest = { duplicate = null }, title = { Text("Already submitted") },
                        text = { Text("${existing.size} of these links already appear in your queue or history. Add them again?") },
                        confirmButton = { TextButton(onClick = { enqueue(submission); duplicate = null }) { Text("Download again") } },
                        dismissButton = { Column {
                            if (submission.urls.size == 1 && existing.firstOrNull()?.uri?.isNotEmpty() == true) TextButton(onClick = { open(existing.first()); duplicate = null }) { Text("Open existing") }
                            if (submission.urls.size > existing.size) TextButton(onClick = { enqueue(submission, true); duplicate = null }) { Text("Add only new links") }
                            TextButton(onClick = { duplicate = null }) { Text("Back") }
                        } })
                }
                remove?.let { job -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove from history?") },
                    text = { Text("Your saved file stays in its download folder. Any unfinished temporary download will be removed. Playlist items already queued remain in the queue.") },
                    confirmButton = { TextButton(onClick = {
                        try { Jobs.remove(job.id, this@MainActivity) } catch (exception: IllegalStateException) { error = exception.message.orEmpty() }
                        remove = null
                    }) { Text("Remove entry") } },
                    dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } }) }
                delete?.let { job -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete saved file?") },
                    text = { Text("This permanently deletes the saved file and removes its history entry.") },
                    confirmButton = { TextButton(onClick = {
                        scope.launch {
                            try { withContext(Dispatchers.IO) { deleteSavedFile(this@MainActivity, Uri.parse(job.uri)) }; Jobs.remove(job.id, this@MainActivity) }
                            catch (_: Exception) { error = "Could not delete this file. You can delete it using your Files app." }
                        }
                        delete = null
                    }) { Text("Delete file") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); shared = sharedUrls(intent); shareRevision++ }
}

@Composable
private fun DownloadCard(job: Job, cancel: (() -> Unit)? = null, retry: (() -> Unit)? = null, open: (() -> Unit)? = null,
                         share: (() -> Unit)? = null, remove: (() -> Unit)? = null, delete: (() -> Unit)? = null) {
    var details by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val loadThumbnail = wifiAllowed(context)
    var thumbnail by remember(job.thumbnail) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(job.thumbnail, loadThumbnail) {
        thumbnail = withContext(Dispatchers.IO) {
            try {
                val url = URL(job.thumbnail)
                if (!loadThumbnail || url.protocol != "https") null else {
                    val connection = url.openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
                    connection.getInputStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 2 * 1024 * 1024) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        check(output.size() <= 2 * 1024 * 1024)
                        val bytes = output.toByteArray()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 480).coerceAtLeast(1) }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }
                }
            } catch (_: Exception) { null }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            thumbnail?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Crop) }
            Text(job.title.ifBlank { job.url }, style = MaterialTheme.typography.titleMedium)
            Text(formatLabels[job.format.coerceIn(0, 4)], style = MaterialTheme.typography.bodySmall)
            Text(job.status)
            if (!job.expanded) Text("Save to: ${job.destinationLabel}", style = MaterialTheme.typography.bodySmall)
            if (job.status == "Downloading") {
                LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text(listOf("${job.progress}%", job.speed.takeUnless { it == "NA" }.orEmpty(), job.eta.takeIf { it.isNotBlank() && it != "NA" }?.let { "$it remaining" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
            } else if (job.status in setOf("Reading playlist", "Fetching details", "Converting", "Saving", "Cancelling")) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (job.status.startsWith("Failed") || job.status.startsWith("Engine error")) Text(job.message.ifBlank { friendlyError(job.status) }, color = MaterialTheme.colorScheme.error)
            if (cancel != null) TextButton(onClick = cancel, enabled = job.status != "Cancelling") { Text("Cancel") }
            if (retry != null && job.uri.isEmpty() && !job.expanded) TextButton(onClick = retry) { Text("Retry") }
            if (job.uri.isNotEmpty()) {
                Row { TextButton(onClick = { open?.invoke() }) { Text("Open") }; TextButton(onClick = { share?.invoke() }) { Text("Share") } }
                TextButton(onClick = { delete?.invoke() }) { Text("Delete file…") }
            }
            if (remove != null) TextButton(onClick = remove) { Text("Remove from history…") }
            if (job.details.isNotEmpty()) {
                TextButton(onClick = { details = !details }) { Text(if (details) "Hide technical details" else "Technical details") }
                if (details) Text(job.details, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
