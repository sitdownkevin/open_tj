# 安全反馈

请勿在公开 issue 中附带账号、密码、Cookie、钥匙串内容或完整未脱敏日志。涉及认证、管理员 helper 或网络恢复的问题，应通过维护者的私密渠道报告；正式公开前维护者需补充具体联系地址。

应用验证 TLS 证书及主机名。管理员 helper 修改系统网络，退出时进行恢复核对。当前 IPv6 保护不等价于防火墙级 kill switch，也不承诺所有网络变化期间零泄漏。

Android 预览版使用系统 VpnService，无管理员 helper；密码通过 Android Keystore
密钥加密，账号存储不参与备份。日志仅保留在进程内存中。连接期间阻止 IPv6，
但断线重连会释放 VPN 接口，不提供跨重连期间的 kill switch。
GitHub Release APK 使用固定 release 密钥签名，签名指纹记录于
apps/android/release-certificate.sha256。私钥与密码仅保存在维护者的私密备份和
GitHub Actions Secrets；PR 构建不接触签名密钥。APK 发布前校验指纹、SHA-256
和 Android 8/16 安装测试。make android 生成的本地开发包仍使用 debug 签名，
与 Release 签名不同，不能直接互相覆盖。
