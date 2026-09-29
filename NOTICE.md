# Notices and attribution

## 月虹提权助手

月虹提权助手是基于 Stellar、GhostLock 与 KSuRoot 进行集成和扩展的 Android 工程。月虹界面、启动验证、设备适配、协议客户端、OTA 自动解析、提权流程和 KernelSU 激活属于本工程的修改范围。

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
- Upstream snapshot: `ghostlock-app-main.zip`, SHA-256 `10898C812713F3AE239BB931EC6BA1CACFF60656D0FB49C87B58B09E335DD19E`
- License: Apache License 2.0

GhostLock C 核心、Rust 镜像/OTA 提取器、内核偏移表及许可证随源码交付；本次上游 native/profile 镜像位于 `third_party/ghostlock/upstream`，当前 App 仍使用父目录的兼容运行链路；Android 页面适配位于 `assistant/src/main/java/roro/stellar/yuehong/ghostlock`。Rust 提取器保留月虹的 `--require-xbl` 参数及其 HTTP 依赖配置，避免丢失骁龙 OTA 校验和在线提取能力。完整许可证文本位于 `LICENSES/GhostLock-Apache-2.0.txt`。

版权归各自贡献者所有。各文件继续适用其原有许可证；本说明不替代许可证正文。

## KSuRoot dynamic payload components

- Project: KSuRoot
- Source: https://github.com/hmascs/KSuRoot/
- Upstream snapshot: provided archive (2026-09-29) (`KSuRoot-main.zip`, SHA-256 `AE24388FEA5ED0E21EF4F344DB59CB05E7A6F60742C6BF052CE8F2B3413440FC`)
- License: Apache License 2.0

GhostLock+KSuRoot 的动态载荷方案仅移植 KSuRoot 的纯 Kotlin `kernelpack` 构建算法和 arm64-v8a 本地动态载荷/执行辅助程序；未移植其 Compose 页面、Provider 或在线下载逻辑。完整许可证文本位于 `LICENSES/Apache-2.0.txt`。

版权归各自贡献者所有。各文件继续适用其原有许可证；本说明不替代许可证正文。
