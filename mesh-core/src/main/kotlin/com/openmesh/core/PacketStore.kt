package com.openmesh.core

interface PacketStore {
    suspend fun contains(packetId: String): Boolean
    suspend fun put(packet: MeshEnvelope)
    suspend fun remove(packetId: String)
    suspend fun list(): List<MeshEnvelope>
    suspend fun purgeExpired(nowMs: Long = System.currentTimeMillis()): Int
}
