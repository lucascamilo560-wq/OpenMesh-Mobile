package com.openmesh.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/** Challenge-response proof that a peer possesses the private key behind its self-certifying node ID. */
object PeerIdentityProof {
    const val CHALLENGE_BYTES = 32
    private const val DOMAIN = "OpenMesh-peer-possession-v1"
    private val random = SecureRandom()

    fun newChallenge(): ByteArray = ByteArray(CHALLENGE_BYTES).also(random::nextBytes)

    fun sign(
        challenge: ByteArray,
        identity: MeshKeyPair,
    ): String {
        require(challenge.size == CHALLENGE_BYTES) { "Invalid OpenMesh identity challenge size" }
        return MeshCrypto.sign(
            proofBytes(challenge, identity.nodeId),
            identity.privateKeyBase64,
        )
    }

    fun verify(
        challenge: ByteArray,
        nodeId: String,
        publicKeyBase64: String,
        signatureBase64: String,
    ): Boolean {
        if (challenge.size != CHALLENGE_BYTES) return false
        val derivedNodeId = runCatching { MeshCrypto.nodeId(publicKeyBase64) }.getOrNull() ?: return false
        if (derivedNodeId != nodeId) return false
        return runCatching {
            MeshCrypto.verify(
                data = proofBytes(challenge, nodeId),
                signatureBase64 = signatureBase64,
                publicKeyBase64 = publicKeyBase64,
            )
        }.getOrDefault(false)
    }

    private fun proofBytes(challenge: ByteArray, nodeId: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(DOMAIN)
            out.writeUTF(nodeId)
            out.writeInt(challenge.size)
            out.write(challenge)
        }
        return bytes.toByteArray()
    }
}
