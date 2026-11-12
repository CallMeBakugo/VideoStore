Native improvements — 0.4.0; device acceptance pending
=====================================================

Current changes
---------------
- Signed ARM64 release 0.4.0, versionCode 1040000. Build and verify with the
  existing tools; `release-verification.json` records the current APK digest,
  permissions, signer continuity and historical engine-audit checks.
- Queue entries retain title, thumbnail, URL, format and error details. Cards
  display download percentage, speed/ETA when available, and explicit fetching,
  downloading, converting and saving stages. Conversion and saving use an
  indeterminate progress indicator. A local fixture test verifies the pinned
  engine's stdout contract with host FFmpeg, including conversion-stage logs.
- Failures have readable explanations, expandable technical details and Retry.
  Stable per-job paths and `--continue` retain partial files after interruption
  or failure. Resumption depends on the site's range/fragment support; it can
  restart when the remote server cannot resume. Cancel removes temporary output;
  retrying a cancelled job begins again. Final output is selected from yt-dlp's
  `after_move` path, so retained fragments cannot be mistaken for finished media.
- Publication retains the pending-URI journal and recovery sweep. Ambiguous
  publication is reconciled before another queued attempt. Completed entries
  and cancelled entries have their leftover temporary files cleaned on recovery.
- Open and Share grant read access to the content URI. Missing files/apps get a
  readable error. Remove from history preserves the saved file; Delete file
  permanently deletes it. Both actions explain their effects in a dialog.
- Main screen puts URL, format and Download first, then separates active work
  from history. Familiar format labels now include explanations. Format selection
  and Wi-Fi-only preference persist; shared text extracts its HTTP(S) URL and
  requires Download to be tapped. Identical URL/format submissions offer Download
  again, and Open existing when a saved URI exists.
- Wi-Fi-only queues wait for validated Wi-Fi and resume automatically while the
  service is alive. A one-second monitor interrupts the engine on Wi-Fi loss,
  retaining partial downloads. This is a best-effort pause, not a network firewall:
  brief traffic can occur between connectivity change and engine termination.
  Thumbnail requests also respect the setting when started. ACCESS_NETWORK_STATE
  is the only added permission; it is normal and has no runtime consent dialog.
- Engine versions and historical provenance records are unchanged. No extra app
  dependencies, engine updater, git commits or pushes were introduced.

Validation
----------
- `:app:testDownloadLogic`: 19 dependency-free JVM regression checks (structured
  progress, unavailable estimates, conversion stages, filenames, failure messages
  and active/terminal status membership).
- `assembleDebug`, `lintDebug`, signed `assembleRelease`/lint-vital and release
  identity/signature verification pass. Full lint retains existing advisory
  warnings about storage permission, dependency age, launcher assets and ABI.
- `python tools/test-engine-options.py`: pinned yt-dlp source on host Python,
  localhost WAV -> MP3 via host FFmpeg; progress, conversion stage, final path
  and output existence pass. This does not run Android native binaries.
- No adb device is connected. First-launch behavior, native MP3/video merge,
  cancellation, recovery, file intents and Wi-Fi transitions remain unverified
  on Android. Follow [DEVICE-ACCEPTANCE.md](DEVICE-ACCEPTANCE.md).

The following 0.3.1 notes are historical; current behavior above supersedes
statements below about restart-from-zero recovery, UI, version and permissions.

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
