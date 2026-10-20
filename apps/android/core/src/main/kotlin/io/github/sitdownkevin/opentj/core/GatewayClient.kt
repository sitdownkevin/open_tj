package io.github.sitdownkevin.opentj.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

class GatewaySession internal constructor(
    val connection: WireConnection,
    val config: TunnelConfig,
    internal val cookie: String,
) : java.io.Closeable {
    override fun close() = connection.close()
    override fun toString() = "GatewaySession(redacted)"
}

internal data class HttpHead(val status: Int, val headers: Map<String, List<String>>) {
    fun first(name: String): String? = headers[name]?.firstOrNull()
}

class GatewayClient(private val connector: Connector) {
    fun login(credentials: Credentials, scope: ResourceScope): GatewaySession {
        val body = linkedMapOf(
            "method" to credentials.method, "uname" to credentials.username, "pwd" to credentials.password,
            "pwd1" to "", "pwd2" to "", "deviceid" to "", "device_name" to "OpenTJ Android",
            "hardwareid" to "",
        ).entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val response = connector.open(scope, 20_000).use { connection ->
            sendRequest(connection, "POST", "/login", "", body)
            readHead(connection.input)
        }
        val cookie = response.headers["set-cookie"].orEmpty()
            .map { it.substringBefore(';') }
            .filter { it.contains('=') && !it.contains('\r') && !it.contains('\n') }
            .joinToString("; ")
        if (response.status in 500..599 || response.status == 429) {
            throw java.io.IOException("网关暂时不可用")
        }
        val welcome = runCatching { URI(response.first("location") ?: "").path.endsWith("/welcome") }.getOrDefault(false)
        if (response.status !in 300..399 || !welcome) {
            if (cookie.isNotEmpty()) runCatching { logout(cookie, scope) }
            throw AuthenticationException()
        }
        try {
            val connection = connector.open(scope, 20_000)
            try {
                sendRequest(connection, "GET", "/vpntunnel", cookie, extra = mapOf(
                    "x-devtype" to "3", "clientid" to UUID.randomUUID().toString().replace("-", ""),
                    "cpuid" to "opentj-android", "hostname" to "OpenTJ",
                    "PAYLOAD-IP-VERSION" to "6",
                ))
                if (readHead(connection.input).status != 201) throw ProtocolException("网关未接受隧道请求")
                connection.output.write(Protocol.configRequest(0x50))
                connection.output.flush()
                val common = Protocol.readConfig(connection.input, 0x51)
                connection.output.write(Protocol.configRequest(0x54, ByteArray(32)))
                connection.output.flush()
                val network = Protocol.readConfig(connection.input, 0x55)
                val config = Protocol.tunnelConfig(common, network)
                connection.readTimeout(config.keepaliveSeconds * 4000 + 10_000)
                scope.checkActive()
                return GatewaySession(connection, config, cookie)
            } catch (error: Throwable) {
                connection.close()
                throw error
            }
        } catch (error: Throwable) {
            runCatching { logout(cookie, scope) }
            throw error
        }
    }

    fun logout(session: GatewaySession, scope: ResourceScope) = logout(session.cookie, scope)

    private fun logout(cookie: String, scope: ResourceScope) {
        if (cookie.isEmpty()) return
        connector.open(scope, 5_000).use { connection ->
            connection.readTimeout(5_000)
            sendRequest(connection, "GET", "/logout", cookie)
            readHead(connection.input)
        }
    }

    private fun sendRequest(
        connection: WireConnection,
        method: String,
        endpoint: String,
        cookie: String,
        body: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) {
        val data = body?.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("$method /prx/000/http/localhost$endpoint HTTP/1.1\r\n")
            append("Host: $HOST\r\nCookie: ANStandalone=true;$cookie\r\nappid: SSPVPN\r\n")
            extra.forEach { (name, value) -> append("$name: $value\r\n") }
            if (data != null) {
                append("Content-Type: application/x-www-form-urlencoded\r\n")
                append("Content-Length: ${data.size}\r\n")
            }
            append("\r\n")
        }
        connection.output.write(header.toByteArray(Charsets.US_ASCII))
        if (data != null) connection.output.write(data)
        connection.output.flush()
    }

    companion object {
        const val HOST = "vpn.tongji.cn"
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        internal fun readHead(input: InputStream): HttpHead {
            var total = 0
            fun line(): String {
                val buffer = ByteArrayOutputStream()
                while (true) {
                    val value = input.read()
                    if (value < 0) throw java.io.EOFException("网关响应不完整")
                    total++
                    if (total > 65536 || buffer.size() > 8192) throw ProtocolException("网关响应头过长")
                    if (value == 10) break
                    buffer.write(value)
                }
                val text = buffer.toString("ISO-8859-1")
                if (!text.endsWith('\r')) throw ProtocolException("网关响应头格式错误")
                return text.dropLast(1)
            }
            val match = Regex("""HTTP/1\.[01] (\d{3})(?: .*)?""").matchEntire(line())
                ?: throw ProtocolException("网关 HTTP 响应无效")
            val headers = mutableMapOf<String, MutableList<String>>()
            while (true) {
                val text = line()
                if (text.isEmpty()) break
                val colon = text.indexOf(':')
                if (colon <= 0) throw ProtocolException("网关 HTTP 响应头无效")
                val name = text.substring(0, colon).lowercase(Locale.ROOT)
                headers.getOrPut(name) { mutableListOf() }.add(text.substring(colon + 1).trim())
            }
            return HttpHead(match.groupValues[1].toInt(), headers)
        }
    }
}
