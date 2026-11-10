"""VideoStore mobile: paste, download, close. No memory."""

from kivy.clock import Clock
from kivy.lang import Builder
from kivy.metrics import dp
import queue
import sys

from kivy.properties import NumericProperty, StringProperty
from kivy.base import ExceptionHandler, ExceptionManager
from kivy.app import App

from .core_lib import FORMATS, LibGrabber
from . import diagnostics, storage

KV = """
#:import dp kivy.metrics.dp

BoxLayout:
    orientation: "vertical"
    padding: dp(16)
    spacing: dp(10)

    canvas.before:
        Color:
            rgba: 0.03, 0.09, 0.11, 1
        Rectangle:
            pos: self.pos
            size: self.size

    Label:
        text: "VideoStore"
        font_size: dp(26)
        color: 0.0, 0.9, 0.78, 1
        size_hint_y: None
        height: dp(40)
        bold: True

    TextInput:
        id: url
        hint_text: "Paste link…"
        multiline: False
        size_hint_y: None
        height: dp(44)
        foreground_color: 0.79, 0.91, 0.83, 1
        background_normal: ""
        background_active: ""
        background_color: 0.05, 0.14, 0.15, 1

    Spinner:
        id: fmt
        text: app.fmt_labels[0]
        values: app.fmt_labels
        size_hint_y: None
        height: dp(44)
        background_color: 0.05, 0.14, 0.15, 1
        color: 0.79, 0.91, 0.83, 1

    TextInput:
        id: outdir
        text: app.outdir
        multiline: False
        size_hint_y: None
        height: dp(44)
        foreground_color: 0.79, 0.91, 0.83, 1
        background_normal: ""
        background_active: ""
        background_color: 0.05, 0.14, 0.15, 1

    Widget:
        size_hint_y: None
        height: dp(6)

    Button:
        id: dl
        text: "Download" if app.state != "downloading" else "Downloading…"
        disabled: app.state == "downloading"
        size_hint_y: None
        height: dp(48)
        background_color: (0.0, 0.55, 0.49, 1) if app.state != "downloading" else (0.3, 0.35, 0.35, 1)
        color: 1, 1, 1, 1
        on_press: app.start()

    Button:
        id: cancel
        text: "Cancel" if app.state == "downloading" else "Clear"
        size_hint_y: None
        height: dp(48)
        background_color: 0.16, 0.14, 0.23, 1
        on_press: app.cancel_or_clear()

    ProgressBar:
        id: bar
        max: 100
        value: app.pct
        size_hint_y: None
        height: dp(14)

    Label:
        text: app.status
        font_size: dp(13)
        color: 0.49, 1.0, 0.60, 1
        size_hint_y: None
        height: dp(30)
        halign: "left"
        text_size: self.width, None
"""


class VideoStoreApp(App):
    pct = NumericProperty(0.0)
    state = StringProperty("idle")
    status = StringProperty("")

    def __init__(self, **kw):
        super().__init__(**kw)
        self.grabber = LibGrabber()
        self.outdir = LibGrabber.default_outdir()
        self.fmt_labels = [f["label"] for f in FORMATS.values()]
        self._thread = None
        self._events = queue.SimpleQueue()

    def build(self):
        self.title = "VideoStore"
        return Builder.load_string(KV)

    def on_start(self):
        diagnostics.install(lambda report: self._events.put(("error", report)))
        events = self._events

        class VisibleErrors(ExceptionHandler):
            def handle_exception(self, exc):
                events.put(("error", diagnostics.record(type(exc), exc, exc.__traceback__)))
                return ExceptionManager.PASS

        self._exception_handler = VisibleErrors()
        ExceptionManager.add_handler(self._exception_handler)
        Clock.schedule_interval(self._drain_events, 0.1)
        try:
            if storage.request_shared_storage():
                self.status = "Allow all-files access for Download; otherwise using app files."
        except Exception:
            self.status = "Using app files; shared Download access unavailable."

    def on_pause(self):
        return True

    def on_resume(self):
        self.outdir = LibGrabber.default_outdir()
        self.root.ids.outdir.text = self.outdir
        self.status = "Save folder: " + self.outdir

    def _drain_events(self, dt):
        while not self._events.empty():
            kind, value = self._events.get_nowait()
            if kind == "progress":
                self._setpct(value)
            elif kind == "done":
                self._done(value)
            elif kind == "fail":
                self._fail(value)
            elif kind == "error":
                from kivy.uix.popup import Popup
                from kivy.uix.textinput import TextInput
                Popup(title="VideoStore error", content=TextInput(
                    text=value, readonly=True, multiline=True)).open()

    def _label_to_key(self, label):
        for k, f in FORMATS.items():
            if f["label"] == label:
                return k
        return "label_best"

    def start(self):
        if self.state == "downloading":
            return
        url = self.root.ids.url.text.strip()
        if not url:
            self.status = "Paste a link first."
            return
        key = self._label_to_key(self.root.ids.fmt.text)
        outdir = self.root.ids.outdir.text or self.outdir
        self.grabber = LibGrabber()
        self.state = "downloading"
        self.pct = 0.0
        self.status = "Fetching…"

        import threading

        def work():
            try:
                final = self.grabber.run(url, key, outdir, on_progress=self._prog)
                self._events.put(("done", final))
            except Exception as exc:  # noqa: BLE001
                msg = "cancelled" if self.grabber._cancel else str(exc)
                if msg != "cancelled":
                    self._events.put(("error", diagnostics.record(*sys.exc_info())))
                self._events.put(("fail", msg))

        self._thread = threading.Thread(target=work, daemon=True)
        self._thread.start()

    def _prog(self, pct):
        self._events.put(("progress", pct))

    def _setpct(self, pct):
        self.pct = float(pct)
        self.status = f"Downloading… {pct:.0f}%"

    def _done(self, final):
        self.state = "idle"
        self.pct = 100.0
        self.status = "Done. Next link."

    def _fail(self, msg):
        self.state = "idle"
        self.status = f"Failed: {msg}" if msg != "cancelled" else "Cancelled."

    def cancel_or_clear(self):
        if self.state == "downloading":
            self.grabber.cancel()
        else:
            self.root.ids.url.text = ""
            self.pct = 0.0
            self.status = ""