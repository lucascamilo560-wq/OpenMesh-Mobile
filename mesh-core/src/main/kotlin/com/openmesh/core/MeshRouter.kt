package com.openmesh.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MeshRouter(
    private val localNodeId: String,
    private val store: PacketStore,
) {
    private val _deliveries = MutableSharedFlow<MeshEnvelope>(extraBufferCapacity = 64)
    val deliveries: SharedFlow<MeshEnvelope> = _deliveries.asSharedFlow()

    suspend fun createLocal(packet: MeshEnvelope): IngestResult {
        require(packet.sourceNodeId == localNodeId) {
            "Local packet source must match localNodeId"
        }
        return ingest(packet)
    }

    suspend fun ingest(packet: MeshEnvelope, nowMs: Long = System.currentTimeMillis()): IngestResult {
        if (packet.protocolVersion != MeshEnvelope.CURRENT_PROTOCOL_VERSION) {
            return IngestResult.REJECTED_PROTOCOL
        }
        if (packet.isExpired(nowMs)) {
            return IngestResult.REJECTED_EXPIRED
        }
        if (store.contains(packet.packetId)) {
            return IngestResult.DUPLICATE
        }

        val addressedToLocal = packet.destinationNodeId == localNodeId
        val broadcast = packet.destinationNodeId == null

        if (addressedToLocal || broadcast) {
            _deliveries.emit(packet)
        }

        // Keep a record even after final delivery so duplicates are suppressed.
        // nextBatchForPeer() prevents final-destination packets from being re-forwarded.
        if (packet.canForward(nowMs)) {
            store.put(packet)
        }

        return when {
            addressedToLocal -> IngestResult.DELIVERED_LOCAL
            broadcast -> IngestResult.DELIVERED_BROADCAST
            else -> IngestResult.STORED_FOR_FORWARDING
        }
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
