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