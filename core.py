"""ytdl-box core: pure download logic, zero GUI imports.

Wraps the yt-dlp CLI as a subprocess so the same grab() contract can drive
the PySide6 desktop app today and an Android (BeeWare/Kivy) shell later.
"""

import os
import re
import subprocess
from collections.abc import Callable

FORMATS = {
    "label_best": {
        "label": "Best video+audio",
        "args": ["-f", "bv*+ba/b"],
    },
    "label_1080": {
        "label": "Best ≤1080p",
        "args": ["-f", "bv*[height<=1080]+ba/b[height<=1080]/b"],
    },
    "label_720": {
        "label": "Best ≤720p",
        "args": ["-f", "bv*[height<=720]+ba/b[height<=720]/b"],
    },
    "label_audio_mp3": {
        "label": "Audio only (mp3)",
        "args": ["-x", "--audio-format", "mp3"],
    },
    "label_audio_best": {
        "label": "Audio only (best)",
        "args": ["-f", "ba/b"],
    },
}

OUTPUT_TEMPLATE = "%(title)s.%(ext)s"

PROGRESS_RE = re.compile(r"(\d+(?:\.\d+)?)%")
DEST_RE = re.compile(r"Destination:\s+(.+)$")
Merging_RE = re.compile(r"\[Merger\].*?into\s+(.+)$")


class Grabber:
    """Builds and runs yt-dlp. Pure logic; safe to headless-test."""

    def __init__(self, yt_dlp_bin: str = "yt-dlp") -> None:
        self.bin = yt_dlp_bin
        self.proc: subprocess.Popen | None = None  # set by run(); kill via cancel()

    def cancel(self) -> None:
        """Terminate the running yt-dlp, if any."""
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()

    @staticmethod
    def default_outdir() -> str:
        return os.path.expanduser("~/Downloads")

    def build_cmd(self, url: str, format_key: str, outdir: str) -> list[str]:
        fmt = FORMATS.get(format_key)
        if fmt is None:
            raise ValueError(f"unknown format key: {format_key}")
        url = url.strip()
        if not url:
            raise ValueError("empty URL")
        outdir = os.path.expanduser(outdir)
        os.makedirs(outdir, exist_ok=True)
        return [
            self.bin,
            *fmt["args"],
            "--no-playlist",
            "--no-mtime",
            "--restrict-filenames",
            "--newline",  # progress: one line per update, easy to parse
            "-o", os.path.join(outdir, OUTPUT_TEMPLATE),
            "--", url,
        ]

    def run(
        self,
        cmd: list[str],
        on_progress: Callable[[float], None],
        on_file: Callable[[str], None],
    ) -> str:
        """Run the command synchronously; parse progress/dest from stdout."""
        final_file = ""
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        self.proc = proc
        assert proc.stdout is not None
        for line in proc.stdout:
            line = line.strip()
            m = PROGRESS_RE.search(line)
            if m:
                on_progress(float(m.group(1)))
                continue
            d = DEST_RE.search(line)
            if d:
                on_file(d.group(1))
                continue
            d = Merging_RE.search(line)
            if d:
                final_file = d.group(1)
                on_file(final_file)
        rc = proc.wait()
        if rc != 0:
            raise RuntimeError(f"yt-dlp exited with code {rc}")
        return final_file