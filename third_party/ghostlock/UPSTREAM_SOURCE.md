# GhostLock upstream source anchor

## Source snapshot

- Archive: `ghostlock-app-main.zip`, supplied on 2026-10-24
- SHA-256: `8DFA1BE6F138A8FA17B1A081F63E5564165269D1FF6A8A84B8A12D7EB9ECA7B0`
- Upstream app version name: `1.2`
- Application ID: `com.ghostlock.app`
- License: Apache-2.0

Compared with the 2026-10-22 snapshot, this archive adds three kernel profiles and updates the profile index. The Android, native, and extractor source files are unchanged.

## Synced source

- `app/src/main` and `app/src/test`: upstream app source and resources
- `profile-core/src`: upstream profile implementation and tests
- `src`: upstream C++ Native implementation
- `tools/extract_rs`: upstream Rust OTA/image extractor
- `LICENSE`: upstream license text

The five Native files that differed from the earlier local snapshot were replaced with the archive versions. The stale top-level `Makefile` and the assistant-side generated kernel gate were removed.

## YueHong integration

The upstream Android App module is built as an Android Library and merged into the YueHong assistant APK. The assistant button explicitly opens the upstream `com.ghostlock.app.ui.MainActivity`; no separate GhostLock APK or launcher entry is produced. The host APK uses its existing application ID and application class.

The packaging adapter removes the upstream Application registration so it can coexist with YueHong's `StellarApplication`; the GhostLock activity now creates the same upstream repository directly from `applicationContext`. The GhostLock activity keeps its upstream theme and is internal to the host app. Library BuildConfig fields preserve GhostLock's displayed version and Shizuku user-service version. Since upstream requires API 31, the combined assistant APK minimum is Android 12.

The Gradle module builds the Native executable and Rust extractor from the synced upstream sources. Apart from the Application/Activity packaging adaptation described above, the runtime Kotlin, Java, AIDL, profile, Native, and extractor sources are unchanged from the archive.
