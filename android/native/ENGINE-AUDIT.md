# youtubedl-android release audit

Decision: **reject 0.18.1 for release pending verifiable native-source
provenance**. This does not establish a runtime defect. The mission requires
rejecting anything that cannot be verified and stopping if the engine audit
fails; no alternative engine or partial download implementation was introduced.

## Artifact identity

[Maven Central metadata](https://repo.maven.apache.org/maven2/io/github/junkfood02/youtubedl-android/library/maven-metadata.xml)
identifies 0.18.1 as latest stable. Inspected exactly
`io.github.junkfood02.youtubedl-android:{library,common,ffmpeg}:0.18.1`.
URLs, SHA-256 pins and individual ARM64 hashes are recorded in
[audit/engine-0.18.1.json](audit/engine-0.18.1.json). These are rejected-candidate
audit pins, not accepted application dependencies.

[Source tag 0.18.1](https://github.com/yausername/youtubedl-android/tree/d725d5c9a18c3a99a13ee0308bf78275dc310760)
resolves to commit d725d5c9a18c3a99a13ee0308bf78275dc310760. Every ARM64
binary/archive in the engine and FFmpeg AARs matches its checked-in payload
byte for byte. This verifies publication identity; the checked-in payloads
are prebuilt binaries, not their corresponding native source.

## Actual bundled engine

Library contains a Python executable disguised as libpython.so, archive
libpython.zip.so, QuickJS libqjs.so and res/raw/ytdlp. Nested archive paths
and ELF strings identify **Python 3.12.11**; yt-dlp's embedded version.py
identifies **2025.11.12**, source head
335653be82d5ef999cfc2879d005397402eebec1. README's Python 3.8 statement is
incorrect for this artifact. Python 3.14 is absent from the ARM64 payload.
Native Kotlin UI still requires this engine's bundled Python runtime.

FFmpeg is a separate AAR required for MP3 extraction and separate-stream video
merging. It provides libffmpeg.so, libffprobe.so and libffmpeg.zip.so.
Libavcodec is 61.19.101. Executable configuration includes --enable-gpl,
--enable-version3, --enable-libmp3lame, --enable-libx264, --enable-libx265,
and --disable-libfdk-aac. Library license string says GPL version 3 or later.
No exact FFmpeg source revision is established here; ABI numbers and
version-looking strings alone do not establish that revision.

## ARM64 and API 34

ARM64 payloads are present; ELF architecture is AArch64. FFmpeg's Android
ident note identifies NDK r28c/API 24. Upstream library uses compileSdk 34 and
minSdk 24. Source executes binaries from application nativeLibraryDir, extracts
dependency archives into noBackupFilesDir and supplies LD_LIBRARY_PATH,
PYTHONHOME and SSL_CERT_FILE. Integration requires extracted native libraries
(useLegacyPackaging/extractNativeLibs) for executable paths to exist.

Traversed DT_NEEDED from Python, FFmpeg, ffprobe and QuickJS, following archive
symlinks across both payloads: 102 names visited, no unresolved dependencies
outside expected Android system libraries. Some unused auxiliary libraries
have missing dependencies; these are outside the reachable executable closure
and were not treated as launch failures.

Static checks support compatibility but do not prove execution under GrapheneOS
hardening or first-launch download success. No device execution or emulator test
was performed. Runtime compatibility remains unverified.

## Failed provenance gate

The wrapper POM and upstream LICENSE declare GPL-3.0. FFmpeg embeds a
GPLv3-or-later build with many dependencies; Python and QuickJS need their own
license notices and provenance. The three AARs and nested FFmpeg archive have
no named LICENSE/COPYING/NOTICE entries. Application notices could be supplied,
but notices alone do not establish corresponding source.

The pinned [Python build guide](https://github.com/yausername/youtubedl-android/blob/d725d5c9a18c3a99a13ee0308bf78275dc310760/BUILD_PYTHON.md)
and [FFmpeg build guide](https://github.com/yausername/youtubedl-android/blob/d725d5c9a18c3a99a13ee0308bf78275dc310760/BUILD_FFMPEG.md)
clone UNPINNED termux/termux-packages and describe historical Python 3.7.2/3.8
and package examples. They do not identify the Termux recipe commit, patches,
complete dependency versions or source checksums used for these binaries.
[Release notes](https://github.com/yausername/youtubedl-android/releases/tag/0.18.1)
only describe QuickJS addition; release assets are debug APKs. No native
corresponding-source bundle or reproducible build manifest was found in this
release/tag.

Exact source/build provenance for the bundled native dependency tree therefore
could not be verified. Candidate rejected under this mission's verification
rule, without claiming upstream is legally noncompliant or binaries technically
unusable. Resolve with a complete source/recipe manifest for these exact payloads
or an explicitly authorized audited source build. Do not silently swap engines
or assume a current Termux checkout reconstructs these bytes.

## Reproducing inspection

Download the three URLs in the JSON and compare with sha256sum. Clone the
pinned source tag/commit and compare each AAR's jni/arm64-v8a/* against
<module>/src/main/jniLibs/arm64-v8a/*. Treat libpython.zip.so and
libffmpeg.zip.so as ZIP files, preserving Unix symlinks when inspecting the ELF
dependency graph. Use readelf -h/-n/-d and strings on extracted ELF files;
inspect res/raw/ytdlp as ZIP for yt_dlp/version.py. No engine binary was
executed on the host. Downloads/checkout were confined to /tmp/vs-audit;
no secret values were required.
