package io.github.sitdownkevin.opentj.core

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class TunnelPumpTest {
    @Test fun forwardsPacketsBothWaysAndRespondsToControlMessages() {
        withSockets { local, remote ->
            val scope = ResourceScope()
            val device = FakePacketDevice()
            val pool = Executors.newSingleThreadExecutor()
            val done = pool.submit<Throwable> {
                try { TunnelPump().run(GatewaySession(local, TunnelConfig("10.0.0.2", listOf("10.0.0.1"), 30), ""), device, scope) }
                catch (error: Throwable) { error }
            }
            try {
                val packet = testPacket()
                repeat(64) {
                    if (it == 0) device.outgoing.put(ByteArray(40).apply { this[0] = 0x60.toByte() })
                    device.outgoing.put(packet)
                    assertArrayEquals(packet, Protocol.readPacket(remote.getInputStream()))
                    remote.getOutputStream().write(packet)
                    assertArrayEquals(packet, device.incoming.poll(2, TimeUnit.SECONDS))
                }
                remote.getOutputStream().write(Protocol.control(4))
                assertEquals(5, Protocol.controlCommand(Protocol.readPacket(remote.getInputStream())))
                remote.getOutputStream().write(Protocol.control(2, 3))
                val result = done.get(3, TimeUnit.SECONDS)
                assertTrue(result is GatewayClosedException)
                assertEquals(3L, (result as GatewayClosedException).reason)
                assertTrue(device.closed.get())
            } finally {
                scope.close()
                pool.shutdownNow()
            }
        }
    }

    @Test fun cancellationStopsAnIdleTunnel() {
        withSockets { local, _ ->
            val scope = ResourceScope()
            val pool = Executors.newSingleThreadExecutor()
            val device = FakePacketDevice()
            val started = java.util.concurrent.CountDownLatch(1)
            val done = pool.submit<Throwable> {
                started.countDown()
                try { TunnelPump().run(GatewaySession(local, TunnelConfig("10.0.0.2", listOf("10.0.0.1"), 30), ""), device, scope) }
                catch (error: Throwable) { error }
            }
            try {
                assertTrue(started.await(1, TimeUnit.SECONDS))
                scope.close()
                assertNotNull(done.get(3, TimeUnit.SECONDS))
            } finally { pool.shutdownNow() }
        }
    }

    private fun withSockets(block: (WireConnection, Socket) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { socket ->
                listener.accept().use { remote ->
                    remote.soTimeout = 2000
                    val local = object : WireConnection {
                        override val input = BufferedInputStream(socket.getInputStream())
                        override val output = socket.getOutputStream()
                        override fun readTimeout(milliseconds: Int) { socket.soTimeout = milliseconds }
                        override fun close() = socket.close()
                    }
                    block(local, remote)
                }
            }
        }
    }
}

private class FakePacketDevice : PacketDevice {
    val outgoing = ArrayBlockingQueue<ByteArray>(8)
    val incoming = ArrayBlockingQueue<ByteArray>(8)
    val closed = AtomicBoolean()
    override fun readPacket(): ByteArray? {
        if (closed.get()) throw SessionCancelledException()
        return outgoing.poll(50, TimeUnit.MILLISECONDS)
    }
    override fun writePacket(packet: ByteArray) { incoming.put(packet) }
    override fun close() { closed.set(true) }
}
