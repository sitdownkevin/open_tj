# 开发约定

从项目根目录运行 make test；构建使用 make build，预览分发使用 make package。Go 修改使用 gofmt，测试与对应 package 放在一起。Swift 的 --self-test 断言必须在 -Onone 下运行。

网络事务修改应覆盖部分失败、回滚及取消路径；不要以 mock 测试替代真实网络验收。不要提交凭据、日志、个人网络快照、证书、私钥或构建产物。

版本维护于 VERSION；重要用户变化记录到 CHANGELOG.md。提交信息描述具体变更。主分支启用 PR 检查的远程保护规则由仓库维护者配置。
