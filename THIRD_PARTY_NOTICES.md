# Third-party notices

Open TJ 的 Go 后端运行依赖如下；版本以 backend/go.mod 和 go.sum 为准。

| Dependency | License | Included license |
| --- | --- | --- |
| golang.zx2c4.com/wireguard (tun package) | MIT | licenses/wireguard-LICENSE.txt |
| golang.org/x/sys | BSD-3-Clause | licenses/x-sys-LICENSE.txt |

应用不使用 WireGuard 隧道协议。旧 Python 代理依赖的 gVisor 未进入当前发行目标。Go 编译器运行库的许可证见 licenses/go-LICENSE.txt。上述许可证随 app 的 Contents/Resources 一同分发。

本项目采用 MIT 许可证，见根目录 LICENSE。第三方组件仍遵循各自许可证。
