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

## 组合发行方式

- 根目录 `LICENSE`：MPL-2.0。
- Stellar 与月虹修改文件：MPL-2.0。
- GhostLock、KSuRoot 与 Shizuku 文件：Apache-2.0。
- `NOTICE.md` 列出上游来源和本工程修改范围。
