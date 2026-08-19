package com.openmesh.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshCryptoTest {

    @Test
    fun `two nodes derive compatible shared encryption keys`() {
        val alice = MeshCrypto.generateKeyPair()
        val bob = MeshCrypto.generateKeyPair()
        val plaintext = "mensagem privada".encodeToByteArray()
        val aad = "packet-123".encodeToByteArray()

        val sealed = MeshCrypto.seal(
            plaintext = plaintext,
            senderPrivateKeyBase64 = alice.privateKeyBase64,
            receiverPublicKeyBase64 = bob.publicKeyBase64,
            aad = aad,
        )

        val opened = MeshCrypto.open(
            sealed = sealed,
            receiverPrivateKeyBase64 = bob.privateKeyBase64,
            senderPublicKeyBase64 = alice.publicKeyBase64,
            aad = aad,
        )

        assertContentEquals(plaintext, opened)
    }

    @Test
    fun `signature verifies sender and rejects modified data`() {
        val alice = MeshCrypto.generateKeyPair()
        val data = "OpenMesh".encodeToByteArray()
        val signature = MeshCrypto.sign(data, alice.privateKeyBase64)

        assertTrue(MeshCrypto.verify(data, signature, alice.publicKeyBase64))
        assertFalse(MeshCrypto.verify("OpenMesh!".encodeToByteArray(), signature, alice.publicKeyBase64))
    }
}
