# 分发指南

## macOS 本地验证包

在项目根目录运行：

```sh
make test
make package
# 单架构：ARCHS=arm64 make package
```

生成的 ZIP 包含 Apple Silicon 与 Intel 可执行文件，用户无需 Go/Python。包默认 ad-hoc 签名，适合开发验证，Gatekeeper 可能阻止来自互联网的下载；普通用户的正式渠道应使用下述签名公证流程。不要要求用户关闭系统安全保护。

## macOS 正式分发

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

## macOS Git 与发布版本

远程仓库为 https://github.com/sitdownkevin/open_tj，默认 main 分支。推送 main 或手动触发 Actions 会生成可下载的构建产物。修改 VERSION 并提交后，推送对应标签（例如 git tag v0.2.0，再 git push origin v0.2.0），Actions 会测试、构建并创建含 ZIP 与 SHA-256 的 Release。标签必须与 VERSION 一致。

GitHub Actions 使用 macos-26 runner，在 push/PR 时测试并生成 ad-hoc 双架构包；版本标签触发自动 Release，无需分发证书。普通构建产物保留 30 天；Release 附件可持续下载。公开 Release 附件无需登录即可下载。正式 Developer ID 签名与 Apple 公证仍使用上文的 make release 流程。

## Android 签名准备

Android 使用独立的 `apps/android/VERSION` 和 `android-v*` 标签，不复用 macOS 标签。
仓库的 Android 工作流在 Ubuntu 上构建，用户直接下载 Release 中的 APK 即可安装，
无需访问 Actions 或自行编译。

首次发布配置以下仓库 Actions Secrets（Settings → Secrets and variables → Actions）：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 固定发布 keystore 文件的 Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 发布密钥 alias |
| `ANDROID_KEY_PASSWORD` | 发布密钥密码 |

私钥和密码保存在维护者自己的安全位置，不能提交 Git，也不能成为 Actions artifact。
本项目初始化的私钥与密码配置保存在维护者本机 `~/.config/open-tj/android-release/`；
需要将该目录另行加密备份。GitHub Secret 无法读回原值，不应作为唯一备份。
公开签名指纹维护在 `apps/android/release-certificate.sha256`；每次签名强制校验，
避免因使用新生成的密钥而破坏覆盖升级。工作流不会自动创建或回退到 debug 密钥。

`make android` 使用本机 debug key；`make android-release` 生成未签名的 R8 优化包；
`make android-sign` 从环境变量读取签名配置，输出最终包到 `dist/android/release/`。
脚本先校验签名与指纹，再写入发布目录。维护者可运行：

```sh
base64 < /path/to/opentj-release.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS
gh secret set ANDROID_KEY_PASSWORD
```

后三个命令交互读取输入，不要把密码直接写在命令行、配置示例或 issue 中。

## Android 版本发布

1. 更新 `apps/android/VERSION`、`CHANGELOG.md`、`docs/ANDROID_RELEASE_NOTES.md`，
   并同步 README 中 APK 与 Release 的版本链接。minor/patch 必须小于 100，
   versionCode 使用 `major * 10000 + minor * 100 + patch`，必须比旧版本大。
2. 提交并推送 `main`，确认 Android CI 测试、lint 和 R8 构建通过。
3. 创建与版本一致的新标签并推送：

```sh
git tag -a android-v0.2.1 -m "Open TJ Android 0.2.1"
git push origin android-v0.2.1
```

4. Actions 使用同一构建产物完成签名，在 Android API 26 和 36 的独立模拟器验证
   安装、冷启动和覆盖安装，全部通过才创建公开 Android prerelease。发布包含
   `OpenTJ-<VERSION>-android.apk`、同名 `.apk.sha256` 和 `release-certificate.sha256`。
5. 从公开下载链接取回 APK，核对 SHA-256、签名与安装结果后完成发布验收记录。

签名 job 才能访问密钥；PR、普通分支构建、模拟器和发布 job 均不接触私钥。
Actions 使用固定 commit SHA。已存在的 Release 不自动覆盖，后续修复须增加版本号；
首次失败若留下未公开的 draft，维护者应先检查 draft 再决定是否删除并重跑。
Android 目前标记为预览版，工作流保持 macOS 的 Latest Release 不变。

当前维护者手机上的早期 debug 包与发布签名不同，不能直接覆盖安装。签名测试只能
在专用模拟器或测试设备上运行；不能为安装 Release 而自动卸载或清理用户账号数据。

## 许可与用户数据

本应用包包含实际运行依赖的许可证，见 THIRD_PARTY_NOTICES.md。本项目采用 MIT 许可证，见根目录 LICENSE；第三方依赖遵循各自许可证。原厂安装器、反编译材料和真实用户数据不在此仓库中。

macOS 用户凭据保存在钥匙串，日志位于 ~/Library/Logs/Open TJ。日志可能包含接口、域名、IP 等环境信息，提交 issue 前请脱敏。卸载前先退出客户端，再删除 app；日志、偏好、后端缓存和钥匙串项目不会自动删除。
Android 记住的密码由 Keystore 加密且不参与备份，日志只在内存中保留；卸载会丢失
本地配置与凭据，因此应优先使用相同签名 APK 覆盖升级。

参考：[Apple 公证工作流](https://developer.apple.com/documentation/security/customizing-the-notarization-workflow)、[Developer ID 分发](https://developer.apple.com/developer-id/)。
