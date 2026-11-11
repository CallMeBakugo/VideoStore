VideoStore Android 0.3.0 release
==============================

On the build host, install mise Java `temurin-17` and Python `3.12`, plus
Buildozer's Android/Linux system prerequisites. Keep the trusted, gitignored
`android/buildozer.secrets` file exporting `VS_KEYSTORE`, `VS_KEYSTORE_ALIAS`,
`VS_KEYSTORE_PASS`, and `VS_KEY_PASS`; `rebuild.sh` maps them to the
`P4A_RELEASE_*` names p4a reads. Keep the existing signing key for Obtainium
updates. Do not print or commit those secrets.

Run `./android/rebuild.sh` from the repository. It resolves mise installation
paths, puts Java 17 first on PATH, runs Buildozer/Cython from the mise Python
3.12 host interpreter (never a venv — p4a shells out to `pip install --user`,
which aborts inside virtualenvs), sources signing secrets, and removes the
entire project Buildozer cache
plus global recipe packages and p4a build caches. This deliberately forces a
clean rebuild; network access and time to download dependencies are required.
The p4a pin **3762c88c** must remain: its runtime is Python **3.11.13**. Host
Python 3.12 and target Python 3.11 are intentionally different.

The script runs Buildozer twice. Pass 1 may fail with `No module named 'math'`
while building pyjnius; any other failure stops the script. Between passes it
copies the built hostpython stdlib extensions into `Lib/lib-dynload`, skipping
directories already staged. Pass 2 reuses the cached builds and must succeed.
This workaround does not modify the p4a checkout, which Buildozer resets on
each run. The Android settings live in `[app]`: arm64-v8a only, API 34,
minimum API 27, and backups disabled.

The script builds a release APK and fails unless exactly one fresh APK exists,
no native library references `libpython3.14.so`, every `libmain.so` references
`libpython3.11.so`, that runtime is packaged, and `assets/private.tar` contains
`videostore/main.pyc`. Only upload the printed VERIFIED APK. These checks catch
stale build layers; they cannot replace a real device launch/download test.

Review/commit the changes on the host. Create and push the **new** tag
`v-mobile-0.3.0`, create its GitHub release, and upload that APK. `git tag` must
fail if the tag exists: never move or force it. Use a new version/tag if needed.
Obtainium uses tags to detect updates.

Install via Obtainium. Grant all-files access in the Settings screen to use
shared Download; declining keeps saves in the app's external files directory
(shown in the folder field; removed on uninstall). Test one download, cancel,
and a second download. Python exceptions appear in a diagnostic screen/popup
and append to `videostore-crash.log` in that external files directory.
If launch fails, run `adb logcat -c`, then `adb logcat > videostore-launch.log`,
launch the app, and stop capture with Ctrl-C. Native loader failures happen
before Python can show a diagnostic screen or write its log.

Existing format limitation: this spec does not ship an FFmpeg executable. MP3
conversion and separate video/audio merging require FFmpeg and can fail even
with a healthy install. For the first storage smoke test, use Audio only (best)
on a supported URL. The build verification does not validate yt-dlp site support
or FFmpeg-dependent formats.
