"""VideoStore core, library transport: yt_dlp as a Python module.

Same contract as desktop core.Grabber (url, format_key, outdir ->
progress callbacks) but runs yt-dlp in-process via its Python API, which
is what an APK can ship. No subprocess, no wl-paste, no GUI imports.
"""

import os

from yt_dlp import YoutubeDL

from .storage import default_outdir

FORMATS = {
    "label_best": {
        "label": "Best video+audio",
        "ydl": {"format": "bv*+ba/b"},
    },
    "label_1080": {
        "label": "Best ≤1080p",
        "ydl": {"format": "bv*[height<=1080]+ba/b[height<=1080]/b"},
    },
    "label_720": {
        "label": "Best ≤720p",
        "ydl": {"format": "bv*[height<=720]+ba/b[height<=720]/b"},
    },
    "label_audio_mp3": {
        "label": "Audio only (mp3)",
        "ydl": {"format": "ba/b", "postprocessors": [{
            "key": "FFmpegExtractAudio", "preferredcodec": "mp3",
        }]},
    },
    "label_audio_best": {
        "label": "Audio only (best)",
        "ydl": {"format": "ba/b"},
    },
}

OUTPUT_TEMPLATE = "%(title)s.%(ext)s"


class LibGrabber:
    """In-process yt-dlp with progress hook; cancellable from the UI."""

    def __init__(self) -> None:
        self._ydl: YoutubeDL | None = None
        self._cancel = False

    default_outdir = staticmethod(default_outdir)

    def run(
        self,
        url: str,
        format_key: str,
        outdir: str,
        on_progress=None,
    ) -> str:
        fmt = FORMATS.get(format_key)
        if fmt is None:
            raise ValueError(f"unknown format key: {format_key}")
        url = (url or "").strip()
        if not url:
            raise ValueError("empty URL")
        outdir = os.path.expanduser(outdir)
        os.makedirs(outdir, exist_ok=True)

        ydl_opts = {
            "outtmpl": os.path.join(outdir, OUTPUT_TEMPLATE),
            "noplaylist": True,
            "restrictfilenames": True,
            "quiet": True,
            "no_warnings": True,
            "progress_hooks": [self._hook(on_progress)],
            **fmt["ydl"],
        }
        final = ""

        def settle(d):
            nonlocal final
            if d.get("status") == "finished":
                final = d.get("filename") or final

        ydl_opts["postprocessor_hooks"] = [settle]
        try:
            with YoutubeDL(ydl_opts) as ydl:
                self._ydl = ydl
                if self._cancel:
                    raise RuntimeError("cancelled")
                info = ydl.extract_info(url, download=True)
                if self._cancel:
                    raise RuntimeError("cancelled")
                if info and not final:
                    final = ydl.prepare_filename(info)
                return final
        finally:
            self._ydl = None

    def cancel(self) -> None:
        self._cancel = True

    def _hook(self, on_progress):
        def hook(d):
            if self._cancel:
                raise RuntimeError("cancelled")
            if d.get("status") == "downloading" and on_progress:
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                pct = (d.get("downloaded_bytes", 0) / total * 100) if total else 0
                on_progress(pct)
        return hook
