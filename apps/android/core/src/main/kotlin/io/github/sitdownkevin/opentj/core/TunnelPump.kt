package io.github.sitdownkevin.opentj.core

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

interface PacketDevice : Closeable {
    /** Return null after a bounded idle wait so cancellation can be observed. */
    fun readPacket(): ByteArray?
    fun writePacket(packet: ByteArray)
}

class TunnelPump {
    fun run(
        session: GatewaySession,
        device: PacketDevice,
        scope: ResourceScope,
        onTraffic: (TrafficSnapshot) -> Unit = {},
    ): Nothing {
        val outbound = ArrayBlockingQueue<ByteArray>(256)
        val stopped = CompletableFuture<Throwable>()
        val workers = Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "OpenTJ-pump").apply { isDaemon = true }
        }
        val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "OpenTJ-heartbeat").apply { isDaemon = true }
        }
        val upload = AtomicLong()
        val download = AtomicLong()
        val writeStarted = AtomicLong()
        val cancelHook = scope.own(Closeable { stopped.complete(SessionCancelledException()) })
        scope.own(device)
        scope.own(session)
        fun worker(block: () -> Unit) {
            workers.execute {
                try { block() } catch (error: Throwable) { stopped.complete(error) }
            }
        }
        fun enqueue(packet: ByteArray) {
            while (!outbound.offer(packet, 250, TimeUnit.MILLISECONDS)) scope.checkActive()
        }
        var previousUpload = 0L
        var previousDownload = 0L
        var previousTime = System.nanoTime()
        var previousPing = previousTime
        val intervalNanos = session.config.keepaliveSeconds * 1_000_000_000L
        try {
            worker {
                while (true) {
                    scope.checkActive()
                    val packet = outbound.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    writeStarted.set(System.nanoTime())
                    session.connection.output.write(packet)
                    session.connection.output.flush()
                    writeStarted.set(0)
                    if (packet[9].toInt() and 255 != 255) upload.addAndGet(packet.size.toLong())
                }
            }
            worker {
                while (true) {
                    scope.checkActive()
                    val packet = device.readPacket() ?: continue
                    if (Protocol.isIpv6(packet)) continue
                    Protocol.validatePacket(packet)
                    enqueue(packet)
                }
            }
            worker {
                var lastReply = 0L
                while (true) {
                    scope.checkActive()
                    val packet = Protocol.readPacket(session.connection.input)
                    if (packet[9].toInt() and 255 == 255) {
                        when (Protocol.controlCommand(packet)) {
                            1 -> {
                                val now = System.nanoTime()
                                if (now - lastReply >= intervalNanos / 2) {
                                    lastReply = now
                                    enqueue(Protocol.control(1))
                                }
                            }
                            2 -> throw GatewayClosedException(Protocol.controlParameter(packet))
                            4 -> enqueue(Protocol.control(5))
                        }
                    } else {
                        device.writePacket(packet)
                        download.addAndGet(packet.size.toLong())
                    }
                }
            }
            timer.scheduleAtFixedRate({
                try {
                    scope.checkActive()
                    val now = System.nanoTime()
                    val writing = writeStarted.get()
                    if (writing != 0L && now - writing > 30_000_000_000L) {
                        throw java.net.SocketTimeoutException("发送数据超时")
                    }
                    if (now - previousPing >= intervalNanos) {
                        if (!outbound.offer(Protocol.control(1))) throw IOException("发送队列阻塞")
                        previousPing = now
                    }
                    val up = upload.get()
                    val down = download.get()
                    val seconds = (now - previousTime) / 1_000_000_000.0
                    onTraffic(TrafficSnapshot(up, down, (up - previousUpload) / seconds, (down - previousDownload) / seconds))
                    previousUpload = up
                    previousDownload = down
                    previousTime = now
                } catch (error: Throwable) {
                    stopped.complete(error)
                }
            }, 1, 1, TimeUnit.SECONDS)
            throw stopped.get()
        } finally {
            scope.forget(cancelHook)
            scope.close()
            timer.shutdownNow()
            workers.shutdownNow()
            workers.awaitTermination(2, TimeUnit.SECONDS)
            timer.awaitTermination(2, TimeUnit.SECONDS)
            onTraffic(TrafficSnapshot(upload.get(), download.get()))
        }
    }
}
