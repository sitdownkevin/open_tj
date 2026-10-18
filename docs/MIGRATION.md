# 从 prototype 提取

新仓库独立于上级目录；原 prototype/ 和 analysis/ 保持原样。

| 原位置 | 新位置 |
| --- | --- |
| prototype/gui/MotionVPN.swift | apps/macos/OpenTJ.swift |
| prototype/netstack/cmd/motionvpn | backend/cmd/motionvpn |
| prototype/netstack/client | backend/internal/client |
| prototype/netstack/go.mod、go.sum | backend/，经 go mod tidy 清理 |
| prototype/gui/build.sh、test.sh | scripts/ 下重建 |

Go module 改为 open-tj.local/backend，仅用于仓库内部导入，不依赖公开托管地址。Swift 及协议核心行为未改动。未引入 Python 历史客户端、旧 netstack 代理入口、逆向代码、原厂 DMG、历史网络快照、日志、测试结果 JSON、编译缓存或旧 app 包。

旧 README 混合多阶段结论，不作为新版发布说明。此前真实网关测试记录仅属于旧原型证据；新仓库测试及打包成功不等价于所有平台和管理员路径已验收。
