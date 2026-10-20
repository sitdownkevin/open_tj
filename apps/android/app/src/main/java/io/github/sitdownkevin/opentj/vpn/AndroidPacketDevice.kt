package io.github.sitdownkevin.opentj.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import io.github.sitdownkevin.opentj.core.PacketDevice
import io.github.sitdownkevin.opentj.core.Protocol
import io.github.sitdownkevin.opentj.core.SessionCancelledException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidPacketDevice(private val descriptor: ParcelFileDescriptor) : PacketDevice {
    private val closed = AtomicBoolean()
    private val buffer = ByteArray(Protocol.MAX_PACKET)

    private fun ready(events: Int): Boolean {
        if (closed.get()) throw SessionCancelledException()
        val poll = StructPollfd().apply {
            fd = descriptor.fileDescriptor
            this.events = events.toShort()
        }
        val count = Os.poll(arrayOf(poll), 500)
        if (closed.get()) throw SessionCancelledException()
        if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
            throw IOException("VPN 接口已关闭")
        }
        return count > 0
    }

    override fun readPacket(): ByteArray? {
        try {
            if (!ready(OsConstants.POLLIN)) return null
            val count = Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size)
            if (count <= 0) throw IOException("VPN 接口已关闭")
            return buffer.copyOf(count)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) return null
            throw IOException("无法读取 VPN 接口", error)
        }
    }

    override fun writePacket(packet: ByteArray) {
        while (!closed.get()) {
            try {
                if (!ready(OsConstants.POLLOUT)) continue
                val count = Os.write(descriptor.fileDescriptor, packet, 0, packet.size)
                // A TUN write is one complete packet; retrying a suffix would corrupt it.
                if (count != packet.size) throw IOException("VPN 数据包写入不完整")
                return
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) {
                    throw IOException("无法写入 VPN 接口", error)
                }
            }
        }
        throw SessionCancelledException()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) descriptor.close()
    }
}
