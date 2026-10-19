# 开源发行说明

此目录是月虹提权助手 v1.6.0 的公开源码快照，来源于当前权威开发仓库。

## 已移除的私有内容

- `server.properties` 及其中的运营域名、接口路径、模块标识、V2 公钥、key ID、频道链接和更新地址。
- `keystore.properties`、发行 JKS/KeyStore、证书私钥及签名口令。
- APK、AAB、R8 mapping、Gradle/CMake/Rust 构建缓存、调试日志和 Codex 本地事务文件。
- 公告页中原有的硬编码更新服务器地址；公开构建改从本地 `server.properties` 的 `updateUrl` 读取。
- 当前版本字段统一为根工程 `build.gradle` 中的 `yuehongVersionCode=160` 与 `yuehongVersionName=v1.6.0`；`manager` Manifest 和 `assistant` BuildConfig 均引用该版本源。

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
- DirtyFrag 原生链：按 `LICENSE_REVIEW.md` 和 `NOTICE.md` 中的上游文件头及待核对说明归属，不声明为 MPL-2.0。

DirtyFrag v1.16 同步自用户提供的 `DirtyFrag-master (1).zip`。公开源码中的 `manager/src/main/jni/dfroot/` 同时包含 DirtyFrag 原生代码、上游 LKM 文件和八个 KMI 内核模块构建产物；月虹只保留当前应用的设备保护路径和管理器选择交接，DirtyFrag 直接执行所选管理器的 `libksud.so`，不调用激活脚本。目录和二进制中的 `dfroot` 是 DirtyFrag 上游协议名称，不代表另一个上游来源。

完整条款和上游归属见 `LICENSE`、`LICENSES/` 与 `NOTICE.md`。
