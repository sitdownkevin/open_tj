package io.github.sitdownkevin.opentj.core

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class GatewayClientTest {
    @Test fun loginConfigurationBufferedPacketAndLogoutMatchDesktopProtocol() {
        val login = FakeConnection("HTTP/1.1 302 Found\r\nLocation: /prx/000/http/localhost/welcome\r\nSet-Cookie: ANsession=test; HttpOnly\r\n\r\n".toByteArray())
        val tunnel = FakeConnection(
            "HTTP/1.1 201 Created\r\n\r\n".toByteArray() +
                Protocol.configRequest(0x51, """{"keepalive_interval":30}""".toByteArray()) +
                Protocol.configRequest(0x55, """{"client_ipv4":33554442,"dns_servers":[{"ipv4":[16777226]}]}""".toByteArray()) +
                testPacket(),
        )
        val logout = FakeConnection("HTTP/1.1 302 Found\r\n\r\n".toByteArray())
        val queue = ArrayDeque(listOf(login, tunnel, logout))
        val client = GatewayClient(Connector { _, _ -> queue.removeFirst() })
        ResourceScope().use { scope ->
            val session = client.login(Credentials("user", "p &+密码"), scope)
            assertEquals(TunnelConfig("10.0.0.2", listOf("10.0.0.1"), 30), session.config)
            assertTrue(login.closed)
            val submitted = login.output.toString("UTF-8")
            assertTrue(submitted.contains("pwd=p+%26%2B%E5%AF%86%E7%A0%81"))
            assertTrue(submitted.contains("method=LDAP3"))
            val sent = tunnel.output.toByteArray()
            val head = sent.toString(Charsets.ISO_8859_1).substringBefore("\r\n\r\n")
            assertTrue(head.contains("Cookie: ANStandalone=true;ANsession=test"))
            val offset = head.toByteArray(Charsets.ISO_8859_1).size + 4
            assertArrayEquals(Protocol.configRequest(0x50) + Protocol.configRequest(0x54, ByteArray(32)), sent.copyOfRange(offset, sent.size))
            assertArrayEquals(testPacket(), Protocol.readPacket(session.connection.input))
            session.close()
            client.logout(session, scope)
            assertTrue(logout.output.toString("UTF-8").contains("/logout"))
            assertTrue(queue.isEmpty())
        }
    }

    @Test fun rejectedCredentialsAreNotRetriedAndCookieIsLoggedOut() {
        val queue = ArrayDeque(listOf(
            FakeConnection("HTTP/1.1 200 OK\r\nSet-Cookie: ANsession=test\r\n\r\n".toByteArray()),
            FakeConnection("HTTP/1.1 200 OK\r\n\r\n".toByteArray()),
        ))
        var count = 0
        val client = GatewayClient(Connector { _, _ -> count++; queue.removeFirst() })
        ResourceScope().use { scope ->
            assertThrows(AuthenticationException::class.java) { client.login(Credentials("u", "p"), scope) }
        }
        assertEquals(2, count)
        assertFalse(RetryPolicy.shouldRetry(AuthenticationException()))
    }

    @Test fun malformedHttpHeadersAreBounded() {
        assertThrows(ProtocolException::class.java) {
            GatewayClient.readHead(("HTTP/1.1 201 Created\r\nX: " + "a".repeat(9000)).byteInputStream())
        }
        assertThrows(ProtocolException::class.java) { GatewayClient.readHead("HTTP/1.1 201 Created\n\n".byteInputStream()) }
        assertThrows(ProtocolException::class.java) { GatewayClient.readHead("not HTTP\r\n\r\n".byteInputStream()) }
    }

    @Test fun gatewayServiceFailureIsRetryableWithoutResubmittingInLogin() {
        var count = 0
        val client = GatewayClient(Connector { _, _ ->
            count++
            FakeConnection("HTTP/1.1 503 Service Unavailable\r\n\r\n".toByteArray())
        })
        ResourceScope().use { scope ->
            val error = assertThrows(java.io.IOException::class.java) {
                client.login(Credentials("u", "p"), scope)
            }
            assertTrue(RetryPolicy.shouldRetry(error))
        }
        assertEquals(1, count)
    }

    @Test fun transientErrorsRetryButPermanentErrorsDoNotExposeSensitiveMessages() {
        assertTrue(RetryPolicy.shouldRetry(java.net.UnknownHostException("sensitive")))
        assertTrue(RetryPolicy.shouldRetry(java.net.SocketTimeoutException("sensitive")))
        assertFalse(RetryPolicy.shouldRetry(SessionCancelledException()))
        assertFalse(RetryPolicy.shouldRetry(javax.net.ssl.SSLHandshakeException("sensitive")))
        assertFalse(RetryPolicy.shouldRetry(ProtocolException("bad config")))
        assertEquals(listOf(2L, 4L, 8L, 16L, 30L, 30L), (1..6).map(RetryPolicy::delaySeconds))
        assertFalse(RetryPolicy.message(java.io.IOException("https://example.invalid/?token=secret")).contains("secret"))
        assertEquals("无法绑定当前底层网络，请切换 Wi-Fi 或移动网络", RetryPolicy.message(GatewayNetworkException()))
        assertFalse(Credentials("user", "private-password").toString().contains("private-password"))
    }
}

internal class FakeConnection(response: ByteArray) : WireConnection {
    override val input = response.inputStream()
    override val output = ByteArrayOutputStream()
    var closed = false
    override fun readTimeout(milliseconds: Int) = Unit
    override fun close() { closed = true }
}
