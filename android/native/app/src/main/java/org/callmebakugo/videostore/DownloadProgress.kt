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
fun safeMediaName(title: String, extension: String): String =
    title.map { if (it in "\\/:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("").take(120).trim().ifBlank { "VideoStore" } + "." + extension

val activeStatuses = setOf("Queued", "Fetching details", "Downloading", "Converting", "Saving", "Publishing", "Cancelling", "Waiting for Wi-Fi")
val formatDescriptions = listOf("Highest available quality; may merge audio and video", "Up to 1080p", "Up to 720p", "MP3 — widely compatible", "Best audio — original format, avoids conversion")
fun friendlyError(details: String): String {
    val text = details.lowercase()
    return when {
        "no space" in text -> "Your device is out of storage. Free some space and retry."
        "unsupported url" in text -> "This link is not supported. Check the URL."
        "private" in text || "sign in" in text || "login" in text -> "This media requires access or sign-in. Try a publicly available link."
        "unavailable" in text || "removed" in text || "404" in text -> "This media is unavailable or has been removed."
        "network" in text || "resolve" in text || "timed out" in text || "connection" in text -> "Could not connect. Check your connection and retry."
        "403" in text || "429" in text -> "The site refused this request. Wait a while and retry."
        else -> "The download could not finish. Retry or expand technical details."
    }
}
