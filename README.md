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

The same app ships as an APK — same engine philosophy: pure download logic
(`videostore/core_lib.py`, yt-dlp imported as a library, no subprocess), a
thin Kivy shell (`videostore/main.py`), built with buildozer. No store, no
iOS: releases attach the raw APK here.

Build (Arch example; JDK 17, `buildozer` + `cython` on PATH):

    cd android
    buildozer android debug

Output: `android/bin/videostore-0.1.0-debug.apk`.

Note: yt-dlp's mp3 extraction needs ffmpeg; python-for-android does not
bundle it, so on-device "Audio only (mp3)" returns best-audio native format
(m4a/webm). The other formats are unaffected.