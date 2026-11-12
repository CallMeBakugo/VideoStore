# VideoStore

Small, single-window yt-dlp wrapper for the desktop. Paste a link, pick a
format, hit Download. Nothing else.

    python3 ytdl-box.py

Requires: yt-dlp on PATH, PySide6.

## What it is

- URL field, format dropdown (best video+audio, ≤1080p, ≤720p, mp3,
  best audio), download-folder field with a picker, Download and Clear.
- Downloads run in a worker thread; the progress bar fills live.
- Defaults to ~/Downloads.

## What it is not

- No history, no logs, no queue, no memory. Paste, download, close.
- Not a library, not a service. A box.

## Layout

- `core.py` — pure download logic (yt-dlp subprocess, progress
  parsing, cancel). Zero GUI imports; this is what other shells
  (an Omarchy bar widget, an Android app) import.
- `ytdl-box.py` — the PySide6 shell.

The bar-widget sibling lives in [VideoStoreBar](../VideoStoreBar).

## Mobile (Android APK)

The Android app is now native Kotlin/Compose, using the pinned
`youtubedl-android` and bundled FFmpeg engine. It supports MP3 conversion,
merged video, a persistent queue, media cards with progress stages, retry and
partial-download recovery, Open/Share, and separate history removal and file
deletion. Shared links prefill the URL; downloading requires a tap. Format
selection and the optional Wi-Fi-only setting are remembered.

Open **Download folders…** to choose separate audio and video destinations with
Android's folder picker. Both default to Downloads/VideoStore and persist across
app/device restarts. Folder changes apply to newly queued work; existing jobs
keep their selected destination. Android may require a subfolder rather than a
storage root. The app retains grants used by queued jobs and saved-file history.

Paste several links, one per line, to queue them together. Enable **Download full
playlists** to expand playlist links into individual jobs. The queue downloads
one item at a time, in submission/playlist order; failed items do not stop later
items. Playlist expansion and the child jobs are journaled together.

The foreground service keeps working when another app is visible, including
when the task is swiped away. Active work holds a timed, renewed partial wake
lock for screen-off transfers/conversion. Android 13+ asks for notification
permission so progress and Cancel can stay visible. Denying notifications does
not block downloading. Force-stop, device shutdown and system/vendor power
restrictions can still interrupt work; unfinished jobs can be resumed on reopen.
These device behaviors still require on-device acceptance testing.

Build the signed ARM64 APK with JDK 17 and the existing local signing setup:

    android/native/tools/build-release.sh

Output: `android/bin/videostore-0.5.0-arm64-v8a-release.apk`.

Host checks (set `JAVA_HOME` to JDK 17):

    android/native/gradlew -p android/native testDownloadLogic assembleDebug lintDebug
    python android/native/tools/test-engine-options.py
    python android/native/tools/verify-release.py

The engine-options smoke test uses the pinned Python bundle, localhost media,
and host FFmpeg. It does not establish Android runtime behavior. See
[device acceptance](android/native/DEVICE-ACCEPTANCE.md) and the
[implementation notes](android/native/IMPLEMENTATION.md) for remaining checks.
The prior Kivy sources remain under `android/videostore` as historical code.
