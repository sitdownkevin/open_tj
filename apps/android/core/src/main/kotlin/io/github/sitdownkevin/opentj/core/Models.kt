package io.github.sitdownkevin.opentj.core

import java.io.IOException
import java.io.InterruptedIOException
import javax.net.ssl.SSLException

class Credentials(val username: String, val password: String, val method: String = "LDAP3") {
    init {
        require(username.isNotBlank() && password.isNotEmpty()) { "请输入账号和密码" }
        require(method in METHODS) { "不支持的认证方式" }
    }

    override fun toString() = "Credentials(redacted)"

    companion object {
        val METHODS = listOf("LDAP3", "LDAP2", "LDAP", "default_method_localdb")
    }
}

data class TunnelConfig(val address: String, val dnsServers: List<String>, val keepaliveSeconds: Int)

data class TrafficSnapshot(
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val uploadBps: Double = 0.0,
    val downloadBps: Double = 0.0,
)

class AuthenticationException : IOException("账号验证失败，请检查账号、密码和认证方式")
class ProtocolException(message: String) : IOException(message)
class SessionCancelledException : InterruptedIOException("连接已取消")
class GatewayClosedException(val reason: Long) : IOException("网关结束了会话（原因码 $reason）")
class GatewayNetworkException : IOException("无法绑定当前底层网络，请切换 Wi-Fi 或移动网络")

object RetryPolicy {
    fun shouldRetry(error: Throwable): Boolean =
        error is IOException && error !is AuthenticationException &&
            error !is ProtocolException && error !is SSLException &&
            error !is SessionCancelledException

    fun delaySeconds(attempt: Int): Long = minOf(30L, 1L shl attempt.coerceIn(1, 5))

    fun message(error: Throwable): String = when (error) {
        is AuthenticationException -> error.message!!
        is ProtocolException -> error.message ?: "网关协议不兼容"
        is SSLException -> "服务器安全连接验证失败，请检查系统时间和网络"
        is GatewayClosedException -> error.message!!
        is GatewayNetworkException -> error.message!!
        is java.net.UnknownHostException -> "暂时无法解析 VPN 服务器"
        is java.net.SocketTimeoutException -> "服务器响应超时"
        is SessionCancelledException -> "连接已取消"
        else -> "网络连接中断"
    }
}
