package com.openmesh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouterConcurrencyTest {

    @Test
    fun `concurrent duplicate ingestion produces one local result`() = runBlocking {
        val store = DelayedContainsPacketStore()
        val router = MeshRouter(localNodeId = "B", store = store)
        val packet = MeshEnvelope.expiresIn(
            sourceNodeId = "A",
            destinationNodeId = "B",
            ttlMs = 60_000,
            payloadBase64 = "cmFjZQ==",
            nowMs = 1_000,
        )
        val start = CompletableDeferred<Unit>()
        val attempts = List(16) {
            async(Dispatchers.Default) {
                start.await()
                router.ingest(packet, nowMs = 2_000)
            }
        }

        start.complete(Unit)
        val results = attempts.awaitAll()

        assertEquals(1, results.count { it == IngestResult.DELIVERED_LOCAL })
        assertEquals(15, results.count { it == IngestResult.DUPLICATE })
        assertEquals(listOf(packet), store.list())
    }

    @Test
    fun `packet delivered at hop limit remains recorded for deduplication`() = runBlocking {
        val store = InMemoryPacketStore()
        val router = MeshRouter(localNodeId = "B", store = store)
        val packet = MeshEnvelope(
            packetId = "packet-at-hop-limit",
            sourceNodeId = "A",
            destinationNodeId = "B",
            createdAtMs = 1_000,
            expiresAtMs = 61_000,
            hopCount = 12,
            maxHops = 12,
            payloadBase64 = "aG9w",
        )

        assertEquals(IngestResult.DELIVERED_LOCAL, router.ingest(packet, nowMs = 2_000))
        assertTrue(store.contains(packet.packetId))
        assertEquals(IngestResult.DUPLICATE, router.ingest(packet, nowMs = 2_001))
        assertTrue(router.nextBatchForPeer(peerNodeId = "relay", nowMs = 3_000).isEmpty())
    }

    private class DelayedContainsPacketStore : PacketStore {
        private val delegate = InMemoryPacketStore()

        override suspend fun contains(packetId: String): Boolean {
            val presentBeforeDelay = delegate.contains(packetId)
            delay(15)
            return presentBeforeDelay
        }

        override suspend fun put(packet: MeshEnvelope) = delegate.put(packet)

        override suspend fun remove(packetId: String) = delegate.remove(packetId)

        override suspend fun list(): List<MeshEnvelope> = delegate.list()

        override suspend fun purgeExpired(nowMs: Long): Int = delegate.purgeExpired(nowMs)
    }
}
