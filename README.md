# 月虹提权助手

月虹提权助手是一个内置 Stellar Manager、Server、API 与 DirtyFrag VR 的 Android 提权工作台。应用不再依赖外部 Shizuku 或外部 Stellar 管理器，可直接在应用内通过 Android 无线调试完成 Stellar 自激活，再使用 `Stellar.newProcess()` 执行命令；DirtyFrag VR 作为独立的 Android 12+ 模式运行。

- 应用 ID：`roro.stellar.yuehong`
- 版本：`v1.6.4`（`versionCode 164`）
- 最低系统：Android 12（API 31）
- 目标系统：Android API 37
- ABI：`arm64-v8a`

## 运行流程

1. 启动时检查必要权限；权限齐全后进入提权模式选择页。
2. 检查内置 Stellar Binder 和 `stellar` 权限。
3. 服务未启动时进入应用内无线调试自激活页；配对、连接和服务启动均由内置 Stellar 组件完成。
4. 服务已启动但权限未确认时，使用 Stellar 自身授权页完成显式授权。
5. 授权后进入命令工作台，终端和本地提权文件都通过 `Stellar.newProcess()` 执行。
6. Stellar 提权使用 APK 内置提权文件手动选择，或导入本地载荷；提权执行不再请求设备匹配和载荷下载服务。
7. DirtyFrag VR 模式单独运行 DirtyFrag v1.16-vr native 链；管理器包名和识别器返回的 ksud 路径由助手直接传入，bootstrap 完成管理器交接。
8. 提权成功后可按原流程检测并激活 KernelSU；KernelSU 接管后的 `su` 不可用时立即停止后续命令。

应用保留正式版包名；使用相同证书签名时可覆盖安装现有正式版。公开源码不附带发行证书。整合工程未使用 Stellar 上游的 `sharedUserId`。

## 源码结构

```text
assistant/  月虹提权助手 UI、本地载荷和提权业务
manager/    Android 宿主、自激活界面、无线调试、授权、Binder 接收与 DirtyFrag VR 原生链
server/     以 ADB shell 或 Root 身份运行的 Stellar 特权服务
api/        随工程固定版本交付的 Stellar API、AIDL、Provider 与共享代码
shizuku/    Stellar 内部保留的 Shizuku 兼容层
LICENSES/   第三方许可证文本
```

`assistant` 是 Android Library，由 `manager` 打包为月虹提权助手 APK。上游 GhostLock App 以 Android Library 方式嵌入同一 APK；助手中的 GhostLock 按钮进入上游 `MainActivity`，GhostLock 的界面、profile、OTA 解析和 Native 流程均由同步的上游代码提供。用户只需安装月虹提权助手一个 APK。桌面入口仍是月虹提权助手的 `roro.stellar.yuehong.activities.MainActivity`。

## 主要上游项目

