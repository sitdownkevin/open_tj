# Android 开发与验收

Android 客户端位于 `apps/android`，支持 Android 8.0（API 26）及以上。
应用 ID 为 `io.github.sitdownkevin.opentj`。本阶段为 Android 预览版；
版本维护于 `apps/android/VERSION`，与 macOS 版本和发布标签独立。
GitHub Release 提供固定密钥签名的 APK，本地 debug 版本名附带 `-dev`。

## 用户安装

从仓库 README 的「下载 APK」入口获取 `.apk`，在手机上安装后输入自己的
校园 VPN 账号并同意系统 VPN 授权即可连接。无需电脑、ADB 或本地编译。
系统如提示未知来源，按提示允许当前下载来源安装；不需要关闭系统安全保护。
Release 中的校验和与签名证书指纹用于核对下载文件。

后续 Release APK 使用同一发布密钥，可直接覆盖升级。早期 debug 安装包
签名不同，不能与 Release 互相覆盖；迁移前需保管好账号信息。调试时不得
通过卸载或清空用户数据来绕过签名不匹配。

## 架构

- `core`：纯 Kotlin/JVM 模块，实现网关 HTTPS 登录、TLS 隧道、配置与 IPv4
  包解析、心跳、双向数据泵、流量统计、重试分类。无 Android 依赖。
- `app`：Jetpack Compose 界面、Android Keystore 凭据加密、`VpnService`、
  前台通知、网络切换与系统 VPN 授权。
- `testdata/vpn-protocol.json`：Go 与 Kotlin 共用的协议样例，防止两端控制包
  和 IPv4 编码发生偏差。

网关仍为 `vpn.tongji.cn`。Android 不执行桌面端的 `networksetup`、`route`
或管理员 helper，也不依赖 Go/JNI/NDK。关闭 VPN 文件描述符后，由 Android
移除本应用的 VPN 网络配置。

所有网关 socket 都在连接前尝试通过 `VpnService.protect`，并强制绑定到选定的
非 VPN 底层网络。部分厂商在 TUN 接口建立前会让 `protect` 返回 `false`；
此时仍以 `Network.bindSocket` 成功作为继续连接的前提，避免网关流量回环。
DNS 查询通过同一个底层 `Network` 完成。TLS 使用系统信任链和 HTTPS 主机名
验证，不提供跳过验证开关。TLS 握手设置读超时。

VPN 为 IPv4 全局模式，不调用 `allowBypass` 或 `allowFamily(AF_INET6)`。
按 Android `VpnService.Builder` 的规则，不配置 IPv6 地址、路由和 DNS，
即阻止该 VPN 的 IPv6 出站流量。断线重连时会释放接口，因此这不是跨重连
期间的 kill switch。本预览版不宣称支持系统始终开启 VPN 或 lockdown，
并在 manifest 中禁用 always-on。

部分 Android 16 设备在仅配置 IPv4 的 TUN 上仍会先投递 IPv6 包。数据泵会
直接丢弃这些包并继续转发 IPv4，而不是把首包误判为致命协议错误。IPv6
仍由 Android 生成的 `unreachable` 默认路由阻断。

## 功能

- LDAP3、LDAP2、LDAP 和本地数据库认证选项。
- 连接、主动断开、系统撤销授权、持续前台通知与通知内断开。
- Wi-Fi/移动网络切换后重建连接；临时网络错误指数退避，等待网络恢复。
- 错误凭据、TLS 验证失败、协议不兼容不自动反复提交。
- 每秒流量、累计流量、60 点趋势图、最多 200 条内存日志。
- 可选记住密码：Android Keystore AES-256-GCM；随机 IV；禁止备份凭据。
  取消记住密码立即删除密文。密码不通过 Intent、日志或 saved state 传递。

系统进程被杀后不自动恢复，也不在开机时自动登录。用户需重新打开应用连接。
日志仅在内存中保存；没有自动网站探测、分析 SDK 或凭据上传功能。

## 构建

安装 JDK 17、Android SDK Platform 36 和 Build Tools 35.0.0。
配置 `JAVA_HOME`、`ANDROID_HOME`，或用 Android Studio 打开 `apps/android`。
Gradle Wrapper 固定为 8.13 并校验分发包 SHA-256；AGP 为 8.13.2。

```sh
make android-test
make android          # dist/android/debug/
make android-release  # dist/android/unsigned/
```

`make android` 输出 `dist/android/debug/OpenTJ-<VERSION>-android-debug.apk`。
它使用本机 Android debug key，仅适合开发。普通用户应下载 Release APK。
`make android-release` 输出 `dist/android/unsigned/OpenTJ-<VERSION>-android-unsigned.apk`，
经过 R8 优化，但在签名前不能安装。

```sh
adb install -r dist/android/debug/OpenTJ-0.2.1-android-debug.apk
cd apps/android
./gradlew connectedDebugAndroidTest
```

设备测试会验证 Android Keystore 加解密和删除。它应在专用测试安装上运行，
会清理该测试应用的已存账号设置，不会连接真实 VPN。

## GitHub Actions 发布

`.github/workflows/android.yml` 在 `main`、PR 和手动触发时运行单测、Debug/Release
lint 与 R8 release 构建。`android-v*` 标签会触发完整发布流程；维护者手动在
`main` 运行时，也会签名并执行安装测试，但不创建 Release。流程如下：

