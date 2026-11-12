package org.callmebakugo.videostore

/** Dependency-free JVM regression checks; run :app:testDownloadLogic. */
fun main() {
    check(parseDownloadProgress("VS| 42.8%| 2.50MiB/s|00:17") == DownloadProgress(42, "2.50MiB/s", "00:17"))
    check(parseDownloadProgress("VS|NA|NA|Unknown") == DownloadProgress(null, "", ""))
    check(parseDownloadProgress("VS|105%| 1MiB/s | 00:00 ")?.percent == 100)
    check(parseDownloadProgress("VS|bad|NA|NA")?.percent == null)
    check(parseDownloadProgress("[download] Destination: video.mp4") == null)
    check(parseDownloadProgress("VS|50%") == null)
    check(isConversionLine("[ExtractAudio] Destination: audio.mp3"))
    check(isConversionLine("[Merger] Merging formats into video.mp4"))
    check(!isConversionLine("[download] 100%"))
    check(safeMediaName("a/b\\c:\nname?", "mp3") == "a_b_c__name_.mp3")
    check(safeMediaName("  ", "webm") == "VideoStore.webm")
    check(safeMediaName("x".repeat(200), "mp4").length == 124)
    check(friendlyError("ERROR: No space left on device").contains("storage"))
    check(friendlyError("Connection timed out").contains("connection"))
    check(friendlyError("HTTP Error 403").contains("refused"))
    check(friendlyError("private video").contains("sign-in"))
    check(friendlyError("unrecognized failure").contains("technical details"))
    check(setOf("Fetching details", "Converting", "Saving", "Waiting for Wi-Fi", "Cancelling").all { it in activeStatuses })
    check(listOf("Failed", "Cancelled", "Complete").none { it in activeStatuses })
    println("Download logic: 19 checks passed")
}
