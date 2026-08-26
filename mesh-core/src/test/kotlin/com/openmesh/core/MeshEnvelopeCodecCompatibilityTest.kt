package com.openmesh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.EOFException
import java.nio.ByteBuffer

class MeshEnvelopeCodecCompatibilityTest {

    @Test
    fun `v1 golden envelope remains byte exact`() {
        val expected = MeshEnvelope(
            protocolVersion = 1,
            packetId = "packet-v1-golden",
            sourceNodeId = "om1-00000000000000000000000000000000",
            destinationNodeId = "om1-11111111111111111111111111111111",
            createdAtMs = 1_700_000_000_000,
            expiresAtMs = 1_700_000_060_000,
            hopCount = 2,
            maxHops = 12,
            lastHopNodeId = "relay-v1",
            priority = PacketPriority.HIGH,
            contentType = "text/plain",
            payloadBase64 = "SGVsbG8=",
            signatureBase64 = "c2ln",
        )
        val golden = goldenEnvelope()

        assertArrayEquals(golden, MeshEnvelopeCodec.encode(expected))
        assertEquals(expected, MeshEnvelopeCodec.decode(golden))
    }

    @Test
    fun `v1 decoder rejects invalid magic`() {
        val malformed = goldenEnvelope().also { it[0] = 0 }

        assertThrows(IllegalArgumentException::class.java) {
            MeshEnvelopeCodec.decode(malformed)
        }
    }

    @Test
    fun `v1 decoder rejects truncated envelope`() {
        val truncated = goldenEnvelope().dropLast(1).toByteArray()

        assertThrows(EOFException::class.java) {
            MeshEnvelopeCodec.decode(truncated)
        }
    }

    @Test
    fun `v1 decoder rejects trailing data`() {
        val withTrailingByte = goldenEnvelope() + byteArrayOf(0)

        assertThrows(IllegalArgumentException::class.java) {
            MeshEnvelopeCodec.decode(withTrailingByte)
        }
    }

    @Test
    fun `v1 decoder rejects oversized declared string before allocation`() {
        val malformed = ByteBuffer.allocate(12)
            .putInt(0x4F4D5348)
            .putInt(MeshEnvelope.CURRENT_PROTOCOL_VERSION)
            .putInt(16 * 1024 * 1024 + 1)
            .array()

        assertThrows(IllegalArgumentException::class.java) {
            MeshEnvelopeCodec.decode(malformed)
        }
    }

    private fun goldenEnvelope(): ByteArray {
        val resource = checkNotNull(javaClass.getResource("/golden/mesh-envelope-v1.hex"))
        val hex = resource.readText().filterNot { it.isWhitespace() }
        require(hex.length % 2 == 0)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
