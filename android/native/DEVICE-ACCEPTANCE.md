# Android device acceptance

Status: **not run — no device connected**. Host checks cannot prove that the
bundled ARM64 executables, MediaStore transactions or Android intents work on a
device. Record device model, Android version, APK digest, result and relevant
`adb logcat` output for each case. Test only media you are allowed to download.

Build `tools/build-release.sh` and run `tools/verify-release.py` from the native
project. Install `../bin/videostore-0.4.0-arm64-v8a-release.apk` using
`adb install -r` on the device with the previous release. Confirm that history
and the launcher entry survive the update. Use a spare device/profile for the
fresh-install checks below; preserve the main installation's data.

| Case | Procedure and expected result |
| --- | --- |
| First launch | On a fresh installation, download public audio with default Best audio. Engine initializes, title/thumbnail appear, stages advance and a playable file appears in Downloads/VideoStore. Broad storage access is not needed for publication. |
| MP3 | Select Audio only (mp3). Observe Downloading, then Converting with an indeterminate indicator, then Saving. Open the resulting MP3 and confirm playback. |
| Video merge | Use a source with separate video/audio tracks and Best video+audio. Observe Converting during merge. Verify both picture and sound in the saved file. Repeat with 1080p/720p caps. |
| Cancel | Cancel separately during Fetching details, Downloading, Converting and Saving; repeat from the foreground notification if visible. Job settles on Cancelled; native processes stop, no incomplete public file remains, and the next queued job proceeds. Retry of a cancelled job starts anew. |
| Failure and Retry | Interrupt connectivity or use an unavailable URL. Verify readable error, expandable details and Retry retaining URL/format/title. Restore connectivity and retry a transferable source. Saved output must be correct and appear once. |
| Partial recovery | Interrupt the app process during a large transfer, reopen and Resume queue. With a resumable source, confirm the engine's byte/fragment continuation in logs. With a non-resumable source, restarting must still produce a correct file. |
| Publication recovery | Interrupt the process during Saving and just after publication. Resume queue. Check that incomplete pending rows are removed, a committed row is retained, and no duplicate public file appears. |
| File actions | Open and Share audio/video with receiving apps. Remove from history and confirm the file remains in Files. On another entry, Delete file and confirm both file and entry disappear. Remove a file externally and verify Open/Share show a readable error. |
| Preferences and sharing | Change format, close and reopen; selection persists. Share text containing a URL into VideoStore, including while it is already open and repeating the same URL. It prefills without downloading. Rotate after editing the URL; the edit survives. |
| Duplicates | Submit the same URL/format again while active and after completion. Verify Download again needs an explicit tap, Back leaves the queue unchanged, and Open existing opens the saved file. |
| Wi-Fi only | Enable on cellular/offline: queue waits. Join validated Wi-Fi: transfer begins. Lose Wi-Fi mid-download: engine pauses and partial files remain. Restore Wi-Fi: transfer resumes. Toggle preference off while waiting: queue proceeds. Queued jobs can be cancelled while waiting. |
| Relaunch | Kill the process in Fetching details, Downloading, Converting, Saving and Cancelling. Reopen and resume; no job stays permanently in an obsolete active stage. |

If the accepted engine misbehaves, record the error and device logs; keep the
engine and historical audit records unchanged. A host smoke test is available as
`python tools/test-engine-options.py`; it uses host FFmpeg and does not count as
any device acceptance result.
