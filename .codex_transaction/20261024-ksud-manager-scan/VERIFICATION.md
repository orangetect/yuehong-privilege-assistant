# KernelSU manager scan verification

Date: 2026-10-24

## Changes

- Keep exact `libksud.so` matching as the first choice.
- Filter the app package directory under `/data/app/<install>/<package>` first; reject a package-directory name containing `android`, case-insensitively.
- Then require the app native-library directory to resolve exactly to that package directory's `lib/arm64` and inspect only direct `.so` children.
- For randomized names, require shared ksud CLI fingerprints and verify `late-load --help` exposes `--kmi`, `--allow-shell`, and `--package-name`.
- Remove APK and split-APK library scanning. No manager-specific package rules were added.
- Do not modify `yhroot_ksu_activate.sh` as part of this change.

The checked CLI interface is shared by [SukiSU Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/main/userspace/ksud/src/cli.rs) and [KernelSU](https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/cli.rs).

## Verification

- `git diff --check`: passed after the final path-order adjustment.
- `:assistant:compileReleaseKotlin --no-daemon`: blocked before Kotlin compilation by `:aidl:compileReleaseAidl` reporting `MalformedInputException` in the Chinese-named workspace.
- Retry with `-x :aidl:compileReleaseAidl`: still blocked by malformed UTF-8 in generated AIDL Java containing the workspace path, plus TLS handshake failures downloading AndroidX/Kotlin dependencies from Google Maven and Maven Central. The assistant Kotlin compile task did not run.
- Tests were not run.
