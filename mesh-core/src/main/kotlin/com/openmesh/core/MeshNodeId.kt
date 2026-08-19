package com.openmesh.core

/** Canonical compact OpenMesh node identifier used in routing and BLE discovery. */
object MeshNodeId {
    const val PREFIX = "om1-"
    const val DIGEST_BYTES = 16
    private const val HEX_CHARS = DIGEST_BYTES * 2

    fun fromDigest(digest: ByteArray): String {
        require(digest.size == DIGEST_BYTES) { "OpenMesh node digest must be $DIGEST_BYTES bytes" }
        return PREFIX + digest.joinToString(separator = "") { "%02x".format(it) }
    }

    fun fromAdvertisementBytes(bytes: ByteArray): String = fromDigest(bytes)

    fun toAdvertisementBytes(nodeId: String): ByteArray {
        require(isValid(nodeId)) { "Invalid OpenMesh node ID: $nodeId" }
        val hex = nodeId.removePrefix(PREFIX)
        return ByteArray(DIGEST_BYTES) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun isValid(nodeId: String): Boolean {
        if (!nodeId.startsWith(PREFIX)) return false
        val hex = nodeId.removePrefix(PREFIX)
        return hex.length == HEX_CHARS && hex.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
