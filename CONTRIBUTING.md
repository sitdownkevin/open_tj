# 开发约定

从项目根目录运行 make test；构建使用 make build，预览分发使用 make package。Go 修改使用 gofmt，测试与对应 package 放在一起。Swift 的 --self-test 断言必须在 -Onone 下运行。

网络事务修改应覆盖部分失败、回滚及取消路径；不要以 mock 测试替代真实网络验收。不要提交凭据、日志、个人网络快照、证书、私钥或构建产物。

Android 代码位于 apps/android，协议模块 core 不依赖 Android；共用协议样例位于 testdata。
运行 make android-test 验证单测和 lint，make android-release 验证 R8 构建；
输出均在被 Git 忽略的 dist/android。不要把 SDK、local.properties、签名密钥或 APK 加入 Git。

macOS 版本维护于根目录 VERSION，标签使用 v*；Android 版本维护于 apps/android/VERSION，
标签使用 android-v*。Android minor/patch 必须小于 100，versionCode 按
major * 10000 + minor * 100 + patch 生成，发布新版本必须递增。
更新 Android 下载版本时同步修改 README 下载链接和 docs/ANDROID_RELEASE_NOTES.md。
重要用户变化记录到 CHANGELOG.md，提交信息描述具体变更。
主分支启用 PR 检查的远程保护规则由仓库维护者配置。
