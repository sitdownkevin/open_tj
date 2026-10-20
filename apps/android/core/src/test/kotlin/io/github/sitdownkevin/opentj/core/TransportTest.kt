package io.github.sitdownkevin.opentj.core

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class TransportTest {
    @Test fun stalledTlsHandshakeTimesOutAndSocketIsPreparedBeforeConnect() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = Executors.newSingleThreadExecutor()
            val accepted = CountDownLatch(1)
            val future = worker.submit {
                server.accept().use { socket ->
                    accepted.countDown()
                    while (socket.getInputStream().read() != -1) { /* deliberately no TLS reply */ }
                }
            }
            val prepared = AtomicBoolean()
            val connector = TlsConnector(
                host = "localhost", port = server.localPort,
                resolve = { listOf(InetAddress.getByName("127.0.0.1")) },
                prepareSocket = { socket -> assertFalse(socket.isConnected); prepared.set(true) },
            )
            try {
                ResourceScope().use { scope ->
                    assertThrows(SocketTimeoutException::class.java) { connector.open(scope, 500) }
                }
                assertTrue(prepared.get())
                assertTrue(accepted.await(1, TimeUnit.SECONDS))
                future.get(2, TimeUnit.SECONDS)
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun cancellationClosesExistingAndFutureResourcesExactlyOnce() {
        val scope = ResourceScope()
        var closed = 0
        scope.own(Closeable { closed++ })
        scope.close()
        scope.close()
        assertEquals(1, closed)
        assertThrows(SessionCancelledException::class.java) { scope.own(Closeable { closed++ }) }
        assertEquals(2, closed)
    }
}
