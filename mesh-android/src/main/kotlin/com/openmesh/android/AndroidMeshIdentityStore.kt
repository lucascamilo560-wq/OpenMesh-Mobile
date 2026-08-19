package com.openmesh.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.openmesh.core.MeshCrypto
import com.openmesh.core.MeshKeyPair
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists an OpenMesh identity while keeping the exported EC private key
 * encrypted at rest by a non-exportable AES key in Android Keystore.
 *
 * Identity replacement is never silent: corrupted material throws and callers
 * must explicitly call [reset] before generating a new identity.
 */
class AndroidMeshIdentityStore(
    context: Context,
    private val storeName: String = DEFAULT_STORE_NAME,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(storeName, Context.MODE_PRIVATE)
    private val alias = "openmesh.identity.$storeName"

    @Synchronized
    fun loadOrCreate(): MeshKeyPair {
        val publicKey = preferences.getString(KEY_PUBLIC, null)
        val encryptedPrivate = preferences.getString(KEY_PRIVATE_CIPHERTEXT, null)
        val nonce = preferences.getString(KEY_PRIVATE_NONCE, null)

        val populated = listOf(publicKey, encryptedPrivate, nonce).count { it != null }
        if (populated !in setOf(0, 3)) {
            error("OpenMesh identity storage is incomplete; explicit reset is required")
        }

        if (populated == 3) {
            val pair = MeshKeyPair(
                publicKeyBase64 = requireNotNull(publicKey),
                privateKeyBase64 = decryptPrivateKey(
                    ciphertextBase64 = requireNotNull(encryptedPrivate),
                    nonceBase64 = requireNotNull(nonce),
                ),
            )
            verifyPair(pair)
            return pair
        }

        val pair = MeshCrypto.generateKeyPair()
        val protectedPrivate = encryptPrivateKey(pair.privateKeyBase64)
        check(
            preferences.edit()
                .putString(KEY_PUBLIC, pair.publicKeyBase64)
                .putString(KEY_PRIVATE_CIPHERTEXT, protectedPrivate.ciphertextBase64)
                .putString(KEY_PRIVATE_NONCE, protectedPrivate.nonceBase64)
                .commit()
        ) { "Unable to persist OpenMesh identity" }
        verifyPair(pair)
        return pair
    }

    @Synchronized
    fun reset() {
        check(preferences.edit().clear().commit()) { "Unable to clear OpenMesh identity" }
        val keyStore = keyStore()
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun verifyPair(pair: MeshKeyPair) {
        val challenge = "OpenMesh-identity-check-v1".encodeToByteArray()
        val signature = runCatching { MeshCrypto.sign(challenge, pair.privateKeyBase64) }
            .getOrElse { error("Stored OpenMesh private key cannot sign: ${it.message}") }
        check(MeshCrypto.verify(challenge, signature, pair.publicKeyBase64)) {
            "Stored OpenMesh public/private keys do not form a valid identity"
        }
    }

    private fun encryptPrivateKey(privateKeyBase64: String): ProtectedValue {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val ciphertext = cipher.doFinal(privateKeyBase64.encodeToByteArray())
        return ProtectedValue(
            ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            nonceBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    private fun decryptPrivateKey(ciphertextBase64: String, nonceBase64: String): String {
        val key = requireWrappingKey()
        val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        val nonce = Base64.decode(nonceBase64, Base64.NO_WRAP)
        require(nonce.size == GCM_NONCE_BYTES) { "Invalid stored OpenMesh identity nonce" }

        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun requireWrappingKey(): SecretKey =
        keyStore().getKey(alias, null) as? SecretKey
            ?: error("OpenMesh Keystore wrapping key is missing; explicit identity reset is required")

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private data class ProtectedValue(
        val ciphertextBase64: String,
        val nonceBase64: String,
    )

    companion object {
        private const val DEFAULT_STORE_NAME = "openmesh_identity"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_NONCE_BYTES = 12
        private const val KEY_PUBLIC = "public_key"
        private const val KEY_PRIVATE_CIPHERTEXT = "private_key_ciphertext"
        private const val KEY_PRIVATE_NONCE = "private_key_nonce"
    }
}
