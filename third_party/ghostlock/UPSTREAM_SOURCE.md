# GhostLock upstream source anchor

## Source snapshot

- Archive: `ghostlock-app-main (3).zip`, supplied on 2026-10-22
- SHA-256: `953FA5F65F9AE6BAE79C16E4769F327A143F4DBB84729AA544C6141559FD4E4B`
- Upstream app version name: `1.2`
- Application ID: `com.ghostlock.app`
- License: Apache-2.0

The archive hash matches the previously supplied `(2).zip`; the two archives are byte-for-byte identical.

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
