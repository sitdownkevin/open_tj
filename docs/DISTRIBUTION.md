# 分发指南

## 本地验证包

在项目根目录运行：

```sh
make test
make package
# 单架构：ARCHS=arm64 make package
```

生成的 ZIP 包含 Apple Silicon 与 Intel 可执行文件，用户无需 Go/Python。包默认 ad-hoc 签名，适合开发验证，Gatekeeper 可能阻止来自互联网的下载；普通用户的正式渠道应使用下述签名公证流程。不要要求用户关闭系统安全保护。

## 正式分发

1. 准备 Apple Developer Program 账号、Developer ID Application 证书和私钥，在发布机器的钥匙串中安装。需要可用的 notarytool、stapler（建议安装完整 Xcode）。
2. 使用 xcrun notarytool store-credentials 配置钥匙串 profile，例如 open-tj-notary。按交互提示提供开发者账号、Team ID 和 app-specific password；不要将凭据写入仓库。
3. 修改 VERSION，按需要设置递增 BUILD_NUMBER，执行下方命令。
4. 完成发布验收后，将 dist/ 内最终 ZIP 及 .sha256 上传到 GitHub Releases 或自己的下载页，填写系统要求、版本变化和已知问题。

```sh
export SIGN_IDENTITY="Developer ID Application: Your Name (TEAMID)"
export NOTARY_PROFILE="open-tj-notary"
export BUILD_NUMBER=1
make release
```

release 会先执行测试，构建双架构 app，从内到外使用 Hardened Runtime 和安全时间戳签名，提交 Apple 公证，staple 票据并验证 Gatekeeper，最后重新压缩并生成 SHA-256。任何一步失败都不应发布中间产物；最终以命令成功和验收完成为准。

Apple 公证是外部服务操作，仓库整理过程中没有提交。当前机器只有 Command Line Tools，没有验证正式证书、公证账号或正式发行链路。

## Git 与发布版本

这是独立本地 Git 仓库，默认 main 分支。尚未配置远程地址。设置自己的 Git 身份后提交，创建空远程仓库并设置 origin，然后 push；验证发布产物后再打版本标签。不要在上级 TJ-VPN 目录整体 git add。

GitHub Actions 在 push/PR 时测试并生成 ad-hoc 双架构预览包，不自动发布，不需要分发证书。CI 需要能下载 go.mod 指定的工具链，且 runner SDK 支持 SwiftUI 源码所用 API；首次远程运行仍需核验。

## 许可与用户数据

本应用包包含实际运行依赖的许可证，见 THIRD_PARTY_NOTICES.md。维护者仍需选择项目自身的源码许可证，明确名称与图标使用权限，以及与学校/原厂的关系说明。原厂安装器、反编译材料和真实用户数据不在此仓库中。

用户凭据保存在钥匙串，日志位于 ~/Library/Logs/Open TJ。日志可能包含接口、域名、IP 等环境信息，提交 issue 前请脱敏。卸载前先退出客户端，再删除 app；日志、偏好、后端缓存和钥匙串项目不会自动删除。

参考：[Apple 公证工作流](https://developer.apple.com/documentation/security/customizing-the-notarization-workflow)、[Developer ID 分发](https://developer.apple.com/developer-id/)。
