"""Android storage helpers; importing this module never probes storage."""
import os
import tempfile


def activity():
    from jnius import autoclass
    return autoclass("org.kivy.android.PythonActivity").mActivity


def external_files_dir():
    try:
        directory = activity().getExternalFilesDir(None)
        if directory is not None:
            return str(directory.getAbsolutePath())
    except Exception:
        pass
    return os.environ.get("ANDROID_PRIVATE") or os.path.expanduser("~")


def writable(directory):
    try:
        os.makedirs(directory, exist_ok=True)
        with tempfile.TemporaryFile(dir=directory):
            pass
        return True
    except (OSError, ValueError):
        return False


def default_outdir():
    if "ANDROID_ARGUMENT" in os.environ or "ANDROID_PRIVATE" in os.environ:
        shared = "/storage/emulated/0/Download"
        if writable(shared):
            return shared
        return external_files_dir()
    return os.path.join(os.path.expanduser("~"), "Downloads")


def request_shared_storage():
    """Return True if settings was opened; permission is checked again on resume."""
    if not ("ANDROID_ARGUMENT" in os.environ or "ANDROID_PRIVATE" in os.environ):
        return False
    from jnius import autoclass
    Environment = autoclass("android.os.Environment")
    if Environment.isExternalStorageManager():
        return False
    Intent = autoclass("android.content.Intent")
    Settings = autoclass("android.provider.Settings")
    Uri = autoclass("android.net.Uri")
    current = activity()
    try:
        intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.setData(Uri.parse("package:" + str(current.getPackageName())))
        current.startActivity(intent)
    except Exception:
        current.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    return True
