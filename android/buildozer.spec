[app]
title = VideoStore
package.name = videostore
package.domain = org.callmebakugo

source.dir = app
source.include_exts = py,png,jpg,kv,ttf

version = 0.1.0

requirements = python3,kivy==2.3.0,yt-dlp,android

orientation = portrait

# Your icon: purple VS film-gradient
icon.filename = %(source.dir)s/icon.png

fullscreen = 0

# APK signing key (generated at build time if absent; set for release builds)
# android.release_artifact = aab

[buildozer]
log_level = 2
warn_on_root = 1

# Build for the phone you own
[app@android]
android.archs = arm64-v8a
android.api = 34
android.minapi = 27
android.allow_backup = False
