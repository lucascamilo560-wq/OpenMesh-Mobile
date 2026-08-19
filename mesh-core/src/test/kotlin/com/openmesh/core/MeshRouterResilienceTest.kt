package com.openmesh.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouterResilienceTest {

    @Test
    fun `offline intermediate node does not block alternate route`() = runBlocking {
        val storeA = InMemoryPacketStore()
        val storeD = InMemoryPacketStore()
        val storeC = InMemoryPacketStore()

        val nodeA = MeshRouter("A", storeA)
        val nodeD = MeshRouter("D", storeD)
        val nodeC = MeshRouter("C", storeC)

        val packet = MeshEnvelope.expiresIn(
            sourceNodeId = "A",
            destinationNodeId = "C",
            ttlMs = 60_000,
            payloadBase64 = "aGVsbG8=",
            nowMs = 1_000,
        )

        assertEquals(IngestResult.STORED_FOR_FORWARDING, nodeA.createLocal(packet))
        assertTrue(storeA.contains(packet.packetId))

        // B never participates. Later A encounters D instead.
        val aToD = nodeA.nextBatchForPeer(peerNodeId = "D", nowMs = 2_000)
        assertEquals(1, aToD.size)
        assertEquals(IngestResult.STORED_FOR_FORWARDING, nodeD.ingest(aToD.single(), nowMs = 2_000))

        // D later encounters C and completes delivery.
        val dToC = nodeD.nextBatchForPeer(peerNodeId = "C", nowMs = 3_000)
        assertEquals(1, dToC.size)
        assertEquals(IngestResult.DELIVERED_LOCAL, nodeC.ingest(dToC.single(), nowMs = 3_000))
        assertEquals(2, dToC.single().hopCount)
    }

    @Test
    fun `duplicate retransmission is ignored`() = runBlocking {
        val store = InMemoryPacketStore()
        val node = MeshRouter("C", store)
        val packet = MeshEnvelope.expiresIn(
            sourceNodeId = "A",
            destinationNodeId = "C",
            ttlMs = 60_000,
            payloadBase64 = "dGVzdA==",
            nowMs = 1_000,
        )

        assertEquals(IngestResult.DELIVERED_LOCAL, node.ingest(packet, nowMs = 2_000))
        assertEquals(IngestResult.DUPLICATE, node.ingest(packet, nowMs = 2_001))
    }
}
