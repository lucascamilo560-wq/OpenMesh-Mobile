package com.openmesh.android

import android.content.Context
import android.util.Base64
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshEnvelopeCodec
import com.openmesh.core.PacketStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small durable packet store used by the Android SDK.
 *
 * Packets survive process death and device restarts, which is essential for
 * delay-tolerant/store-and-forward delivery when no route is currently available.
 */
class SharedPreferencesPacketStore(
    context: Context,
    name: String = DEFAULT_STORE_NAME,
) : PacketStore {
    private val preferences = context.applicationContext
        .getSharedPreferences(name, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun contains(packetId: String): Boolean = mutex.withLock {
        preferences.contains(key(packetId))
    }

    override suspend fun put(packet: MeshEnvelope) = mutex.withLock {
        val encoded = Base64.encodeToString(
            MeshEnvelopeCodec.encode(packet),
            Base64.NO_WRAP,
        )
        check(preferences.edit().putString(key(packet.packetId), encoded).commit()) {
            "Unable to persist OpenMesh packet ${packet.packetId}"
        }
    }

    override suspend fun remove(packetId: String) = mutex.withLock {
        check(preferences.edit().remove(key(packetId)).commit()) {
            "Unable to remove OpenMesh packet $packetId"
        }
    }

    override suspend fun list(): List<MeshEnvelope> = mutex.withLock {
        decodeAllLocked()
    }

    override suspend fun purgeExpired(nowMs: Long): Int = mutex.withLock {
        val expired = decodeAllLocked().filter { it.isExpired(nowMs) }
        if (expired.isEmpty()) return@withLock 0

        val editor = preferences.edit()
        expired.forEach { editor.remove(key(it.packetId)) }
        check(editor.commit()) { "Unable to purge expired OpenMesh packets" }
        expired.size
    }

    private fun decodeAllLocked(): List<MeshEnvelope> = preferences.all
        .asSequence()
        .filter { (entryKey, _) -> entryKey.startsWith(PACKET_PREFIX) }
        .mapNotNull { (_, value) -> value as? String }
        .mapNotNull { encoded ->
            runCatching {
                MeshEnvelopeCodec.decode(Base64.decode(encoded, Base64.NO_WRAP))
            }.getOrNull()
        }
        .toList()

    private fun key(packetId: String): String = "$PACKET_PREFIX$packetId"

    companion object {
        private const val DEFAULT_STORE_NAME = "openmesh_packet_store"
        private const val PACKET_PREFIX = "packet."
    }
}
