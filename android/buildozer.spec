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
source.include_exts = py,png,jpg,kv,ttf

version = 0.1.0

requirements = python3,kivy,yt-dlp,android

orientation = portrait

# Your icon: purple VS film-gradient
icon.filename = %(source.dir)s/icon.png

fullscreen = 0

# APK signing key (generated at build time if absent; set for release builds)
# android.release_artifact = aab

[buildozer]
log_level = 2
warn_on_root = 1

# Our fork of p4a pinned to the last pre-3.14 commit: python3/hostpython3
# recipes at 3.11.13 with their matching patch set. The default master pins
# recipes to 3.14.2, for which kivy/pyjnius Android wheels do not exist yet.
p4a.branch = py311-pin

# Build for the phone you own
[app@android]
android.archs = arm64-v8a
android.api = 34
android.minapi = 27
android.allow_backup = False
