package com.openmesh.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

enum class PacketPriority {
    LOW,
    NORMAL,
    HIGH,
    EMERGENCY
}

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

/** Compact, deterministic binary codec suitable for constrained transports such as BLE. */
object MeshEnvelopeCodec {
    private const val MAGIC = 0x4F4D5348 // OMSH
    private const val MAX_STRING_BYTES = 16 * 1024 * 1024

    fun encode(envelope: MeshEnvelope): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(envelope.protocolVersion)
            out.writeString(envelope.packetId)
            out.writeString(envelope.sourceNodeId)
            out.writeNullableString(envelope.destinationNodeId)
            out.writeLong(envelope.createdAtMs)
            out.writeLong(envelope.expiresAtMs)
            out.writeInt(envelope.hopCount)
            out.writeInt(envelope.maxHops)
            out.writeNullableString(envelope.lastHopNodeId)
            out.writeInt(envelope.priority.ordinal)
            out.writeString(envelope.contentType)
            out.writeString(envelope.payloadBase64)
            out.writeNullableString(envelope.signatureBase64)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): MeshEnvelope = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == MAGIC) { "Invalid OpenMesh envelope magic" }
        val protocolVersion = input.readInt()
        val packetId = input.readString()
        val sourceNodeId = input.readString()
        val destinationNodeId = input.readNullableString()
        val createdAtMs = input.readLong()
        val expiresAtMs = input.readLong()
        val hopCount = input.readInt()
        val maxHops = input.readInt()
        val lastHopNodeId = input.readNullableString()
        val priorityOrdinal = input.readInt()
        require(priorityOrdinal in PacketPriority.entries.indices) { "Invalid packet priority" }
        val contentType = input.readString()
        val payloadBase64 = input.readString()
        val signatureBase64 = input.readNullableString()

        val envelope = MeshEnvelope(
            protocolVersion = protocolVersion,
            packetId = packetId,
            sourceNodeId = sourceNodeId,
            destinationNodeId = destinationNodeId,
            createdAtMs = createdAtMs,
            expiresAtMs = expiresAtMs,
            hopCount = hopCount,
            maxHops = maxHops,
            lastHopNodeId = lastHopNodeId,
            priority = PacketPriority.entries[priorityOrdinal],
            contentType = contentType,
            payloadBase64 = payloadBase64,
            signatureBase64 = signatureBase64,
        )
        require(input.available() == 0) { "Unexpected trailing OpenMesh envelope data" }
        envelope
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES) { "Invalid OpenMesh string size: $size" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.decodeToString()
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null
}