- [Stellar](https://github.com/roro2239/Stellar)：内置的 Manager、Server 与核心服务框架。
- [GhostLock-App](https://github.com/YuKongA/ghostlock-app)：嵌入助手 APK 的上游 GhostLock 应用界面、Native 核心、OTA 解析器和内核 profile。
- [KSuRoot](https://github.com/hmascs/KSuRoot/)：APK 内置提权载荷来源。
- [DirtyFrag VR](https://github.com/DONGDERS/DirtyFalg-vr)：DirtyFrag VR 原生提权链和 VR 探针处理来源；本工程集成用户提供的 v1.16-vr 源码。

其他第三方代码的许可证和归属见 [NOTICE.md](NOTICE.md) 与 [LICENSES](LICENSES)。

## 服务器配置

复制 `server.properties.example` 为根目录下的 `server.properties`，填写：

```properties
compatibilityEndpoint=
moduleId=
protocolV2Endpoint=
protocolV2PublicKey=
protocolV2KeyId=
```

`moduleId` 应填写为构建者自己服务端配置的模块 ID；示例文件不携带月虹正式服务的模块 ID 或其他运营配置。

公开源码不包含运营域名、接口路径、模块标识、公钥、key ID、频道链接、更新地址、签名口令或密钥库。需要联网功能时，由构建者在本地复制示例文件后填写自己的 HTTPS 服务和协议密钥。`server.properties`、签名属性和密钥库不会进入公开源码包。

## 构建

需要：

- JDK 21
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0
- Android NDK `29.0.13113456`（或通过 `ANDROID_NDK_HOME` 指定可用版本）
- Rust 工具链及 `aarch64-linux-android` target
- CMake 3.22.1 或更高版本

Windows 中文路径下请使用工程自带脚本。脚本会创建一个仅在构建期间存在的 ASCII 临时目录联接，避免 CMake/Ninja 的中文路径限制，不会复制源码：

构建包含上游 GhostLock 的调试版提权助手：

```powershell
.\tools\build_android.ps1 -Variant Debug
```

构建包含上游 GhostLock 的正式版提权助手：

```powershell
.\tools\build_android.ps1 -Variant Release
```

Release 签名读取根目录下由构建者自行创建的 `keystore.properties`；公开源码不附带任何发行密钥或签名属性。主要产物会同步到：

```text
out/apk/月虹提权助手-v1.6.4-release.apk
out/mapping/mapping-v1.6.4.txt
```

## 安全边界

- 自激活只负责启动 ADB shell/Root 身份的 Stellar 服务，不会让 Android 应用进程本身变成 Root。
- 助手在每次执行前同时检查 Binder 存活状态和 Stellar 授权状态。
- 有线 vivo/iQOO 提权的在线响应、资源元数据和线路票据继续执行 Ed25519 与 SHA-256 校验。
- 本地文件先复制到应用私有缓存，再由 Stellar 写入 `/data/local/tmp/preload.so`；不会修改用户选择的原文件。
- 正式运营接口仍校验应用 ID 和正式签名证书；自行重签名的构建不会连接正式服务。
- DirtyFrag VR 入口在所有系统版本都显示；Android 12 以下仅禁用进入操作。VR 探针处理默认关闭，只有用户手动开启后才使用当前内核 BTF 或提供的偏移；`/dev/df` 已存在时再次点击会安全跳过，重启后可重新运行。

## 许可与归属

本工程是一个由不同许可证文件组成的组合项目。组合发行版以 MPL-2.0 为顶层许可证，原有文件继续保留各自许可证：

- 月虹提权助手集成与修改部分遵循 MPL-2.0。
- Stellar 与月虹修改文件遵循 MPL-2.0，完整文本位于 [LICENSES/MPL-2.0.txt](LICENSES/MPL-2.0.txt)。
- Stellar 中源自 Shizuku 的文件保留 Apache-2.0。
- GhostLock 源码保留 Apache-2.0。
- KSuRoot 本地载荷组件保留 Apache-2.0。
- DirtyFrag VR 原生链按上游文件头与待核对的上游许可证说明归属，不声明为 MPL-2.0。

根目录 [LICENSE](LICENSE) 为 MPL-2.0 完整文本。完整归属与修改说明见 [NOTICE.md](NOTICE.md)，第三方许可文本见 [LICENSES](LICENSES)。发布前可运行 `tools/verify_public_source.ps1` 检查配置、密钥文件和构建产物是否被误加入。
## 提权模式页面

启动验证与频道授权通过后，应用先进入提权模式选择页：

- **GhostLock**：按钮打开同一 APK 内嵌的上游 GhostLock 界面。profile、OTA 解析、Shizuku 和 Native 提权流程由上游实现处理。
- **通用提权助手 · Stellar**：使用 APK 内置提权文件手动选择、本地文件导入、自激活和命令控制台。
- **DirtyFrag VR**：入口始终显示，只有 Android 12+ 设备可以进入。管理器选择和 ksud 路径由助手识别器直接传入。VR 探针处理由用户手动开启，默认关闭；偏移可由运行内核 BTF 自动读取，也可从含 BTF 的 boot.img / OTA payload 解析或手动填写。

DirtyFrag VR 的临时文件位于应用的 device-protected files 目录，bootstrap 不会清理整个 `/data/local/tmp`。仓库中的八个预编译 KMI 模块均包含 VR 配置标记和探针处理代码；目标设备上的 KMI 兼容性仍需验证。上游版本、KMI 构建产物和许可证核对见 [LICENSE_REVIEW.md](LICENSE_REVIEW.md) 与 [NOTICE.md](NOTICE.md)。原生文件名保留上游的 `dfroot` 模块和库名，以维持内核模块协议。

GhostLock 上游源码及本次同步锚点见 `third_party/ghostlock/UPSTREAM_SOURCE.md`。正式提权助手 APK 同时包含从上游源码构建的 GhostLock Native 库与 Rust 提取器。
