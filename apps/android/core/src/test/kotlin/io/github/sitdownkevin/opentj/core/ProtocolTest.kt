package io.github.sitdownkevin.opentj.core

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun matchesGoProtocolFixtures() {
        val fixture = Json.parseToJsonElement(File(System.getProperty("protocolFixtures")).readText()).jsonObject
        fixture.getValue("control_packets").jsonArray.forEach {
            val item = it.jsonObject
            val packet = Protocol.control(item.getValue("command").jsonPrimitive.int, item.getValue("parameter").jsonPrimitive.long)
            assertEquals(item.getValue("hex").jsonPrimitive.content, packet.joinToString("") { byte -> "%02x".format(byte) })
        }
        fixture.getValue("ipv4_values").jsonArray.forEach {
            val item = it.jsonObject
            assertEquals(item.getValue("address").jsonPrimitive.content, Protocol.ipv4(item["value"]))
        }
        val config = Protocol.tunnelConfig(fixture.getValue("common").jsonObject, fixture.getValue("network").jsonObject)
        assertEquals(TunnelConfig("10.0.0.2", listOf("10.0.0.1", "10.0.0.53"), 30), config)
    }

    @Test fun packetSurvivesFragmentedReadsAndPreservesNextPacket() {
        val packet = testPacket(1280)
        val stream = object : FilterInputStream(ByteArrayInputStream(packet + Protocol.control(4))) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, 3))
        }
        assertArrayEquals(packet, Protocol.readPacket(stream))
        assertEquals(4, Protocol.controlCommand(Protocol.readPacket(stream)))
    }

    @Test fun rejectsInvalidPacketLengthsAndVersions() {
        for (bad in listOf(
            testPacket(20).apply { this[0] = 0x65 },
            testPacket(20).apply { this[0] = 0x4f },
            testPacket(20).apply { this[3] = 19 },
        )) assertThrows(ProtocolException::class.java) { Protocol.readPacket(bad.inputStream()) }
        assertThrows(java.io.EOFException::class.java) { Protocol.readPacket(testPacket(64).copyOf(30).inputStream()) }
        assertThrows(ProtocolException::class.java) { Protocol.validatePacket(testPacket(20) + byteArrayOf(1)) }
        assertThrows(ProtocolException::class.java) { Protocol.validatePacket(byteArrayOf()) }
        assertEquals(true, Protocol.isIpv6(byteArrayOf(0x60.toByte())))
    }

    @Test fun boundedConfigurationAndStrictAddresses() {
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).put(0, 0x51).putInt(12, 1024 * 1024 + 1).array()
        assertThrows(ProtocolException::class.java) { Protocol.readConfig(header.inputStream(), 0x51) }
        assertThrows(ProtocolException::class.java) { Protocol.readConfig(Protocol.configRequest(0x55, "{}".toByteArray()).inputStream(), 0x51) }
        for (value in listOf(JsonPrimitive(-1), JsonPrimitive(0x1ffffffffL), JsonPrimitive("1.2.3.999"), JsonPrimitive("example.com"))) {
            assertThrows(ProtocolException::class.java) { Protocol.ipv4(value) }
        }
        val badDns = Json.parseToJsonElement("""{"client_ipv4":"10.0.0.2","dns_servers":[]}""").jsonObject
        assertThrows(ProtocolException::class.java) { Protocol.tunnelConfig(Json.parseToJsonElement("{}").jsonObject, badDns) }
    }
}

internal fun testPacket(length: Int = 1280): ByteArray =
    ByteArray(length).apply {
        this[0] = 0x45
        this[9] = 6
        this[2] = (length shr 8).toByte()
        this[3] = length.toByte()
        for (index in 20 until length) this[index] = (index % 251).toByte()
    }
