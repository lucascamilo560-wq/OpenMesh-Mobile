package com.openmesh.android

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Splits an OpenMesh envelope into GATT-sized frames and reassembles it.
 * Header is intentionally small enough to work with the default BLE ATT MTU.
 */
object BleFrameCodec {
    const val VERSION: Byte = 1
    const val HEADER_SIZE = 13

    fun chunk(
        payload: ByteArray,
        maxFrameBytes: Int,
        transferId: Long = SecureRandom().nextLong(),
    ): List<ByteArray> {
        require(maxFrameBytes > HEADER_SIZE) { "BLE frame must leave room for payload" }
        val payloadPerFrame = maxFrameBytes - HEADER_SIZE
        val total = ((payload.size + payloadPerFrame - 1) / payloadPerFrame).coerceAtLeast(1)
        require(total <= UShort.MAX_VALUE.toInt()) { "Payload requires too many BLE frames" }

        return (0 until total).map { index ->
            val from = index * payloadPerFrame
            val to = minOf(from + payloadPerFrame, payload.size)
            val part = if (from < payload.size) payload.copyOfRange(from, to) else byteArrayOf()

            ByteBuffer.allocate(HEADER_SIZE + part.size)
                .put(VERSION)
                .putLong(transferId)
                .putShort(index.toShort())
                .putShort(total.toShort())
                .put(part)
                .array()
        }
    }

    fun decode(frame: ByteArray): BleFrame {
        require(frame.size >= HEADER_SIZE) { "BLE frame is truncated" }
        val buffer = ByteBuffer.wrap(frame)
        val version = buffer.get()
        require(version == VERSION) { "Unsupported BLE frame version: $version" }

        val transferId = buffer.long
        val index = buffer.short.toInt() and 0xffff
        val total = buffer.short.toInt() and 0xffff
        require(total > 0 && index < total) { "Invalid BLE frame index" }

        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)
        return BleFrame(transferId, index, total, payload)
    }
}

data class BleFrame(
    val transferId: Long,
    val index: Int,
    val total: Int,
    val payload: ByteArray,
)

class BleFrameAssembler(
    private val staleAfterMs: Long = 60_000,
) {
    private data class Assembly(
        val total: Int,
        val createdAtMs: Long,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
    )

    private val assemblies = ConcurrentHashMap<Long, Assembly>()

    fun accept(frameBytes: ByteArray, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        purgeStale(nowMs)
        val frame = BleFrameCodec.decode(frameBytes)
        val assembly = assemblies.compute(frame.transferId) { _, existing ->
            when {
                existing == null -> Assembly(frame.total, nowMs)
                existing.total != frame.total -> Assembly(frame.total, nowMs)
                else -> existing
            }
        } ?: return null

        assembly.chunks[frame.index] = frame.payload
        if (assembly.chunks.size != assembly.total) return null

        val completed = buildList {
            for (index in 0 until assembly.total) {
                add(assembly.chunks[index] ?: return null)
            }
        }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        assemblies.remove(frame.transferId)
        return completed
    }

    fun purgeStale(nowMs: Long = System.currentTimeMillis()) {
        assemblies.entries.removeIf { (_, assembly) ->
            nowMs - assembly.createdAtMs >= staleAfterMs
        }
    }
}
