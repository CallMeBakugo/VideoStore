Native migration — 0.3.1 built; device acceptance pending
===================================================

Binding rulings (2026-11-11)
----------------------------
- Accepted engine: published io.github.junkfood02.youtubedl-android:library and
  :ffmpeg, both 0.18.1. FFmpeg binaries trace to the upstream Termux build
  recipe in yausername/youtubedl-android BUILD_FFMPEG.md (Termux Docker build,
  prefix /data/youtubedl-android/usr). The recipe-to-published-artifact chain
  remains unattested. The user expressly accepts that residual provenance risk;
  it is documented, no longer an implementation blocker.
- ENGINE-AUDIT.md and audit/engine-0.18.1.json remain untouched historical records.
- Package org.callmebakugo.videostore; versionCode 1030000; versionName 0.3.1.
  Sign using ~/.local/share/videostore.keystore, alias videostore, with VS_*
  environment variables from android/buildozer.secrets. Never commit secrets.
- Permission ruling: the user explicitly approved FOREGROUND_SERVICE and
  FOREGROUND_SERVICE_DATA_SYNC in addition to INTERNET and
  MANAGE_EXTERNAL_STORAGE, because the requested foreground service requires
  them. POST_NOTIFICATIONS is omitted; Android still permits foreground-service
  operation, though notification visibility depends on the platform/user state.
- No engine substitution, runtime engine updater, or git commits. Stop and
  report any engine misbehavior discovered during integration.

Implementation
---------------
- Gradle wrapper 8.9 with distribution SHA-256, AGP 8.7.3, Kotlin/Compose compiler
  2.0.21, JDK 17. SDK compile/target 34, build tools 34.0.0, minSdk 29, ARM64 only.
- Compose UI dependencies explicitly versioned: activity-compose 1.9.3,
  Compose UI 1.7.5, Material3 1.3.1; required by the requested Compose migration.
- Engine initialization (including FFmpeg) precedes downloads on first launch;
  native libraries use legacy extraction so the bundled executables can run.
- Foreground service serializes downloads and reports progress/cancellation.
  Cancellation repeatedly kills the app's native engine processes and their
  descendants using /proc, covering engine launch races without relying on the
  upstream shell-based cancellation implementation. The worker unwinds and
  removes temporary output before running the next job.
- AtomicFile queue journal and observable Compose job status. Interrupted jobs
  requeue when the service starts; UI provides Resume queue. START_STICKY also
  resumes service work after system restart when Android permits it.
- MediaStore pending URI is journaled before copying. Recovery preserves a row
  already committed, removes incomplete rows, and sweeps orphan pending rows
  owned by this package in Download/VideoStore, including the insert/journal
  crash window. No broad filesystem access is used for publication.
- UI: URL paste/share, exact five Kivy format labels/selectors, default audio-only
  (best), output folder display, queue status, cancel and content-URI opening.
- Adaptive and density-specific legacy launcher assets copied from Kivy resources.

Validation roadmap
-------------------
1. PASS: ./gradlew assembleRelease under Temurin JDK 17; release lint-vital gates passed.
2. PASS: apkanalyzer package/version/debuggable/permissions and apksigner checks.
   Signed ARM64-only APK, v2 signature, signer equal to published 0.2.1,
   versionCode 1030000, versionName 0.3.1, non-debuggable, exact four approved
   permissions. Native executable extraction enabled and service non-exported.
   Launcher resource bytes match Kivy; both historical audit hashes unchanged.
3. Device acceptance remains required: in-place install, first-launch audio-only
   download and MediaStore publication; MP3 conversion, video merge,
   cancellation during download/merge/publication, and process-death recovery.
   adb currently lists no connected device; no Android runtime result is claimed.

Release artifact and reproduction
----------------------------------
- APK: ../bin/videostore-0.3.1-arm64-v8a-release.apk
- Size: 75142014 bytes.
- SHA-256: 0424ac2d61998472718cd7e3e8582be6075f3f72c0ac7cbe17d7fc547c176f76
- Signing certificate SHA-256: 35bccd5649e2069ba6f6662f3a4fe78e8bcd050879f605cb6184d3a3788a035d
- Machine-readable host evidence: [release-verification.json](release-verification.json).
- From repository root: `android/native/tools/build-release.sh` loads VS_* secrets
  without printing them, selects JDK 17, runs assembleRelease and copies the APK.
- Verify: `JAVA_HOME="$(mise where java@temurin-17)" android/native/tools/verify-release.py`.
- No device/emulator was available (`adb devices` returned an empty device list).
  Host checks establish update-compatible identity/signing, not successful install
  or engine execution. No engine runtime failure was observed because it could
  not be exercised here. The full success criterion is therefore still pending.

Device acceptance still to execute
-----------------------------------
1. With the published 0.2.x installed, `adb install -r` the APK above. Preserve
   app data. Confirm the existing home-screen icon and app entry update correctly.
2. On first native launch, submit a supported URL with the default Audio only
   (best) format. Verify playable audio in Downloads/VideoStore and Open action.
   Do not grant broad storage access as a prerequisite for MediaStore publication.
3. Check Audio only (mp3), a merged video format, notification progress and Cancel.
   Cancel during extraction, transfer, merge and publication; confirm the engine
   processes terminate, temporary output disappears and the next queued job runs.
4. Kill the process during download and during publication; reopen, Resume queue,
   and confirm no stranded pending MediaStore rows or duplicate completed rows.
   Recovered unfinished jobs restart from zero; partial-byte resumption is not claimed.
5. If the accepted engine misbehaves, stop and report the error/device logs;
   do not replace it or add dependencies without another ruling.

No git commits were made. Existing Kivy sources, android/BUILD-RESEARCH.md,
prior APKs, and the engine audit records were not modified.
