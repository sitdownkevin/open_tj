package io.github.sitdownkevin.opentj.core

import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import org.junit.Assert.*
import org.junit.Test

class TlsVerificationTest {
    @Test fun verifiesBothCertificateTrustAndHostname() {
        val directory = Files.createTempDirectory("opentj-tls-test").toFile()
        try {
            val keystore = File(directory, "server.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val generation = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", keystore.absolutePath,
                "-storepass", "test-only", "-keypass", "test-only", "-noprompt",
            ).redirectErrorStream(true).start()
            val output = generation.inputStream.bufferedReader().readText()
            assertEquals(output, 0, generation.waitFor())
            val store = KeyStore.getInstance("PKCS12").apply {
                keystore.inputStream().use { load(it, "test-only".toCharArray()) }
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store, "test-only".toCharArray())
            }
            val serverContext = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val trusted = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            val server = serverContext.serverSocketFactory.createServerSocket(0, 3, InetAddress.getLoopbackAddress()) as SSLServerSocket
            server.use {
                val worker = Executors.newSingleThreadExecutor()
                val served = worker.submit {
                    repeat(3) {
                        (server.accept() as SSLSocket).use { socket ->
                            runCatching { socket.startHandshake() }
                        }
                    }
                }
                val resolve: (String) -> List<InetAddress> = { listOf(InetAddress.getByName("127.0.0.1")) }
                try {
                    ResourceScope().use { scope ->
                        assertThrows(SSLException::class.java) {
                            TlsConnector("localhost", server.localPort, resolve).open(scope, 3000)
                        }
                    }
                    ResourceScope().use { scope ->
                        assertThrows(SSLException::class.java) {
                            TlsConnector("wrong.invalid", server.localPort, resolve, factory = trusted.socketFactory).open(scope, 3000)
                        }
                    }
                    ResourceScope().use { scope ->
                        TlsConnector("localhost", server.localPort, resolve, factory = trusted.socketFactory).open(scope, 3000).close()
                    }
                    served.get(5, TimeUnit.SECONDS)
                } finally { worker.shutdownNow() }
            }
        } finally { directory.deleteRecursively() }
    }
}
