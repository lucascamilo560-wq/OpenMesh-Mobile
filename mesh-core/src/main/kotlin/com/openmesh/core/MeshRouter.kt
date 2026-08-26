package com.openmesh.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MeshRouter(
    private val localNodeId: String,
    private val store: PacketStore,
) {
    private val ingestMutex = Mutex()
    private val _deliveries = MutableSharedFlow<MeshEnvelope>(extraBufferCapacity = 64)
    val deliveries: SharedFlow<MeshEnvelope> = _deliveries.asSharedFlow()

    suspend fun createLocal(
        packet: MeshEnvelope,
        nowMs: Long = System.currentTimeMillis(),
    ): IngestResult {
        require(packet.sourceNodeId == localNodeId) {
            "Local packet source must match localNodeId"
        }
        return ingest(packet, nowMs)
    }

    suspend fun ingest(packet: MeshEnvelope, nowMs: Long = System.currentTimeMillis()): IngestResult {
        if (packet.protocolVersion != MeshEnvelope.CURRENT_PROTOCOL_VERSION) {
            return IngestResult.REJECTED_PROTOCOL
        }
        if (packet.isExpired(nowMs)) {
            return IngestResult.REJECTED_EXPIRED
        }

        val addressedToLocal = packet.destinationNodeId == localNodeId
        val broadcast = packet.destinationNodeId == null
        val result = ingestMutex.withLock {
            if (store.contains(packet.packetId)) {
                return@withLock IngestResult.DUPLICATE
            }

            // Record every non-rejected v1 packet, including packets at their hop limit.
            // Forwardability is evaluated separately by nextBatchForPeer(); retaining
            // the record prevents repeated final/broadcast delivery of the same ID.
            store.put(packet)

            when {
                addressedToLocal -> IngestResult.DELIVERED_LOCAL
                broadcast -> IngestResult.DELIVERED_BROADCAST
                else -> IngestResult.STORED_FOR_FORWARDING
            }
        }

        if (result == IngestResult.DELIVERED_LOCAL || result == IngestResult.DELIVERED_BROADCAST) {
            _deliveries.emit(packet)
        }
        return result
    }

    suspend fun nextBatchForPeer(
        peerNodeId: String,
        peerKnownPacketIds: Set<String> = emptySet(),
        limit: Int = 32,
        nowMs: Long = System.currentTimeMillis(),
    ): List<MeshEnvelope> {
        store.purgeExpired(nowMs)

        return store.list()
            .asSequence()
            .filter { it.canForward(nowMs) }
            .filter { it.destinationNodeId != localNodeId }
            .filter { it.packetId !in peerKnownPacketIds }
            .filter { it.lastHopNodeId != peerNodeId }
            .sortedWith(
                compareByDescending<MeshEnvelope> { it.priority.ordinal }
                    .thenBy { it.createdAtMs }
            )
            .take(limit)
            .map { it.forwardedBy(localNodeId) }
            .toList()
    }
}

enum class IngestResult {
    DELIVERED_LOCAL,
    DELIVERED_BROADCAST,
    STORED_FOR_FORWARDING,
    DUPLICATE,
    REJECTED_EXPIRED,
    REJECTED_PROTOCOL,
}
