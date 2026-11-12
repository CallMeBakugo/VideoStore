package org.callmebakugo.videostore

import android.app.*
import android.content.Intent
import org.json.JSONObject
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
    @Volatile private var destroyed = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var networkPaused = false
    private fun wifiAllowed() = wifiAllowed(this)
    private val networkMonitor = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (active != null && !wifiAllowed()) { networkPaused = true; killEngineProcesses() }
            main.postDelayed(this, 1000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); Jobs.load(this); main.post(networkMonitor)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(message: String, id: String? = active): Notification {
        val builder = Notification.Builder(this, "downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("VideoStore").setContentText(message).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (id != null) builder.addAction(Notification.Action.Builder(null, "Cancel",
            PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction("cancel").putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        return builder.build()
    }
    @android.annotation.SuppressLint("NotificationPermission")
    private fun updateNotification(message: String) {
        // Notifications are optional; the mandatory foreground notification uses startForeground.
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
                    MediaPublisher(this).recover()
                    YoutubeDL.init(this); FFmpeg.init(this)
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
        if (Jobs.snapshot().any { it.pending.isNotEmpty() }) MediaPublisher(this).recover()
        if (!wifiAllowed()) {
            Jobs.snapshot().filter { it.status == "Queued" }.forEach { j -> Jobs.update(j.id) { it.copy(status = "Waiting for Wi-Fi") } }
            if (Jobs.snapshot().any { it.status == "Waiting for Wi-Fi" }) {
                updateNotification("Waiting for Wi-Fi")
                main.postDelayed({ if (!destroyed) worker.execute { drain() } }, 3000)
            } else finish()
            return
        }
        Jobs.snapshot().filter { it.status == "Waiting for Wi-Fi" }.forEach { j -> Jobs.update(j.id) { it.copy(status = "Queued") } }
        val job = synchronized(Jobs) { Jobs.snapshot().firstOrNull { it.status == "Queued" } }
        if (job == null) { finish(); return }
        networkPaused = false
        active = job.id
        val directory = File(filesDir, "downloads/${job.id}")
        try {
            check(!cancelled.contains(job.id) && Jobs.snapshot().firstOrNull { it.id == job.id }?.status != "Cancelled") { "Cancelled" }
            check(directory.isDirectory || directory.mkdirs())
            Jobs.update(job.id) { it.copy(status = "Fetching details", speed = "", eta = "") }
            updateNotification("Fetching details")
            val metadata = YoutubeDLRequest(job.url)
            metadata.addOption("--no-playlist")
            metadata.addOption("--dump-single-json")
            val info = JSONObject(YoutubeDL.execute(metadata, job.id).out)
            Jobs.update(job.id) { it.copy(title = info.optString("title"), thumbnail = info.optString("thumbnail")) }
            check(!cancelled.contains(job.id) && !networkPaused) { "Interrupted" }
            val request = YoutubeDLRequest(job.url)
            request.addOption("--no-playlist")
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
            updateNotification("Saving to Downloads/VideoStore")
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(output.extension.lowercase()) ?: "application/octet-stream"
            val uri = MediaPublisher(this).publish(output, mime, { cancelled.contains(job.id) },
                safeMediaName(Jobs.snapshot().firstOrNull { it.id == job.id }?.title.orEmpty(), output.extension)) { pending ->
                Jobs.update(job.id) { it.copy(pending = pending.toString()) }
            }
            Jobs.update(job.id) { it.copy(status = "Complete", progress = 100, uri = uri.toString(), pending = "") }
            directory.deleteRecursively()
        } catch (error: Throwable) {
            Jobs.update(job.id) { it.copy(status = when { cancelled.contains(job.id) -> "Cancelled"; networkPaused -> "Waiting for Wi-Fi"; else -> "Failed" },
                speed = "", eta = "", message = friendlyError(error.message.orEmpty()), details = error.stackTraceToString()) }
        } finally {
            if (cancelled.contains(job.id)) directory.deleteRecursively()
            active = null; cancelled.remove(job.id)
        }
        // Re-enter through main thread so enqueue/start/stop cannot race each other.
        main.post { if (!destroyed) worker.execute { drain() } }
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
        active?.let { cancelled.add(it); killEngineProcesses() }
        worker.shutdown(); canceller.shutdown(); super.onDestroy()
    }
}
