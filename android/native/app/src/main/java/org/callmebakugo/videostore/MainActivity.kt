package org.callmebakugo.videostore

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf("")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Jobs.load(this)
        shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var url by rememberSaveable { mutableStateOf(shared) }
                LaunchedEffect(shared) { if (shared.isNotEmpty()) url = shared }
                var format by rememberSaveable { mutableIntStateOf(4) }
                var expanded by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf("") }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("VideoStore", style = MaterialTheme.typography.headlineLarge)
                        OutlinedTextField(url, { url = it }, label = { Text("Paste a link") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.let { url = it.getItemAt(0).coerceToText(this@MainActivity).toString() }
                        }) { Text("Paste") }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) { Text(formatLabels[format]) }
                            DropdownMenu(expanded, { expanded = false }) {
                                formatLabels.forEachIndexed { index, label -> DropdownMenuItem(text = { Text(label) }, onClick = { format = index; expanded = false }) }
                            }
                        }
                        Text("Output: Downloads/VideoStore")
                        Button(onClick = {
                            val parsed = Uri.parse(url.trim())
                            if (parsed.scheme !in listOf("https", "http") || parsed.host.isNullOrEmpty()) error = "Enter an http or https URL."
                            else {
                                error = ""
                                Jobs.add(url.trim(), format)
                                startForegroundService(Intent(this@MainActivity, DownloadService::class.java))
                            }
                        }) { Text("Download") }
                        if (error.isNotEmpty()) Text(error)
                        if (Jobs.items.any { it.status in listOf("Queued", "Downloading", "Publishing") }) {
                            TextButton(onClick = { startForegroundService(Intent(this@MainActivity, DownloadService::class.java)) }) { Text("Resume queue") }
                        }
                        Jobs.items.reversed().forEach { job ->
                            HorizontalDivider()
                            Text(job.url)
                            Text("${formatLabels[job.format]} · ${job.status} · ${job.progress}%")
                            if (job.status in listOf("Queued", "Downloading", "Publishing")) {
                                TextButton(onClick = { startForegroundService(Intent(this@MainActivity, DownloadService::class.java).setAction("cancel").putExtra("id", job.id)) }) { Text("Cancel") }
                            }
                            if (job.uri.isNotEmpty()) TextButton(onClick = {
                                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(job.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                catch (_: android.content.ActivityNotFoundException) { error = "No app can open this file." }
                            }) { Text("Open") }
                        }
                    }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() }
}
