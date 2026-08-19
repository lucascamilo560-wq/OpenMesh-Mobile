package com.openmesh.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerIdentityProofTest {

    @Test
    fun `peer proves possession of private key behind node id`() {
        val identity = MeshCrypto.generateKeyPair()
        val challenge = PeerIdentityProof.newChallenge()
        val signature = PeerIdentityProof.sign(challenge, identity)

        assertTrue(
            PeerIdentityProof.verify(
                challenge = challenge,
                nodeId = identity.nodeId,
                publicKeyBase64 = identity.publicKeyBase64,
                signatureBase64 = signature,
            )
        )
    }

    @Test
    fun `copied public identity cannot answer challenge with another private key`() {
        val realPeer = MeshCrypto.generateKeyPair()
        val impostor = MeshCrypto.generateKeyPair()
        val challenge = PeerIdentityProof.newChallenge()
        val forgedSignature = PeerIdentityProof.sign(challenge, impostor)

        assertFalse(
            PeerIdentityProof.verify(
                challenge = challenge,
                nodeId = realPeer.nodeId,
                publicKeyBase64 = realPeer.publicKeyBase64,
                signatureBase64 = forgedSignature,
            )
        )
    }
}
