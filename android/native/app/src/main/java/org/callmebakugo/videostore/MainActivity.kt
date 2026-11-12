package org.callmebakugo.videostore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf("")
    private var shareRevision by mutableIntStateOf(0)
    private fun sharedUrl(intent: Intent): String = Regex("https?://[^\\s<>]+").find(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())?.value.orEmpty()
    private fun startQueue() = startForegroundService(Intent(this, DownloadService::class.java))
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Jobs.load(this)
        shared = sharedUrl(intent)
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var url by rememberSaveable { mutableStateOf(shared) }
                LaunchedEffect(shareRevision) { if (shareRevision > 0 && shared.isNotEmpty()) url = shared }
                var format by rememberSaveable { mutableIntStateOf(settings.getInt("format", 4).coerceIn(0, 4)) }
                var wifiOnly by remember { mutableStateOf(settings.getBoolean("wifiOnly", false)) }
                var expanded by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf("") }
                var duplicate by remember { mutableStateOf<Job?>(null) }
                var remove by remember { mutableStateOf<Job?>(null) }
                var delete by remember { mutableStateOf<Job?>(null) }
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
                fun enqueue() { Jobs.add(url.trim(), format); startQueue() }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("VideoStore", style = MaterialTheme.typography.headlineLarge)
                        OutlinedTextField(url, { url = it }, label = { Text("Paste a link") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
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
                        Button(onClick = {
                            val parsed = Uri.parse(url.trim())
                            if (parsed.scheme !in listOf("https", "http") || parsed.host.isNullOrEmpty()) error = "Enter an http or https URL."
                            else {
                                error = ""
                                val existing = Jobs.items.lastOrNull { it.url == url.trim() && it.format == format }
                                if (existing != null) duplicate = existing else enqueue()
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Download") }
                        Text("Files saved to Downloads/VideoStore", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Wi-Fi only", modifier = Modifier.padding(top = 12.dp))
                            Switch(wifiOnly, { wifiOnly = it; settings.edit().putBoolean("wifiOnly", it).apply(); if (Jobs.items.any { j -> j.status in activeStatuses }) startQueue() })
                        }
                        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                        val active = Jobs.items.filter { it.status in activeStatuses }.reversed()
                        val history = Jobs.items.filter { it.status !in activeStatuses }.reversed()
                        if (active.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Active downloads", style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = { startQueue() }) { Text("Resume queue") }
                            }
                            active.forEach { job -> key(job.id) { DownloadCard(job, cancel = {
                                startForegroundService(Intent(this@MainActivity, DownloadService::class.java).setAction("cancel").putExtra("id", job.id))
                            }) } }
                        }
                        if (history.isNotEmpty()) Text("History", style = MaterialTheme.typography.titleLarge)
                        history.forEach { job -> key(job.id) {
                            DownloadCard(job, retry = { Jobs.retry(job.id); startQueue() }, open = { open(job) }, share = { open(job, true) }, remove = { remove = job }, delete = { delete = job })
                        } }
                    }
                }
                duplicate?.let { job -> AlertDialog(onDismissRequest = { duplicate = null }, title = { Text("Already submitted") },
                    text = { Text("${job.title.ifBlank { job.url }}\n${job.status}. Download another copy?") },
                    confirmButton = { TextButton(onClick = { enqueue(); duplicate = null }) { Text("Download again") } },
                    dismissButton = { Row { if (job.uri.isNotEmpty()) TextButton(onClick = { open(job); duplicate = null }) { Text("Open existing") }; TextButton(onClick = { duplicate = null }) { Text("Back") } } }) }
                remove?.let { job -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove from history?") },
                    text = { Text("Your saved file stays in Downloads/VideoStore. Any unfinished temporary download will be removed.") },
                    confirmButton = { TextButton(onClick = { Jobs.remove(job.id, this@MainActivity); remove = null }) { Text("Remove entry") } },
                    dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } }) }
                delete?.let { job -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete saved file?") },
                    text = { Text("This permanently deletes the file from Downloads/VideoStore and removes its history entry.") },
                    confirmButton = { TextButton(onClick = {
                        try { contentResolver.delete(Uri.parse(job.uri), null, null); Jobs.remove(job.id, this@MainActivity) }
                        catch (_: Exception) { error = "Could not delete this file. You can delete it using your Files app." }
                        delete = null
                    }) { Text("Delete file") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); shared = sharedUrl(intent); shareRevision++ }
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
            if (job.status == "Downloading") {
                LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text(listOf("${job.progress}%", job.speed.takeUnless { it == "NA" }.orEmpty(), job.eta.takeIf { it.isNotBlank() && it != "NA" }?.let { "$it remaining" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
            } else if (job.status in setOf("Fetching details", "Converting", "Saving", "Cancelling")) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (job.status.startsWith("Failed") || job.status.startsWith("Engine error")) Text(job.message.ifBlank { friendlyError(job.status) }, color = MaterialTheme.colorScheme.error)
            if (cancel != null) TextButton(onClick = cancel, enabled = job.status != "Cancelling") { Text("Cancel") }
            if (retry != null && job.uri.isEmpty()) TextButton(onClick = retry) { Text("Retry") }
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
