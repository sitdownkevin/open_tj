# Open TJ

<img src="assets/logo.png" alt="Open TJ" width="128">

简洁的 macOS VPN 客户端，支持菜单栏、流量统计和自动重连。

## 下载与使用

1. 在 [Releases](https://github.com/sitdownkevin/open_tj/releases/latest) 下载 `OpenTJ-*.zip`，支持 Apple Silicon 和 Intel，要求 macOS 14 或更新版本。
2. 解压，将 **Open TJ.app** 拖入「应用程序」。
3. 打开应用，输入账号与密码，点击连接并完成系统授权。

当前构建尚未经 Apple 公证。如被 macOS 拦截，尝试打开后，到「系统设置 → 隐私与安全性」选择「仍要打开」。私有仓库下载需要先登录有访问权限的 GitHub 账号。

关闭窗口后应用继续在菜单栏运行；升级前请先退出旧版本。

## 构建

```sh
make test
make build
make package
```

推送 main 自动测试并构建，可在 [Actions](https://github.com/sitdownkevin/open_tj/actions) 下载产物；推送与 `VERSION` 一致的 `v*` 标签自动发布 Release。更多信息见 [分发指南](docs/DISTRIBUTION.md)。
