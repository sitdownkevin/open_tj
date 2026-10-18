# Open TJ

面向同济 VPN 的 macOS 原生客户端。SwiftUI 界面 + Go TLS 隧道与管理员 helper，用户无需安装 Python 或 Go。

支持菜单栏驻留、钥匙串保存凭据、连接状态与流量统计、日志、断线重连以及睡眠/唤醒处理。默认网关为 vpn.tongji.cn，默认认证方式 LDAP3。最低部署目标 macOS 14；macOS 26+ 使用 Liquid Glass。最低系统及 Intel 真机兼容性仍需发布验收。

## 开发

需要 macOS、支持当前 SwiftUI API 的 macOS 26+ SDK（Xcode 或 Command Line Tools），以及 backend/go.mod 指定的 Go 工具链（当前 1.27.1）。

```sh
make test                    # Go race/vet + Swift 模型自测
make build                   # 当前架构 app → build/
open "build/Open TJ.app"
make package                 # Universal ZIP + SHA-256 → dist/
```

## 目录

| 路径 | 职责 |
| --- | --- |
| apps/macos/OpenTJ.swift | SwiftUI 应用、钥匙串、菜单栏、事件模型及 UI 自测 |
| backend/cmd/motionvpn | 会话监督器、管理员 helper 启动与重连策略 |
| backend/internal/client | 协议、认证、TLS 转发、网络事务、诊断及测试 |
| scripts | 测试、构建、打包、签名公证 |
| docs | 架构、分发、迁移与验收说明 |
| licenses | 随应用分发的第三方许可证 |
| VERSION | 应用版本的唯一配置源 |

## 使用与分发

将解压后的 Open TJ.app 放入 /Applications，再启动并输入 VPN 账号。连接时系统请求管理员授权，用于配置 utun、路由和 DNS。关闭窗口后菜单栏继续运行；退出应用会先断开并恢复网络。升级前先退出旧版本，避免同时运行。

正式面向普通用户分发建议使用 Developer ID 签名并经 Apple 公证的 ZIP，通过 GitHub Releases 或自己的下载页提供。详细步骤见 [分发指南](docs/DISTRIBUTION.md)。本地构建默认为 ad-hoc 签名，不能视为已公证发行版。

应用使用 IPv4 全局路由，连接期间临时关闭 IPv6 并设置拒绝路由；不是防火墙级 kill switch。真实网关、管理员恢复、网络切换与旧系统兼容性需要单独验收，见 [验收清单](docs/RELEASE_CHECKLIST.md)。

## 维护

参见 [架构](docs/ARCHITECTURE.md)、[迁移记录](docs/MIGRATION.md)、[贡献说明](CONTRIBUTING.md) 和 [安全反馈](SECURITY.md)。本项目源码尚未指定开源许可证，公开源码前由维护者选择；项目名称中的 Open 不自动授予开源许可。