1. 标签必须与 `apps/android/VERSION` 相符。
2. 构建未签名 APK，将产物传给独立签名 job。
3. `make android-sign` 使用固定密钥进行 zipalign 与 APK v2/v3 签名，校验公开
   证书指纹、签名与文件 SHA-256。缺少密钥或指纹不匹配时立即失败。
4. Android API 26 和 36 模拟器安装、启动、再次覆盖安装同一签名 APK。
5. 所有检查通过后，发布 APK、APK SHA-256 和证书指纹到独立 Android prerelease。
   不替换已有发布附件，也不改变 macOS 的 Latest Release。

维护者首次需要配置 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、
`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 四个仓库 Actions Secrets。
公开证书指纹位于 `apps/android/release-certificate.sha256`，私钥必须保存在仓库外
并另行备份。具体配置、更新下载链接和推送标签步骤见 [分发指南](DISTRIBUTION.md)。

本地签名使用同名密码与 alias 环境变量，以及指向私钥文件的 `ANDROID_KEYSTORE_FILE`：
先运行 `make android-release`，再运行 `make android-sign`；最终 APK 输出到
`dist/android/release`。不要在公开 shell 日志中打印密钥或密码。
版本码由 `major * 10000 + minor * 100 + patch` 计算，minor/patch 须小于 100；
升级需提高 `apps/android/VERSION`。

## 开发环境检查记录

以下结果来自 2026-10-20 的本地开发环境，可复现检查不等同于校园网真机验收：

- [x] JDK 17、Android SDK Platform 36 下完成 `make android` 和 R8 release 构建；
  lint 为 0 error，剩余提示仅为依赖更新和 SharedPreferences KTX 建议。
- [x] `core` 的 14 个 JVM 测试和 app 的 1 个单元测试通过。
- [x] Android 16 / API 36 arm64 Google APIs 模拟器上 2 个仪器测试通过，
  覆盖 Activity 冷启动和 Android Keystore 加解密/删除。
- [x] Debug APK 和 R8 release 冒烟包可启动；VPN 授权拒绝、通知权限拒绝、
  飞行模式等待、主动断开后网络恢复均完成模拟器冒烟检查。

模拟器没有真实校园账号，不能验证认证、DNS、IPv4 出口或 IPv6 泄漏；
这些项目仍须按下节清单在真机执行。

## GitHub Actions 验证记录

2026-10-20 的[完整流程验证](https://github.com/sitdownkevin/open_tj/actions/runs/39433495309)
使用 GitHub Ubuntu runner 从源码构建并签名 APK，已完成：

- [x] Gradle Wrapper 校验、15 项 JVM/app 单元测试、Debug/Release lint 和 R8 构建。
- [x] 独立签名任务使用固定发布密钥，校验 APK v2/v3 签名、证书指纹和 SHA-256。
- [x] Android 8.0 / API 26 和 Android 16 / API 36 模拟器安装、冷启动和重复覆盖安装。
- [x] 本地独立 Android 16 arm64 模拟器安装、冷启动和重复覆盖安装签名优化包。

这些安装测试不使用校园账号；真实网络验证来自下节记录的 debug 构建。
维护者手机上的 debug 包未卸载、未清空数据，也未被不同签名的发布包替换。

## 真机验证记录

2026-10-20 在 vivo X200 Pro（`V2405A`，Android 16 / API 36）的 debug 构建上使用真实账号
完成以下检查：

- [x] Wi-Fi 下 `protect` 返回 `false` 时，绑定选定底层网络后 TLS 登录、配置读取
  和系统 VPN 接口创建成功。
- [x] TUN 首批数据中的 IPv6 包被安全忽略，后续 IPv4 数据持续转发。
- [x] 手机内 DNS、ICMP 和 HTTP 数据面可用；VPN 界面显示已连接且流量计数增长。
- [x] IPv6 探测返回 `No route to host`，未绕过 IPv4 VPN。
- [x] 界面主动断开后 TUN 释放，再次连接成功并可继续访问 HTTP。
- [x] 关闭 Wi-Fi 后自动切换到移动数据，恢复 Wi-Fi 后自动切回；两次切换后
  HTTP 均返回 `200 OK`。

尚未完成锁屏/后台 30 分钟、飞行模式、通知断开、系统撤销授权、不同 Android
版本和 30 分钟以上稳定性测试，因此预览版仍不宣称覆盖全部真机场景。

## 真机验收

自动化协议测试和构建成功不能代替校园 VPN 真机验收。以下项目由测试者
使用自己的账号在可恢复环境执行，未执行前保持未勾选：

- [ ] Android 8.0、Android 14 和当前系统版本安装与启动。
- [ ] 同意/拒绝 VPN 授权；同意/拒绝通知权限。
- [ ] 正确凭据连接；错误凭据只提交一次且错误提示明确。
- [ ] 校园网站、DNS、IPv4 全局出口与 IPv6 阻断。
- [ ] 锁屏、切到后台、30 分钟以上持续连接。
- [ ] Wi-Fi/移动数据切换、飞行模式及断网重连。
- [ ] 界面/通知断开、系统撤销授权、强制停止后网络恢复。
- [ ] 重连累计流量、日志边界、密码显示和记住密码关闭。
- [ ] 不同厂商省电策略、系统已有其他 VPN 时的授权行为。
- [ ] 正式签名包升级安装、版本码与权限说明。

官方接口参考：
- https://developer.android.com/develop/connectivity/vpn
- https://developer.android.com/reference/android/net/VpnService.Builder
- https://developer.android.com/develop/background-work/services/fgs/service-types
