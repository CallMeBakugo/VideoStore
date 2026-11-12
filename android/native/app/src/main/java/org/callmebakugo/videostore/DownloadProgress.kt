package org.callmebakugo.videostore

/** Parse our template independently of the engine's default-output regex. */
data class DownloadProgress(val percent: Int?, val speed: String, val eta: String)
fun parseDownloadProgress(line: String): DownloadProgress? {
    if (!line.startsWith("VS|")) return null
    val fields = line.trim().split('|')
    if (fields.size != 4) return null
    fun clean(value: String) = value.trim().takeUnless { it == "NA" || it == "Unknown" }.orEmpty()
    return DownloadProgress(fields[1].trim().removeSuffix("%").toFloatOrNull()?.toInt()?.coerceIn(0, 100), clean(fields[2]), clean(fields[3]))
}
fun isConversionLine(line: String) = listOf("[ExtractAudio]", "[Merger]", "[VideoConvertor]", "[VideoRemuxer]").any { line.startsWith(it) }
fun safeMediaName(title: String, extension: String): String {
    val clean = title.map { if (it in "\\/:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("")
    val name = StringBuilder()
    var bytes = 0
    val points = clean.codePoints().iterator()
    while (points.hasNext()) {
        val point = points.nextInt()
        val character = String(Character.toChars(point))
        val size = character.toByteArray(Charsets.UTF_8).size
        if (bytes + size > 120) break
        name.append(character); bytes += size
    }
    return name.toString().trim().ifBlank { "VideoStore" } + "." + extension
}

val activeStatuses = setOf("Queued", "Reading playlist", "Fetching details", "Downloading", "Converting", "Saving", "Publishing", "Cancelling", "Waiting for Wi-Fi")
val formatDescriptions = listOf("Highest available quality; may merge audio and video", "Up to 1080p", "Up to 720p", "MP3 — widely compatible", "Best audio — original format, avoids conversion")
fun friendlyError(details: String): String {
    val text = details.lowercase()
    return when {
        "permission" in text || "selected folder" in text || "securityexception" in text -> "Cannot write to the selected folder. Check folder access and available storage, then retry."
        "no space" in text -> "Your device is out of storage. Free some space and retry."
        "unsupported url" in text -> "This link is not supported. Check the URL."
        "private" in text || "sign in" in text || "login" in text -> "This media requires access or sign-in. Try a publicly available link."
        "unavailable" in text || "removed" in text || "404" in text -> "This media is unavailable or has been removed."
        "network" in text || "resolve" in text || "timed out" in text || "connection" in text -> "Could not connect. Check your connection and retry."
        "403" in text || "429" in text -> "The site refused this request. Wait a while and retry."
        else -> "The download could not finish. Retry or expand technical details."
    }
}

/** One URL per whitespace-delimited item; validate the whole batch before queuing any work. */
fun parseInputUrls(text: String): List<String> {
    val values = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
    require(values.isNotEmpty()) { "Paste at least one http or https link." }
    require(values.all(::isMediaUrl)) { "Enter valid http or https links, one per line." }
    return values
}
fun isMediaUrl(value: String): Boolean = try {
    val uri = java.net.URI(value)
    uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
} catch (_: Exception) { false }
data class PlaylistEntry(val index: Int, val url: String?, val title: String)
fun playlistEntry(index: Int, url: String?, title: String) = PlaylistEntry(index.coerceAtLeast(1), url?.takeIf(::isMediaUrl), title.ifBlank { "Playlist item $index" })
fun sameSubmission(job: Job, url: String, format: Int, destination: String, playlist: Boolean) =
    job.url == url && job.format == format && job.destination == destination && job.playlist == playlist && job.playlistIndex == 0

fun playlistChildren(parent: Job, entries: List<PlaylistEntry>): List<Job> = entries.map { entry ->
    Job(java.util.UUID.randomUUID().toString(), entry.url ?: parent.url, parent.format,
        title = entry.title, destination = parent.destination, destinationLabel = parent.destinationLabel,
        playlistIndex = if (entry.url == null) entry.index else 0, parentId = parent.id)
}
fun recoveredPublicationStatus(status: String, published: Boolean): String = when {
    published -> "Complete"
    status in setOf("Cancelled", "Cancelling") -> "Cancelled"
    status.startsWith("Failed") -> status
    else -> "Queued"
}
fun destinationKind(format: Int) = if (format >= 3) "audio" else "video"
