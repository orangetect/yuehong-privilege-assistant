# 上游许可证核对

## Stellar

- 上游：https://github.com/roro2239/Stellar
- Stellar 修改部分：Mozilla Public License 2.0（MPL-2.0）。
- Stellar 中保留的 Shizuku 原始代码：Apache License 2.0。
- 本工程的月虹集成与修改部分继续采用 MPL-2.0。

## GhostLock

- 上游：https://github.com/YuKongA/ghostlock-app
- 许可证：Apache License 2.0。
- 上游许可证原文保存在 `third_party/ghostlock/LICENSE` 和 `LICENSES/GhostLock-Apache-2.0.txt`。

## KSuRoot bundled payloads

- 上游：https://github.com/hmascs/KSuRoot/
- 同步版本：2026-09-29 提供的 `KSuRoot-main.zip`（SHA-256 `AE24388FEA5ED0E21EF4F344DB59CB05E7A6F60742C6BF052CE8F2B3413440FC`）。
- 许可证：Apache License 2.0。
- 本工程仅保留随 APK 发布的 arm64-v8a 内置提权载荷及其校验信息，不包含动态载荷构建算法；完整许可证文本为 `LICENSES/Apache-2.0.txt`。

## DirtyFrag VR native chain

- 上游：https://github.com/mitschud/DirtyFrag
- 同步版本：用户提供的 `DirtyFalg-vr-master.zip`，DirtyFrag `v1.16-vr`（46），ZIP SHA-256 `B6997E293DFCC2D9FF252FA1E202900880A01FB40631E755F4F4BCF3CDCF5BDA`。
- 集成位置：`manager/src/main/jni/dfroot/` 和 `assistant/src/main/jni/vrext/`。管理器包名与识别器返回的 ksud 路径由助手直接传递；启动器不重新扫描管理器或重新定位 SO。
- VR 偏移提取器使用本仓库 `third_party/ghostlock/tools/extract_rs/` 的 BootImage/BTF 与 OTA payload 解析代码；只从镜像内真实 BTF 读取 `tracepoint.funcs`，无 BTF 时返回错误，不使用猜测值。内核运行时模式直接读取当前内核 BTF。
- 当前 `manager/src/main/jni/dfroot/ko/` 中的八个 KMI 模块来自 DirtyFrag v1.16，尚未包含 VR 配置槽。启用 VR 探针处理时，native launcher 会拒绝继续；需要用该 VR 源码和对应 DDK 镜像重新生成这些模块后，VR 探针功能才可运行。
- DirtyFrag `lkm/dfroot.c` 声明 GPL；`include.inc` 保留 Odzhan 的 BSD 风格许可头。
- 上游仓库根目录没有一份清晰、统一覆盖全部 DirtyFrag 文件的 LICENSE 文件。当前公开源码保留上述文件头，并将剩余文件标为“上游许可证待进一步核对”；DirtyFrag 代码不笼统声明为 MPL-2.0。

## 组合发行方式

- 根目录 `LICENSE`：MPL-2.0。
- Stellar 与月虹修改文件：MPL-2.0。
- GhostLock、KSuRoot 与 Shizuku 文件：Apache-2.0。
- DirtyFrag VR 原生链：按上游文件头和待核对的上游许可证说明单独归属；不并入 MPL-2.0。
- `NOTICE.md` 列出上游来源和本工程修改范围。
