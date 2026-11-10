"""p4a entry point: the package sits next to main.pyc in private.tar."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

if __name__ == "__main__":
    try:
        from videostore.main import VideoStoreApp
        VideoStoreApp().run()
    except BaseException:
        from videostore.diagnostics import record
        report = record(*sys.exc_info())
        # This also handles errors importing the main app (e.g. yt-dlp).
        # Native loader/Kivy initialization failures still require adb logcat.
        from kivy.app import App
        from kivy.uix.textinput import TextInput

        class StartupErrorApp(App):
            def build(self):
                return TextInput(text=report, readonly=True, multiline=True)

        StartupErrorApp().run()
