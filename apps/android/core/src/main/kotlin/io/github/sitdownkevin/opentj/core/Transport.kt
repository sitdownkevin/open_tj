package io.github.sitdownkevin.opentj.core

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

interface WireConnection : Closeable {
    val input: InputStream
    val output: OutputStream
    fun readTimeout(milliseconds: Int)
}

fun interface Connector {
    fun open(scope: ResourceScope, timeoutMs: Int): WireConnection
}

class TlsConnector(
    private val host: String = GatewayClient.HOST,
    private val port: Int = 443,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val prepareSocket: (Socket) -> Unit = {},
    private val factory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) : Connector {
    override fun open(scope: ResourceScope, timeoutMs: Int): WireConnection {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        fun remaining(): Int {
            val ms = (deadline - System.nanoTime()) / 1_000_000
            if (ms <= 0) throw SocketTimeoutException("连接建立超时")
            return ms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val addresses = resolve(host).filterIsInstance<Inet4Address>()
        scope.checkActive()
        if (addresses.isEmpty()) throw java.net.UnknownHostException("VPN 网关没有 IPv4 地址")
        var lastError: java.io.IOException? = null
        for (address in addresses) {
            val plain = scope.own(Socket())
            try {
                prepareSocket(plain)
                scope.checkActive()
                plain.connect(InetSocketAddress(address, port), remaining())
                val tls = scope.own(factory.createSocket(plain, host, port, true) as SSLSocket)
                try {
                    tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                    // Retain the desktop gateway's legacy TLS 1.2 compatibility suite.
                    val compatible = "TLS_RSA_WITH_AES_128_CBC_SHA"
                    if (compatible in tls.supportedCipherSuites) {
                        tls.enabledCipherSuites = (tls.enabledCipherSuites.toList() + compatible).distinct().toTypedArray()
                    }
                    tls.soTimeout = remaining()
                    tls.startHandshake()
                    scope.checkActive()
                    return object : WireConnection {
                        override val input = BufferedInputStream(tls.inputStream, 65536)
                        override val output: OutputStream = tls.outputStream
                        override fun readTimeout(milliseconds: Int) { tls.soTimeout = milliseconds }
                        override fun close() {
                            runCatching { tls.close() }
                            runCatching { plain.close() }
                            scope.forget(tls)
                            scope.forget(plain)
                        }
                    }
                } catch (error: Throwable) {
                    runCatching { tls.close() }
                    scope.forget(tls)
                    throw error
                }
            } catch (error: java.io.IOException) {
                lastError = error
                runCatching { plain.close() }
                scope.forget(plain)
                scope.checkActive()
                if (error is javax.net.ssl.SSLException) throw error
            } catch (error: Throwable) {
                runCatching { plain.close() }
                scope.forget(plain)
                throw error
            }
        }
        throw lastError ?: java.net.ConnectException("无法连接 VPN 网关")
    }
}
