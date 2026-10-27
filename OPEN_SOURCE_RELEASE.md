# 开源发行说明

此目录是月虹提权助手 v1.6.4 的公开源码快照，来源于当前权威开发仓库。

## 已移除的私有内容

- `server.properties` 及其中的运营域名、接口路径、模块标识、V2 公钥和 key ID。
- `keystore.properties`、发行 JKS/KeyStore、证书私钥及签名口令。
- APK、AAB、R8 mapping、Gradle/CMake/Rust 构建缓存、调试日志和 Codex 本地事务文件。
- 启动版本/频道门禁已从公开源码当前版本移除。
- 当前版本字段统一为根工程 `build.gradle` 中的 `yuehongVersionCode=164` 与 `yuehongVersionName=v1.6.4`；`manager` Manifest 和 `assistant` BuildConfig 均引用该版本源。

## 本地配置

1. 复制 `server.properties.example` 为 `server.properties`。
2. 使用自己的 HTTPS 接口和协议签名公钥填写所需字段；不需要联网功能的字段保持为空。
3. 如需 Release 签名，自行创建 `keystore.properties` 和密钥库；两者均已被 `.gitignore` 排除。
4. 发布或提交前运行：

```powershell
pwsh -NoProfile -File .\tools\verify_public_source.ps1
```

## 许可证映射

- 组合发行版、Stellar 与月虹修改文件：MPL-2.0。
- Shizuku 兼容层：Apache-2.0。
- GhostLock：Apache-2.0。
- KSuRoot 动态载荷组件：Apache-2.0。
- DirtyFrag VR 原生链：按 `LICENSE_REVIEW.md` 和 `NOTICE.md` 中的上游文件头及待核对说明归属，不声明为 MPL-2.0。

DirtyFrag VR 上游仓库为 <https://github.com/DONGDERS/DirtyFalg-vr>；集成快照同步自用户提供的 `DirtyFalg-vr-master.zip`。公开源码中的 `manager/src/main/jni/dfroot/` 包含 native 链和 VR LKM 源码，`assistant/src/main/jni/vrext/` 提供 boot.img / OTA 偏移解析。模式直接接收助手传入的管理器包名和 ksud 路径。对仓库中的八个 KMI 二进制进行只读检查，均发现 VR 配置标记和 VR 探针处理代码；目标设备上的 KMI 兼容性仍需验证。目录和二进制中的 `dfroot` 是上游协议名称。

完整条款和上游归属见 `LICENSE`、`LICENSES/` 与 `NOTICE.md`。
