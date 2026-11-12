# Android device acceptance

Status: **not run — no device connected**. Host checks cannot prove that the
bundled ARM64 executables, MediaStore transactions or Android intents work on a
device. Record device model, Android version, APK digest, result and relevant
`adb logcat` output for each case. Test only media you are allowed to download.

Build `tools/build-release.sh` and run `tools/verify-release.py` from the native
project. Install `../bin/videostore-0.5.0-arm64-v8a-release.apk` using
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

## Additional 0.5.0 cases

| Case | Procedure and expected result |
| --- | --- |
| Separate folders | Choose an audio folder and a different video folder. Queue MP3/best audio and video jobs. Verify each lands in its selected folder, Open/Share work, and Delete file removes the correct document. |
| Persistent folder access | Close/reopen, rotate, kill/restart the process and reboot the device. Folder choices and access persist. Use default resets just that media type. Existing queued items keep the old destination after a preference change. |
| Folder picker cancellation | Cancel the picker; the old destination remains. Attempt a read-only/unavailable provider; show a readable error rather than silently reverting to default. |
| Provider recovery | Kill the app during custom-folder file creation, copying, and just after output close. Resume; partial documents are removed and committed documents appear once. Check local storage, SD card if present, and a cloud provider if used. |
| Revoked grant | Revoke or invalidate a selected folder during work. Job fails visibly and later queued jobs can proceed. Restore the original grant with the picker and retry. Failed publication journals must not be lost when removing history. |
| Multi-link batch | Paste three distinct URLs plus a repeated URL. Verify three jobs in input order, one active at a time. A batch containing an invalid URL adds no jobs. Existing duplicates offer Download again/Add only new links/Back without changing the submitted settings. |
| Playlist | Enable Download full playlists and submit a finite playlist with multiple audio/video entries. Check its per-item queue order, correct format/destination, unique outputs and continued processing after one unavailable item. Try an ordinary link in playlist mode too. |
| Playlist recovery | Kill during Reading playlist and immediately after children appear. Resume; children must be inserted once. Cancel during listing; no permanent Reading playlist/Cancelling state remains. A long playlist should scroll without fetching every offscreen thumbnail. |
| Background and screen off | Start a large file and a multi-item queue. Switch apps, lock the phone and swipe VideoStore out of recents in separate runs. Transfers/conversion and serial queue advancement should continue; unlock/reopen shows current state without restarting an already active job. |
| Notifications | On Android 13+, allow notification permission and verify title, stage, percentage, remaining count and Cancel. Repeat with permission denied; downloading should still start. |
| Interruption limits | Force-stop then reopen; unfinished work should be resumable. Do not expect downloading to continue through force-stop or device shutdown. Record any device battery restrictions that interrupt the service. |

If the accepted engine misbehaves, record the error and device logs; keep the
engine and historical audit records unchanged. A host smoke test is available as
`python tools/test-engine-options.py`; it uses host FFmpeg and does not count as
any device acceptance result.
