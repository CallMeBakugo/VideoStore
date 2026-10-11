[app]
title = VideoStore
package.name = videostore
package.domain = org.callmebakugo

# Pin p4a to the last pre-3.14 commit: python3/hostpython3 recipes at 3.11.13
# with their matching patch set. master pins them to 3.14.2, for which
# kivy/pyjnius Android wheels do not exist yet. buildozer git-resets the
# checkout to this exact commit on every run, so no branch can be clobbered.
p4a.commit = 3762c88c

source.dir = app
source.include_exts = py,png,jpg,kv,ttf,xml

version = 0.2.1

requirements = python3,kivy,yt-dlp,android

orientation = portrait

# Launcher icon: legacy square PNG + adaptive layers (fg monogram in safe zone,
# bg purple gradient) — buildozer passes these to p4a as --icon-fg/--icon-bg.
icon.filename = %(source.dir)s/icon.png
icon.adaptive_foreground.filename = %(source.dir)s/adaptive/ic_launcher_fg.png
icon.adaptive_background.filename = %(source.dir)s/adaptive/ic_launcher_bg.png

fullscreen = 0

# Release-signed with our own keystore (password in the keepass vault entry
# VideoStore_APK_Keystore; keystore at ~/.local/share/videostore.keystore).
android.release_artifact = apk

# Release signing creds come from the local gitignored buildozer.secrets file,
# which buildozer reads via environment variables set in the build script.

[buildozer]
log_level = 2
warn_on_root = 1

# Our fork of p4a pinned to the last pre-3.14 commit: python3/hostpython3
# recipes at 3.11.13 with their matching patch set. The default master pins
# recipes to 3.14.2, for which kivy/pyjnius Android wheels do not exist yet.
# Build for the phone you own
[app@android]
android.archs = arm64-v8a
android.api = 34
android.minapi = 27
android.allow_backup = False

