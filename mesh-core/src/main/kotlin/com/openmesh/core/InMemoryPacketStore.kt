package com.openmesh.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryPacketStore : PacketStore {
    private val mutex = Mutex()
    private val packets = LinkedHashMap<String, MeshEnvelope>()

    override suspend fun contains(packetId: String): Boolean = mutex.withLock {
        packets.containsKey(packetId)
    }

    override suspend fun put(packet: MeshEnvelope) {
        mutex.withLock {
            packets[packet.packetId] = packet
        }
    }

    override suspend fun remove(packetId: String) {
        mutex.withLock {
            packets.remove(packetId)
        }
    }

    override suspend fun list(): List<MeshEnvelope> = mutex.withLock {
        packets.values.toList()
    }

    override suspend fun purgeExpired(nowMs: Long): Int = mutex.withLock {
        val expired = packets.values
            .filter { it.isExpired(nowMs) }
            .map { it.packetId }

        expired.forEach(packets::remove)
        expired.size
    }
}
