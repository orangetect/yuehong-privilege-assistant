# Notices and attribution

## 月虹提权助手

月虹提权助手是基于 Stellar、KSuRoot 与 DirtyFrag VR 集成和扩展的 Android 工程。GhostLock 的 Android Activity、profile/OTA 解析、Native 核心和 Rust 提取器来自上游源码，并通过按钮在同一 APK 中打开；助手没有另行实现这些 GhostLock 流程。

本工程的月虹集成与修改部分采用 Mozilla Public License 2.0。完整文本位于根目录 `LICENSE` 与 `LICENSES/MPL-2.0.txt`。

## Stellar

- Project: Stellar
- Author: roro2239 and contributors
- Source: https://github.com/roro2239/Stellar
- License for Stellar modifications: Mozilla Public License 2.0

本工程以内置方式集成 Stellar Manager、Server 与 API，并将月虹提权助手页面作为唯一桌面入口。Stellar 文件及其修改继续遵循 MPL-2.0。

## Stellar API

- Project: Stellar-API
- Source: https://github.com/roro2239/Stellar-API
- Vendored revision: `e22b3a0c76305c57a36696b069938d3c356a290b`
- License: Mozilla Public License 2.0

## Shizuku

- Project: Shizuku
- Author: RikkaApps and contributors
- Source: https://github.com/RikkaApps/Shizuku
- License: Apache License 2.0

Stellar 保留的 Shizuku AIDL 与兼容层继续遵循 Apache-2.0。完整文本位于 `LICENSES/Apache-2.0.txt`。

## GhostLock

- Project: GhostLock-App
- Source: https://github.com/YuKongA/ghostlock-app
- Upstream snapshot: user-provided `ghostlock-app-main (3).zip` (2026-10-22), SHA-256 `953FA5F65F9AE6BAE79C16E4769F327A143F4DBB84729AA544C6141559FD4E4B`
- License: Apache License 2.0

上游 App、profile-core、C++ Native 核心、Rust 镜像/OTA 提取器、内核 profile 和许可证随源码交付；应用源代码位于 `third_party/ghostlock`。GhostLock 的运行逻辑以这份上游源码构建，不叠加助手自有的 profile、OTA、管理器或 Native 交接实现。为合并进月虹提权助手 APK，Android App 模块改用 Library 插件，并由助手按钮显式启动上游 Activity；这项包装适配及 SHA-256 锚点见 `third_party/ghostlock/UPSTREAM_SOURCE.md`。完整许可证文本位于 `LICENSES/GhostLock-Apache-2.0.txt`。

版权归各自贡献者所有。各文件继续适用其原有许可证；本说明不替代许可证正文。

## KSuRoot bundled payload components

- Project: KSuRoot
- Source: https://github.com/hmascs/KSuRoot/
- Upstream snapshot: provided archive (2026-09-29) (`KSuRoot-main.zip`, SHA-256 `AE24388FEA5ED0E21EF4F344DB59CB05E7A6F60742C6BF052CE8F2B3413440FC`)
- License: Apache License 2.0

本工程仅保留随 APK 发布的 arm64-v8a 内置提权载荷及其目录校验信息；不包含 KSuRoot 的动态构建算法、页面、Provider 或在线下载逻辑。完整许可证文本位于 `LICENSES/Apache-2.0.txt`。

版权归各自贡献者所有。各文件继续适用其原有许可证；本说明不替代许可证正文。

## DirtyFrag VR native chain

- Project: DirtyFrag VR
- Source: https://github.com/DONGDERS/DirtyFalg-vr
- Synced source: user-provided `DirtyFalg-vr-master.zip` (DirtyFrag v1.16-vr, version code 46, SHA-256 `B6997E293DFCC2D9FF252FA1E202900880A01FB40631E755F4F4BCF3CDCF5BDA`)
- Included files: `manager/src/main/jni/dfroot/` contains the DirtyFrag native chain and VR probe handling; `assistant/src/main/jni/vrext/` parses the offset from boot images and OTA payloads. The assistant passes the selected manager package and recognized ksud path directly to the mode.
- Build status: all eight checked-in KMI modules contain the VR configuration marker and slot expected by `exp.c`, along with VR probe handling code. This confirms the packaged modules expose the VR configuration interface; compatibility still depends on the target device's KMI and requires device-level validation.
- License status: `lkm/dfroot.c` declares GPL and `include.inc` retains an Odzhan BSD-style license header. The upstream repository does not expose one clear root-level LICENSE covering every file, so the remaining DirtyFrag files are retained with an explicit “upstream license pending further verification” note in `LICENSE_REVIEW.md`. These files are not relicensed as MPL-2.0.

版权归各自贡献者所有。各文件继续适用其原有许可证；本说明不替代许可证正文。
