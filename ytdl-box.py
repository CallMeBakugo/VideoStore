#!/usr/bin/env python3
"""ytdl-box: single-window yt-dlp wrapper. Paste, choose, download. No memory."""

import sys

from PySide6.QtCore import Qt, QThread, Signal
from PySide6.QtWidgets import (
    QApplication,
    QComboBox,
    QFileDialog,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QProgressBar,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from core import FORMATS, Grabber

BG = "#08181c"
PANEL = "#0d2426"
ACCENT = "#00e5c7"
LIME = "#7dff9a"
TEXT = "#c9e7d4"

QSS = f"""
QMainWindow {{ background: {BG}; }}
QWidget {{ background: {BG}; color: {TEXT};
           font-family: 'FantasqueSansM Nerd Font';
           font-size: 14px; }}
QLineEdit, QComboBox {{
    background: {PANEL}; color: {TEXT};
    border: 1px solid #1d3a3c; border-radius: 6px; padding: 6px;
}}
QPushButton {{
    background: {PANEL}; color: {ACCENT};
    border: 1px solid {ACCENT}; border-radius: 6px; padding: 6px 16px;
}}
QPushButton:hover {{ background: #123; color: {LIME}; }}
QPushButton:disabled {{ color: #4a5f5f; border-color: #2a4446; }}
QProgressBar {{
    background: {PANEL}; border: 1px solid #1d3a3c;
    border-radius: 6px; text-align: center; height: 16px;
}}
QProgressBar::chunk {{ background: {ACCENT}; border-radius: 5px; }}
QLabel#status {{ color: {LIME}; }}
QLabel#title {{ font-family: Cinzel; font-size: 16px; color: {ACCENT}; }}
"""


class GrabWorker(QThread):
    progress = Signal(float)
    file_sig = Signal(str)
    done = Signal(str)
    failed = Signal(str)

    def __init__(self, grabber: Grabber, cmd: list[str]) -> None:
        super().__init__()
        self.grabber = grabber
        self.cmd = cmd

    def run(self) -> None:  # pragma: no cover - thread body
        try:
            final = self.grabber.run(
                self.cmd,
                on_progress=lambda p: self.progress.emit(p),
                on_file=lambda f: self.file_sig.emit(f),
            )
            self.done.emit(final)
        except Exception as exc:  # noqa: BLE001
            self.failed.emit(str(exc))


class Box(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.grabber = Grabber()
        self.worker: GrabWorker | None = None
        self.setWindowTitle("VideoStore")
        self.setMinimumWidth(520)
        self.build_ui()

    def build_ui(self) -> None:
        root = QWidget()
        self.setCentralWidget(root)
        v = QVBoxLayout(root)
        v.setContentsMargins(12, 12, 12, 12)
        v.setSpacing(8)

        title = QLabel("VideoStore")
        title.setObjectName("title")
        title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        v.addWidget(title)

        self.url = QLineEdit()
        self.url.setPlaceholderText("Paste link…")
        v.addWidget(self.url)

        self.fmt = QComboBox()
        for key, spec in FORMATS.items():
            self.fmt.addItem(spec["label"], userData=key)
        v.addWidget(self.fmt)

        path_row = QHBoxLayout()
        self.outdir = QLineEdit(self.grabber.default_outdir())
        browse = QPushButton("Where?")
        browse.setFixedWidth(84)
        browse.clicked.connect(self.on_browse)
        path_row.addWidget(self.outdir, 1)
        path_row.addWidget(browse)
        v.addLayout(path_row)

        btn_row = QHBoxLayout()
        self.btn_dl = QPushButton("Download")
        self.btn_clear = QPushButton("Clear")
        self.btn_dl.clicked.connect(self.on_download)
        self.btn_clear.clicked.connect(self.on_clear)
        btn_row.addWidget(self.btn_dl)
        btn_row.addWidget(self.btn_clear)
        v.addLayout(btn_row)

        self.bar = QProgressBar()
        self.bar.setRange(0, 100)
        v.addWidget(self.bar)

        self.status = QLabel("")
        self.status.setObjectName("status")
        v.addWidget(self.status)

    # ---- handlers ----
    def on_browse(self) -> None:
        d = QFileDialog.getExistingDirectory(self, "Download to", self.outdir.text())
        if d:
            self.outdir.setText(d)

    def on_download(self) -> None:
        url = self.url.text().strip()
        if not url:
            self.status.setText("Paste a link first.")
            return
        try:
            cmd = self.grabber.build_cmd(url, self.fmt.currentData(), self.outdir.text())
        except Exception as exc:  # noqa: BLE001
            self.status.setText(f"Can't start: {exc}")
            return
        self.btn_dl.setEnabled(False)
        self.status.setText("Fetching…")
        self.worker = GrabWorker(self.grabber, cmd)
        self.worker.progress.connect(self.bar.setValue)
        self.worker.file_sig.connect(lambda f: self.status.setText(f"→ {f}"))
        self.worker.done.connect(self.on_done)
        self.worker.failed.connect(self.on_fail)
        self.worker.start()

    def on_done(self, final: str) -> None:
        self.btn_dl.setEnabled(True)
        self.bar.setValue(100)
        self.status.setText(f"Done.{(' ' + final) if final else ''} Next link.")

    def on_fail(self, msg: str) -> None:
        self.btn_dl.setEnabled(True)
        self.status.setText(f"Failed: {msg}")

    def on_clear(self) -> None:
        self.url.clear()
        self.bar.setValue(0)
        self.status.setText("")


def main() -> None:
    app = QApplication(sys.argv)
    app.setStyleSheet(QSS)
    w = Box()
    w.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()