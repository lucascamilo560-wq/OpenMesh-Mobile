package com.openmesh.android

import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshEnvelopeCodec
import com.openmesh.core.PacketPriority
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BleGattOpaqueSeamTest {

    @Test
    fun `legacy client wrapper preserves exact v1 envelope bytes`() {
        val envelope = legacyEnvelope()

        assertArrayEquals(
            MeshEnvelopeCodec.encode(envelope),
            encodeLegacyBleGattPayload(envelope),
        )
    }

    @Test
    fun `opaque payload crosses BLE framing byte exact without envelope decode`() {
        val arbitraryBytes = ByteArray(257) { index -> (index * 31).toByte() }.also {
            it[0] = 0
            it[1] = 1
        }
        val frames = BleFrameCodec.chunk(
            payload = arbitraryBytes,
            maxFrameBytes = 20,
            transferId = 0x0102030405060708L,
        )
        val assembler = BleFrameAssembler()
        var completed: ByteArray? = null

        frames.forEachIndexed { index, frame ->
            val result = assembler.accept(frame, nowMs = 1_000L + index)
            if (index < frames.lastIndex) assertNull(result)
            if (result != null) completed = result
        }

        assertArrayEquals(arbitraryBytes, completed)
    }

    @Test
    fun `raw server handler preserves bytes while legacy handler decodes before delivery`() =
        runBlocking {
            val rawInput = byteArrayOf(0x00, 0x01, 0x7f, 0x55)
            var rawAddress: String? = null
            var rawDelivered: ByteArray? = null
            val rawHandler = opaqueBleGattInboundHandler { address, bytes ->
                rawAddress = address
                rawDelivered = bytes
            }
            val rawDelivery = rawHandler.prepare(ADDRESS, rawInput)
            rawInput[0] = 0x44
            rawDelivery()

            assertEquals(ADDRESS, rawAddress)
            assertArrayEquals(byteArrayOf(0x00, 0x01, 0x7f, 0x55), rawDelivered)

            val expectedEnvelope = legacyEnvelope()
            var deliveredEnvelope: MeshEnvelope? = null
            val legacyHandler = legacyBleGattInboundHandler { deliveredEnvelope = it }
            val legacyDelivery = legacyHandler.prepare(
                ADDRESS,
                MeshEnvelopeCodec.encode(expectedEnvelope),
            )
            legacyDelivery()
            assertEquals(expectedEnvelope, deliveredEnvelope)

            val invalidMagic = MeshEnvelopeCodec.encode(expectedEnvelope).also { it[0] = 0 }
            assertThrows(IllegalArgumentException::class.java) {
                legacyHandler.prepare(ADDRESS, invalidMagic)
            }
            Unit
        }

    private fun legacyEnvelope(): MeshEnvelope = MeshEnvelope(
        protocolVersion = 1,
        packetId = "ble-legacy-compatibility",
        sourceNodeId = "om1-00000000000000000000000000000000",
        destinationNodeId = "om1-11111111111111111111111111111111",
        createdAtMs = 1_700_000_000_000,
        expiresAtMs = 1_700_000_060_000,
        hopCount = 1,
        maxHops = 8,
        lastHopNodeId = "relay-v1",
        priority = PacketPriority.NORMAL,
        contentType = "application/octet-stream",
        payloadBase64 = "AAECfw==",
        signatureBase64 = null,
    )

    private companion object {
        const val ADDRESS = "02:00:00:00:00:0A"
    }
}
