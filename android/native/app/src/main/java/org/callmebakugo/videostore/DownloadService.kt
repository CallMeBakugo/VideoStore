package org.callmebakugo.videostore

import android.app.*
import android.content.Intent
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
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); Jobs.load(this)
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
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("Preparing downloads"))
        if (intent?.action == "cancel") intent.getStringExtra("id")?.let { id ->
            cancelled.add(id)
            Jobs.update(id) { if (it.status == "Queued") it.copy(status = "Cancelled") else if (it.status in listOf("Downloading", "Publishing")) it.copy(status = "Cancelling") else it }
            // Repeated termination covers cancellation racing with engine process creation.
            canceller.execute { while (active == id) { killEngineProcesses(); Thread.sleep(50) } }
        }
        if (!running) {
            running = true
            worker.execute {
                try {
                    MediaPublisher(this).recover()
                    File(filesDir, "downloads").deleteRecursively()
                    YoutubeDL.init(this); FFmpeg.init(this)
                    drain()
                } catch (error: Throwable) {
                    // Initialization failure is visible and halts the queue; never change engines.
                    Jobs.items.toList().filter { it.status == "Queued" }.forEach { j -> Jobs.update(j.id) { it.copy(status = "Engine error: ${error.message}") } }
                    finish()
                }
            }
        }
        return START_STICKY
    }
    private fun drain() {
        if (destroyed) return
        val job = synchronized(Jobs) { Jobs.items.firstOrNull { it.status == "Queued" } }
        if (job == null) { finish(); return }
        active = job.id
        val directory = File(filesDir, "downloads/${job.id}")
        try {
            check(!cancelled.contains(job.id)) { "Cancelled" }
            directory.deleteRecursively(); check(directory.mkdirs())
            Jobs.update(job.id) { it.copy(status = "Downloading", progress = 0) }
            getSystemService(NotificationManager::class.java).notify(1, notification("Starting download"))
            val request = YoutubeDLRequest(job.url)
            request.addOption("--no-playlist")
            request.addOption("--restrict-filenames")
            request.addOption("--no-mtime")
            request.addOption("-f", formatSelectors[job.format])
            request.addOption("-o", File(directory, "%(title).160B [%(id)s].%(ext)s").absolutePath)
            if (job.format == 3) { request.addOption("-x"); request.addOption("--audio-format", "mp3") }
            check(!cancelled.contains(job.id)) { "Cancelled" }
            var lastProgress = -1
            YoutubeDL.execute(request, job.id) { progress, _, _ ->
                val percent = progress.toInt().coerceIn(0, 100)
                if (percent != lastProgress) {
                    lastProgress = percent
                    Jobs.update(job.id) { it.copy(progress = percent) }
                    getSystemService(NotificationManager::class.java).notify(1, notification("Downloading · $percent%"))
                }
            }
            check(!cancelled.contains(job.id)) { "Cancelled" }
            val output = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }.orEmpty()
            check(output.size == 1) { "Expected one completed media file, found ${output.size}" }
            Jobs.update(job.id) { it.copy(status = "Publishing") }
            getSystemService(NotificationManager::class.java).notify(1, notification("Saving to Downloads/VideoStore"))
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(output.single().extension.lowercase()) ?: "application/octet-stream"
            val uri = MediaPublisher(this).publish(output.single(), mime, { cancelled.contains(job.id) }) { pending ->
                Jobs.update(job.id) { it.copy(pending = pending.toString()) }
            }
            Jobs.update(job.id) { it.copy(status = "Complete", progress = 100, uri = uri.toString(), pending = "") }
        } catch (error: Throwable) {
            Jobs.update(job.id) { it.copy(status = if (cancelled.contains(job.id)) "Cancelled" else "Failed: ${error.message}") }
        } finally { directory.deleteRecursively(); active = null; cancelled.remove(job.id) }
        // Re-enter through main thread so enqueue/start/stop cannot race each other.
        main.post { if (!destroyed) worker.execute { drain() } }
    }
    private fun finish() {
        main.post {
            if (destroyed) return@post
            if (Jobs.items.any { it.status == "Queued" }) worker.execute { drain() }
            else { running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    /** Kill only this app's native engine roots and their descendants, without shell tools. */
    private fun killEngineProcesses() {
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
        ids.filter { it != android.os.Process.myPid() }.reversed().forEach { android.os.Process.killProcess(it) }
    }
    override fun onDestroy() {
        destroyed = true
        active?.let { cancelled.add(it); killEngineProcesses() }
        worker.shutdown(); canceller.shutdown(); super.onDestroy()
    }
}
