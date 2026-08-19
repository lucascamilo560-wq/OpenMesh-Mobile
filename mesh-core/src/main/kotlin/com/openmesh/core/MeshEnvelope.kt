package com.openmesh.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class PacketPriority {
    LOW,
    NORMAL,
    HIGH,
    EMERGENCY
}

@Serializable
data class MeshEnvelope(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val packetId: String = UUID.randomUUID().toString(),
    val sourceNodeId: String,
    val destinationNodeId: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long,
    val hopCount: Int = 0,
    val maxHops: Int = DEFAULT_MAX_HOPS,
    val lastHopNodeId: String? = null,
    val priority: PacketPriority = PacketPriority.NORMAL,
    val contentType: String = "application/octet-stream",
    val payloadBase64: String,
    val signatureBase64: String? = null,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs

    fun canForward(nowMs: Long = System.currentTimeMillis()): Boolean =
        !isExpired(nowMs) && hopCount < maxHops

    fun forwardedBy(nodeId: String): MeshEnvelope = copy(
        hopCount = hopCount + 1,
        lastHopNodeId = nodeId,
    )

    companion object {
        const val CURRENT_PROTOCOL_VERSION = 1
        const val DEFAULT_MAX_HOPS = 12

        fun expiresIn(
            sourceNodeId: String,
            ttlMs: Long,
            destinationNodeId: String? = null,
            priority: PacketPriority = PacketPriority.NORMAL,
            contentType: String = "application/octet-stream",
            payloadBase64: String,
            nowMs: Long = System.currentTimeMillis(),
        ): MeshEnvelope = MeshEnvelope(
            sourceNodeId = sourceNodeId,
            destinationNodeId = destinationNodeId,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + ttlMs,
            priority = priority,
            contentType = contentType,
            payloadBase64 = payloadBase64,
        )
    }
}

object MeshEnvelopeCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(envelope: MeshEnvelope): ByteArray =
        json.encodeToString(envelope).encodeToByteArray()

    fun decode(bytes: ByteArray): MeshEnvelope =
        json.decodeFromString(bytes.decodeToString())
}
