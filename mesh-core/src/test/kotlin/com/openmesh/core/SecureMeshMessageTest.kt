package com.openmesh.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureMeshMessageTest {

    @Test
    fun `relay can forward ciphertext without breaking recipient verification`() = runBlocking {
        val alice = MeshCrypto.generateKeyPair()
        val bob = MeshCrypto.generateKeyPair()
        val payload = "mensagem somente para Bob".encodeToByteArray()

        val original = SecureMeshMessage.create(
            plaintext = payload,
            innerContentType = "text/plain",
            sender = alice,
            recipientNodeId = bob.nodeId,
            recipientPublicKeyBase64 = bob.publicKeyBase64,
            ttlMs = 60_000,
            nowMs = 1_000,
            packetId = "packet-secure-1",
        )

        val relayStore = InMemoryPacketStore()
        val relay = MeshRouter("relay-D", relayStore)
        assertEquals(IngestResult.STORED_FOR_FORWARDING, relay.ingest(original, nowMs = 2_000))
        val forwarded = relay.nextBatchForPeer(peerNodeId = bob.nodeId, nowMs = 3_000).single()

        assertEquals(1, forwarded.hopCount)
        assertEquals("relay-D", forwarded.lastHopNodeId)
        assertEquals(original.payloadBase64, forwarded.payloadBase64)
        assertEquals(original.signatureBase64, forwarded.signatureBase64)

        val opened = SecureMeshMessage.open(forwarded, bob)
        assertEquals(alice.nodeId, opened.senderNodeId)
        assertEquals("text/plain", opened.contentType)
        assertArrayEquals(payload, opened.payload)
    }

    @Test
    fun `authenticated routing header rejects destination tampering`() {
        val alice = MeshCrypto.generateKeyPair()
        val bob = MeshCrypto.generateKeyPair()
        val mallory = MeshCrypto.generateKeyPair()
        val envelope = SecureMeshMessage.create(
            plaintext = "segredo".encodeToByteArray(),
            innerContentType = "text/plain",
            sender = alice,
            recipientNodeId = bob.nodeId,
            recipientPublicKeyBase64 = bob.publicKeyBase64,
            ttlMs = 60_000,
            nowMs = 1_000,
        )

        val tampered = envelope.copy(destinationNodeId = mallory.nodeId)
        val failed = runCatching { SecureMeshMessage.open(tampered, mallory) }
        assertTrue(failed.isFailure)
    }
}
