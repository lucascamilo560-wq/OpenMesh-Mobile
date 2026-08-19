package com.openmesh.android

import android.content.Context
import com.openmesh.core.MeshCrypto

/**
 * Durable cache for peer public keys that already passed private-key possession
 * verification. Human/contact trust is intentionally not represented here.
 *
 * Because OpenMesh node IDs are derived from public keys, a legitimate key
 * rotation creates a new node ID. A conflicting key for the same node ID is
 * therefore rejected rather than silently replacing the stored value.
 */
class VerifiedPeerKeyStore(
    context: Context,
    name: String = DEFAULT_STORE_NAME,
) {
    private val preferences = context.applicationContext
        .getSharedPreferences(name, Context.MODE_PRIVATE)

    @Synchronized
    fun putVerified(
        nodeId: String,
        publicKeyBase64: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        require(MeshCrypto.nodeId(publicKeyBase64) == nodeId) {
            "Peer public key does not match node ID"
        }

        val existing = preferences.getString(publicKeyKey(nodeId), null)
        require(existing == null || existing == publicKeyBase64) {
            "Conflicting public key for self-certifying node ID $nodeId"
        }

        val editor = preferences.edit()
            .putString(publicKeyKey(nodeId), publicKeyBase64)
            .putLong(lastVerifiedKey(nodeId), nowMs)

        if (!preferences.contains(firstVerifiedKey(nodeId))) {
            editor.putLong(firstVerifiedKey(nodeId), nowMs)
        }

        check(editor.commit()) { "Unable to persist verified OpenMesh peer key" }
    }

    @Synchronized
    fun getVerifiedPublicKey(nodeId: String): String? {
        val key = preferences.getString(publicKeyKey(nodeId), null) ?: return null
        return if (runCatching { MeshCrypto.nodeId(key) == nodeId }.getOrDefault(false)) {
            key
        } else {
            remove(nodeId)
            null
        }
    }

    @Synchronized
    fun metadata(nodeId: String): VerifiedPeerMetadata? {
        val key = getVerifiedPublicKey(nodeId) ?: return null
        return VerifiedPeerMetadata(
            nodeId = nodeId,
            publicKeyBase64 = key,
            firstVerifiedAtMs = preferences.getLong(firstVerifiedKey(nodeId), 0L),
            lastVerifiedAtMs = preferences.getLong(lastVerifiedKey(nodeId), 0L),
        )
    }

    @Synchronized
    fun listVerified(): List<VerifiedPeerMetadata> = preferences.all.keys
        .asSequence()
        .filter { it.startsWith(PEER_PREFIX) && it.endsWith(PUBLIC_SUFFIX) }
        .map { key -> key.removePrefix(PEER_PREFIX).removeSuffix(PUBLIC_SUFFIX) }
        .distinct()
        .mapNotNull(::metadata)
        .sortedByDescending { it.lastVerifiedAtMs }
        .toList()

    @Synchronized
    fun remove(nodeId: String) {
        check(
            preferences.edit()
                .remove(publicKeyKey(nodeId))
                .remove(firstVerifiedKey(nodeId))
                .remove(lastVerifiedKey(nodeId))
                .commit()
        ) { "Unable to remove verified OpenMesh peer key" }
    }

    private fun publicKeyKey(nodeId: String) = "$PEER_PREFIX$nodeId$PUBLIC_SUFFIX"
    private fun firstVerifiedKey(nodeId: String) = "$PEER_PREFIX$nodeId.firstVerifiedAt"
    private fun lastVerifiedKey(nodeId: String) = "$PEER_PREFIX$nodeId.lastVerifiedAt"

    companion object {
        private const val DEFAULT_STORE_NAME = "openmesh_verified_peers"
        private const val PEER_PREFIX = "peer."
        private const val PUBLIC_SUFFIX = ".public"
    }
}

data class VerifiedPeerMetadata(
    val nodeId: String,
    val publicKeyBase64: String,
    val firstVerifiedAtMs: Long,
    val lastVerifiedAtMs: Long,
)
