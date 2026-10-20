# Android 开发与验收

Android 客户端位于 `apps/android`，支持 Android 8.0（API 26）及以上。
应用 ID 为 `io.github.sitdownkevin.opentj`。本阶段为 Android 预览版；
macOS 的版本号与发布流程保持独立，Android 的显示版本附带 `android-preview`。

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
make android
```

预览 APK 和校验和输出到 `dist/OpenTJ-<VERSION>-android-preview.apk`。
它使用本机 Android debug key，适合安装测试；不是正式分发签名。
不同机器/CI 的 debug key 不同，不能保证直接覆盖安装。

```sh
adb install -r dist/OpenTJ-0.2.1-android-preview.apk
cd apps/android
./gradlew connectedDebugAndroidTest
```

设备测试会验证 Android Keystore 加解密和删除。它应在专用测试安装上运行，
会清理该测试应用的已存账号设置，不会连接真实 VPN。

`make android-release` 输出经过 R8 优化的未签名 release APK。
正式发布需使用自己保管的固定签名密钥签名并校验，密钥不可提交到仓库。
可用 `ANDROID_VERSION_CODE` 提供递增版本码。Android CI 单独上传预览产物，
不会修改既有 macOS GitHub Release。

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

## 真机验证记录

2026-10-20 在 vivo X200 Pro（`V2405A`，Android 16 / API 36）上使用真实账号
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
