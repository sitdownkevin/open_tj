# 安全反馈

请勿在公开 issue 中附带账号、密码、Cookie、钥匙串内容或完整未脱敏日志。涉及认证、管理员 helper 或网络恢复的问题，应通过维护者的私密渠道报告；正式公开前维护者需补充具体联系地址。

应用验证 TLS 证书及主机名。管理员 helper 修改系统网络，退出时进行恢复核对。当前 IPv6 保护不等价于防火墙级 kill switch，也不承诺所有网络变化期间零泄漏。

Android 预览版使用系统 VpnService，无管理员 helper；密码通过 Android Keystore
密钥加密，账号存储不参与备份。日志仅保留在进程内存中。连接期间阻止 IPv6，
但断线重连会释放 VPN 接口，不提供跨重连期间的 kill switch。
开发预览 APK 使用 debug 签名，正式分发应使用固定的 release 签名密钥。
