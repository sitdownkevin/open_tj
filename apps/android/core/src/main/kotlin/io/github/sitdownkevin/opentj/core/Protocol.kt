package io.github.sitdownkevin.opentj.core

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object Protocol {
    const val MTU = 1280
    const val MAX_PACKET = 65535
    private const val MAX_CONFIG = 1024 * 1024

    fun readExactly(input: InputStream, size: Int): ByteArray {
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(data, offset, size - offset)
            if (count < 0) throw EOFException("网关已关闭连接")
            if (count == 0) throw IOExceptionNoProgress()
            offset += count
        }
        return data
    }

    fun readPacket(input: InputStream): ByteArray {
        val header = readExactly(input, 20)
        val headerLength = (header[0].toInt() and 15) * 4
        val length = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        if ((header[0].toInt() and 240) != 64 || headerLength < 20 || length < headerLength) {
            throw ProtocolException("网关返回了无效的 IPv4 数据包")
        }
        return header + readExactly(input, length - 20)
    }

    fun isIpv6(packet: ByteArray): Boolean =
        packet.isNotEmpty() && ((packet[0].toInt() ushr 4) and 15) == 6

    fun validatePacket(packet: ByteArray) {
        val version = packet.getOrNull(0)?.let { (it.toInt() ushr 4) and 15 } ?: -1
        val headerLength = packet.getOrNull(0)?.let { (it.toInt() and 15) * 4 } ?: 0
        val length = if (packet.size >= 4) {
            ((packet[2].toInt() and 255) shl 8) or (packet[3].toInt() and 255)
        } else {
            -1
        }
        if (packet.size !in 20..MAX_PACKET ||
            version != 4 ||
            headerLength !in 20..packet.size ||
            length != packet.size
        ) throw ProtocolException(
            "隧道收到无效的 IPv4 数据包（size=${packet.size}, " +
                "version=$version, ihl=$headerLength, length=$length）",
        )
    }

    fun control(command: Int, parameter: Long = 0): ByteArray =
        ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN).apply {
            put(0, 0x45.toByte())
            putShort(2, 20)
            put(9, 255.toByte())
            putShort(12, command.toShort())
            putInt(16, parameter.toInt())
        }.array()

    fun controlCommand(packet: ByteArray): Int =
        ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN).getShort(12).toInt() and 65535

    fun controlParameter(packet: ByteArray): Long =
        ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN).getInt(16).toLong() and 0xffffffffL

    fun configRequest(kind: Int, payload: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(16 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0, kind.toByte())
            putInt(12, payload.size)
            position(16)
            put(payload)
        }.array()

    fun readConfig(input: InputStream, expectedKind: Int): JsonObject {
        val header = readExactly(input, 16)
        val size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
        if ((header[0].toInt() and 255) != expectedKind || size !in 1..MAX_CONFIG) {
            throw ProtocolException("网关配置头无效")
        }
        val text = readExactly(input, size).toString(Charsets.UTF_8).trimEnd('\u0000')
        return try {
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw ProtocolException("网关配置格式无效")
        } catch (_: IllegalArgumentException) {
            throw ProtocolException("网关配置不是有效 JSON")
        }
    }

    fun ipv4(value: JsonElement?): String {
        val primitive = value as? JsonPrimitive ?: throw ProtocolException("缺少 IPv4 地址")
        if (primitive.isString) {
            val parts = primitive.content.split('.')
            if (parts.size != 4 || parts.any { p -> p.isEmpty() || p.any { !it.isDigit() } || p.toIntOrNull() !in 0..255 }) {
                throw ProtocolException("无效的 IPv4 地址")
            }
            return parts.joinToString(".") { it.toInt().toString() }
        }
        val number = primitive.longOrNull ?: throw ProtocolException("无效的 IPv4 地址")
        if (number !in 0..0xffffffffL) throw ProtocolException("IPv4 地址超出范围")
        return (0..3).joinToString(".") { ((number shr (it * 8)) and 255).toString() }
    }

    fun tunnelConfig(common: JsonObject, network: JsonObject): TunnelConfig {
        val address = ipv4(network["client_ipv4"])
        val groups = network["dns_servers"] as? JsonArray ?: throw ProtocolException("缺少 VPN DNS")
        val servers = groups.flatMap { item ->
            val values = (item as? JsonObject)?.get("ipv4") as? JsonArray
                ?: throw ProtocolException("VPN DNS 格式无效")
            values.map(::ipv4)
        }.distinct().take(4)
        if (servers.isEmpty()) throw ProtocolException("网关未提供 IPv4 DNS")
        for (ip in servers + address) {
            if (ip.substringBefore('.').toInt() !in 1..223 || ip == "255.255.255.255") {
                throw ProtocolException("网关提供了不可用的 IPv4 地址")
            }
        }
        val interval = (common["keepalive_interval"] as? JsonPrimitive)?.intOrNull ?: 30
        return TunnelConfig(address, servers, if (interval <= 0) 30 else interval.coerceIn(5, 300))
    }

    private class IOExceptionNoProgress : java.io.IOException("读取网关数据未取得进展")
}
