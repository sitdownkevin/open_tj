# 架构

## Android

`apps/android/core` 以 Kotlin 实现现有网关协议，`apps/android/app` 通过
Android `VpnService` 管理系统接口。两端使用 `testdata/vpn-protocol.json`
验证协议一致性；Android 不运行 macOS helper。详见 [Android 指南](ANDROID.md)。

## macOS

SwiftUI 主进程通过 stdin 向 Go 监督器发送凭据与 stop/sleep/wake 命令，通过 stdout 接收逐行 JSON 事件。凭据存入 macOS 钥匙串。后端会被复制到用户 Application Support 目录执行，延续原来的启动路径。

Go 监督器负责网关认证、TLS 隧道、重连策略、日志与流量事件。它通过 osascript 的管理员授权启动同一可执行文件的 --helper 模式。helper 通过临时 Unix socket 与随机 token 握手，创建 utun，并事务式管理路由、DNS、代理及 IPv6 设置。停止后回滚并返回恢复回执；网络恢复不能确认时停止自动重连。

正式运行路径仅使用 WireGuard 的 tun 包，未使用 WireGuard 协议或用户态 gVisor 网络栈。backend/internal/client 保留同包单元测试；cmd 中保留监督器策略测试。Swift 文件内 --self-test 验证事件解析和界面模型，不连接 VPN。

## 兼容标识

- Bundle ID：local.motionvpn.prototype。
- Keychain service：local.motionvpn.prototype.credentials。
- 后端工作目录：~/Library/Application Support/MotionVPNPrototype/go-backend。
- 日志：~/Library/Logs/Open TJ/connections-go.jsonl。

这些历史标识保留以兼容现有账号与配置。若正式发布前改为自己控制的反向域名，应同时设计钥匙串/偏好迁移，不要只修改 Info.plist。

## 后续维护边界

当前整理优先保持行为和已有测试，Swift 源文件仍为单文件实现。后续可按 App、View、ViewModel、KeychainStore、BackendProcess 分拆，再逐步迁移自测到独立测试 target。管理员 helper 仍是每次授权的进程，不是已安装的 LaunchDaemon；若采用 ServiceManagement，需要独立的安装、签名验证和升级设计。
