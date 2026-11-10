"""Best-effort persistent Python crash reports, including startup failures."""
import os
import sys
import threading
import traceback

from .storage import external_files_dir


def record(exc_type, exc, tb):
    report = "".join(traceback.format_exception(exc_type, exc, tb))
    try:
        directory = external_files_dir()
        os.makedirs(directory, exist_ok=True)
        path = os.path.join(directory, "videostore-crash.log")
        with open(path, "a", encoding="utf-8") as stream:
            stream.write(report + "\n")
        report += "\nLog: " + path
    except Exception:
        pass
    print(report, file=sys.stderr)
    return report


def install(report_error):
    def handle(exc_type, exc, tb):
        report_error(record(exc_type, exc, tb))
    sys.excepthook = handle
    threading.excepthook = lambda args: handle(args.exc_type, args.exc_value, args.exc_traceback)
