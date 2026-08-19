package com.openmesh.core

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Platform-neutral cryptographic primitives for OpenMesh payloads. */
object MeshCrypto {
    private const val CURVE = "secp256r1"
    private const val KEY_ALGORITHM = "EC"
    private const val KEY_AGREEMENT = "ECDH"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private val random = SecureRandom()

    fun generateKeyPair(): MeshKeyPair {
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        generator.initialize(ECGenParameterSpec(CURVE), random)
        val keyPair = generator.generateKeyPair()
        return MeshKeyPair(
            publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded),
        )
    }

    fun nodeId(publicKeyBase64: String): String {
        val publicBytes = Base64.getDecoder().decode(publicKeyBase64)
        val digest = MessageDigest.getInstance("SHA-256").digest(publicBytes)
        return MeshNodeId.fromDigest(digest.copyOfRange(0, MeshNodeId.DIGEST_BYTES))
    }

    fun seal(
        plaintext: ByteArray,
        senderPrivateKeyBase64: String,
        receiverPublicKeyBase64: String,
        aad: ByteArray = byteArrayOf(),
    ): SealedPayload {
        val key = sharedAesKey(senderPrivateKeyBase64, receiverPublicKeyBase64)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)

        return SealedPayload(
            nonceBase64 = Base64.getEncoder().encodeToString(nonce),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
        )
    }

    fun open(
        sealed: SealedPayload,
        receiverPrivateKeyBase64: String,
        senderPublicKeyBase64: String,
        aad: ByteArray = byteArrayOf(),
    ): ByteArray {
        val key = sharedAesKey(receiverPrivateKeyBase64, senderPublicKeyBase64)
        val nonce = Base64.getDecoder().decode(sealed.nonceBase64)
        val ciphertext = Base64.getDecoder().decode(sealed.ciphertextBase64)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun sign(data: ByteArray, privateKeyBase64: String): String {
        val privateKey = KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64))
        )
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey, random)
            update(data)
            Base64.getEncoder().encodeToString(sign())
        }
    }

    fun verify(data: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean {
        val publicKey = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64))
        )
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(data)
            verify(Base64.getDecoder().decode(signatureBase64))
        }
    }

    private fun sharedAesKey(privateKeyBase64: String, publicKeyBase64: String): SecretKeySpec {
        val factory = KeyFactory.getInstance(KEY_ALGORITHM)
        val privateKey = factory.generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64))
        )
        val publicKey = factory.generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64))
        )
        val agreement = KeyAgreement.getInstance(KEY_AGREEMENT)
        agreement.init(privateKey)
        agreement.doPhase(publicKey, true)
        val sharedSecret = agreement.generateSecret()
        val keyMaterial = MessageDigest.getInstance("SHA-256").digest(
            "OpenMesh-E2E-v1".encodeToByteArray() + sharedSecret
        )
        return SecretKeySpec(keyMaterial, "AES")
    }
}

data class MeshKeyPair(
    val publicKeyBase64: String,
    val privateKeyBase64: String,
) {
    val nodeId: String get() = MeshCrypto.nodeId(publicKeyBase64)
}

data class SealedPayload(
    val nonceBase64: String,
    val ciphertextBase64: String,
)
