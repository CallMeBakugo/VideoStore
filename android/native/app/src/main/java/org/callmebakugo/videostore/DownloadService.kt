package org.callmebakugo.videostore

import android.app.*
import android.content.Intent
import org.json.JSONObject
import android.os.PowerManager
import android.os.SystemClock
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

class DownloadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val canceller = Executors.newSingleThreadExecutor()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var active: String? = null
    private var running = false
    private lateinit var wakeLock: PowerManager.WakeLock
    @Volatile private var wakeRenewed = 0L
    @Synchronized private fun keepAwake() {
        if (destroyed) return
        wakeLock.acquire(10 * 60 * 1000L)
        wakeRenewed = SystemClock.elapsedRealtime()
    }
    @Synchronized private fun releaseAwake() { if (wakeLock.isHeld) wakeLock.release() }
    @Volatile private var destroyed = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var networkPaused = false
    private fun wifiAllowed() = wifiAllowed(this)
    private val networkMonitor = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (wakeLock.isHeld && SystemClock.elapsedRealtime() - wakeRenewed > 5 * 60 * 1000L) keepAwake()
            if (active != null && !wifiAllowed()) { networkPaused = true; killEngineProcesses() }
            main.postDelayed(this, 1000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); Jobs.load(this)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoStore:downloads").apply { setReferenceCounted(false) }
        main.post(networkMonitor)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(message: String, id: String? = active): Notification {
        val current = Jobs.snapshot().firstOrNull { it.id == id }
        val remaining = Jobs.snapshot().count { it.status in activeStatuses }
        val builder = Notification.Builder(this, "downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(current?.title?.ifBlank { "VideoStore" } ?: "VideoStore")
            .setContentText(if (current?.status == "Downloading") "$message · ${current.progress}%" else message)
            .setSubText("VideoStore · $remaining remaining").setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (current?.status == "Downloading") builder.setProgress(100, current.progress, false)
        else if (current?.status in setOf("Reading playlist", "Fetching details", "Converting", "Saving")) builder.setProgress(0, 0, true)
        if (id != null) builder.addAction(Notification.Action.Builder(null, "Cancel",
            PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction("cancel").putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        return builder.build()
    }
    @android.annotation.SuppressLint("NotificationPermission")
    private fun updateNotification(message: String) {
        // The user can decline notifications; foreground execution still uses startForeground.
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.areNotificationsEnabled()) manager.notify(1, notification(message))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("Preparing downloads"))
        if (intent?.action == "cancel") intent.getStringExtra("id")?.let { id ->
            if (active == id) cancelled.add(id)
            Jobs.update(id) { if (it.status in listOf("Queued", "Waiting for Wi-Fi")) it.copy(status = "Cancelled") else if (it.status in activeStatuses) it.copy(status = "Cancelling") else it }
            // Repeated termination covers cancellation racing with engine process creation.
            canceller.execute { while (active == id) { killEngineProcesses(); Thread.sleep(50) } }
        }
        if (!running) {
            running = true
            worker.execute {
                try {
                    FilePublisher(this).recover()
                    keepAwake()
                    try { YoutubeDL.init(this); FFmpeg.init(this) } finally { releaseAwake() }
                    drain()
                } catch (error: Throwable) {
                    // Initialization failure is visible and halts the queue; never change engines.
                    Jobs.snapshot().filter { it.status in listOf("Queued", "Waiting for Wi-Fi") }.forEach { j -> Jobs.update(j.id) { it.copy(status = "Failed", message = "The download engine could not start. Retry to initialize it again.", details = error.stackTraceToString()) } }
                    finish()
                }
            }
        }
        return START_STICKY
    }
    private fun drain() {
        if (destroyed) return
        if (Jobs.snapshot().any { it.pending.isNotEmpty() || it.pendingName.isNotEmpty() }) FilePublisher(this).recover()
        if (!wifiAllowed()) {
            Jobs.snapshot().filter { it.status == "Queued" }.forEach { j -> Jobs.update(j.id) { it.copy(status = "Waiting for Wi-Fi") } }
            if (Jobs.snapshot().any { it.status == "Waiting for Wi-Fi" }) {
                updateNotification("Waiting for Wi-Fi")
                main.postDelayed({ if (!destroyed) worker.execute { drain() } }, 3000)
            } else finish()
            return
        }
        Jobs.snapshot().filter { it.status == "Waiting for Wi-Fi" }.forEach { j -> Jobs.update(j.id) { it.copy(status = "Queued") } }
        networkPaused = false
        val job = synchronized(Jobs) {
            Jobs.snapshot().firstOrNull { it.status == "Queued" }?.also { active = it.id }
        }
        if (job == null) { finish(); return }
        val directory = File(filesDir, "downloads/${job.id}")
        try {
            check(!cancelled.contains(job.id) && Jobs.snapshot().firstOrNull { it.id == job.id }?.status != "Cancelled") { "Cancelled" }
            keepAwake()
            check(directory.isDirectory || directory.mkdirs())
            if (job.playlist) {
                expandPlaylist(job)
                return
            }
            Jobs.update(job.id) { it.copy(status = "Fetching details", speed = "", eta = "") }
            updateNotification("Fetching details")
            val metadata = YoutubeDLRequest(job.url)
            selectSingleItem(metadata, job)
            metadata.addOption("--dump-single-json")
            val extracted = JSONObject(YoutubeDL.execute(metadata, job.id).out)
            val info = extracted.optJSONArray("entries")?.optJSONObject(0) ?: extracted
            Jobs.update(job.id) { it.copy(title = info.optString("title"), thumbnail = info.optString("thumbnail")) }
            check(!cancelled.contains(job.id) && !networkPaused) { "Interrupted" }
            val request = YoutubeDLRequest(job.url)
            selectSingleItem(request, job)
            request.addOption("--restrict-filenames")
            request.addOption("--no-mtime")
            request.addOption("--continue")
            request.addOption("--newline")
            request.addOption("--no-colors")
            request.addOption("--no-quiet")
            request.addOption("--progress")
            request.addOption("--no-simulate")
            request.addOption("--print", "after_move:VSFILE|%(filepath)s")
            request.addOption("--progress-template", "download:VS|%(progress._percent_str)s|%(progress._speed_str)s|%(progress._eta_str)s")
            request.addOption("-f", formatSelectors[job.format])
            request.addOption("-o", File(directory, "media.%(ext)s").absolutePath)
            if (job.format == 3) { request.addOption("-x"); request.addOption("--audio-format", "mp3") }
            check(!cancelled.contains(job.id) && !networkPaused) { "Interrupted" }
            Jobs.update(job.id) { it.copy(status = "Downloading") }
            var lastUpdate = 0L
            val response = YoutubeDL.execute(request, job.id) { _, _, line ->
                val now = System.currentTimeMillis()
                val converting = isConversionLine(line)
                val progressInfo = parseDownloadProgress(line)
                if (converting || progressInfo != null && (now - lastUpdate > 700 || progressInfo.percent == 100)) {
                    lastUpdate = now
                    Jobs.update(job.id) {
                        if (it.status == "Cancelling") it else it.copy(status = if (converting) "Converting" else "Downloading",
                            progress = progressInfo?.percent ?: it.progress,
                            speed = progressInfo?.speed.orEmpty(), eta = progressInfo?.eta.orEmpty())
                    }
                    updateNotification(Jobs.snapshot().firstOrNull { it.id == job.id }?.status.orEmpty())
                }
            }
            check(!networkPaused) { "Waiting for Wi-Fi" }
            check(!cancelled.contains(job.id)) { "Cancelled" }
            val finalPath = response.out.lineSequence().lastOrNull { it.startsWith("VSFILE|") }?.substringAfter("VSFILE|")
            val output = finalPath?.let { File(it).canonicalFile }
                ?: directory.listFiles()?.singleOrNull { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            check(output != null && output.isFile && output.parentFile == directory.canonicalFile) { "The completed media file could not be located" }
            Jobs.update(job.id) { it.copy(status = "Saving", speed = "", eta = "") }
            updateNotification("Saving to ${job.destinationLabel}")
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(output.extension.lowercase()) ?: "application/octet-stream"
            val uri = FilePublisher(this).publish(job, output, mime,
                safeMediaName(Jobs.snapshot().firstOrNull { it.id == job.id }?.title.orEmpty(), output.extension), { cancelled.contains(job.id) })
            Jobs.update(job.id) { it.copy(status = "Complete", progress = 100, uri = uri.toString(), pending = "", pendingName = "", pendingComplete = false) }
            directory.deleteRecursively()
        } catch (error: Throwable) {
            Jobs.update(job.id) { it.copy(status = when { destroyed -> "Queued"; cancelled.contains(job.id) || Jobs.snapshot().firstOrNull { j -> j.id == job.id }?.status == "Cancelled" -> "Cancelled"; networkPaused -> "Waiting for Wi-Fi"; else -> "Failed" },
                speed = "", eta = "", message = friendlyError(error.message.orEmpty()), details = error.stackTraceToString()) }
        } finally {
            if (cancelled.contains(job.id) || Jobs.snapshot().firstOrNull { it.id == job.id }?.status == "Cancelled") directory.deleteRecursively()
            active = null; cancelled.remove(job.id)
            releaseAwake()
            // Also runs after playlist expansion returns early.
            main.post { if (!destroyed) worker.execute { drain() } }
        }
    }
    private fun selectSingleItem(request: YoutubeDLRequest, job: Job) {
        if (job.playlistIndex > 0) {
            request.addOption("--yes-playlist")
            request.addOption("--playlist-items", job.playlistIndex)
        } else request.addOption("--no-playlist")
    }
    private fun expandPlaylist(job: Job) {
        Jobs.update(job.id) { it.copy(status = "Reading playlist") }
        updateNotification("Reading playlist")
        val request = YoutubeDLRequest(job.url)
        request.addOption("--yes-playlist")
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")
        val info = JSONObject(YoutubeDL.execute(request, job.id).out)
        check(!cancelled.contains(job.id) && !networkPaused) { "Interrupted" }
        val entries = info.optJSONArray("entries")
        if (entries == null) {
            // The explicit playlist option also accepts an ordinary media link.
            Jobs.update(job.id) { it.copy(playlist = false, status = "Queued") }
            return
        }
        check(entries.length() > 0) { "The playlist is empty or its items are unavailable" }
        val children = (0 until entries.length()).map { index ->
            val entry = entries.optJSONObject(index)
            val candidate = if (entry?.optString("_type") in setOf("url", "url_transparent")) {
                entry?.optString("webpage_url")?.takeIf { it != job.url && isMediaUrl(it) }
                    ?: entry?.optString("url")?.takeIf(::isMediaUrl)
            } else null
            playlistEntry(entry?.optInt("playlist_index", index + 1) ?: (index + 1), candidate, entry?.optString("title").orEmpty())
        }
        Jobs.update(job.id) { it.copy(title = info.optString("title")) }
        Jobs.expand(job.id, children)
        check(!cancelled.contains(job.id)) { "Cancelled" }
    }
    private fun finish() {
        main.post {
            if (destroyed) return@post
            if (Jobs.snapshot().any { it.status in listOf("Queued", "Waiting for Wi-Fi") }) worker.execute { drain() }
            else { running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    /** Kill only this app's native engine roots and their descendants, without shell tools. */
    private fun killEngineProcesses(expectedId: String? = active) {
        data class Proc(val pid: Int, val parent: Int, val engine: Boolean)
        val processes = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            try {
                val cmd = File(dir, "cmdline").readText().substringBefore('\u0000')
                val parent = Regex("(?m)^PPid:\\s+(\\d+)").find(File(dir, "status").readText())!!.groupValues[1].toInt()
                Proc(pid, parent, cmd.startsWith(applicationInfo.nativeLibraryDir + "/lib") && cmd.endsWith(".so"))
            } catch (_: Exception) { null }
        }
        val ids = processes.filter { it.engine }.map { it.pid }.toMutableSet()
        var changed = true
        while (changed) { changed = false; processes.forEach { if (it.parent in ids && ids.add(it.pid)) changed = true } }
        ids.filter { it != android.os.Process.myPid() }.reversed().forEach { if (active == expectedId) android.os.Process.killProcess(it) }
    }
    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(networkMonitor)
        releaseAwake()
        active?.let { killEngineProcesses(it) }
        worker.shutdown(); canceller.shutdown(); super.onDestroy()
    }
}
