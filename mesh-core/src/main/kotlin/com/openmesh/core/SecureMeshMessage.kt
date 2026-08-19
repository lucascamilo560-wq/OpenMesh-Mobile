package com.openmesh.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID

/**
 * Creates and opens end-to-end encrypted OpenMesh data envelopes.
 *
 * Recipient key discovery is deliberately out of scope here: callers must
 * already possess the intended recipient's authenticated public key. Relay
 * nodes only see the normal routing header and an opaque encrypted payload.
 */
object SecureMeshMessage {
    const val OUTER_CONTENT_TYPE = "application/vnd.openmesh.e2e-v1"

    fun create(
        plaintext: ByteArray,
        innerContentType: String,
        sender: MeshKeyPair,
        recipientNodeId: String,
        recipientPublicKeyBase64: String,
        ttlMs: Long,
        priority: PacketPriority = PacketPriority.NORMAL,
        maxHops: Int = MeshEnvelope.DEFAULT_MAX_HOPS,
        nowMs: Long = System.currentTimeMillis(),
        packetId: String = UUID.randomUUID().toString(),
    ): MeshEnvelope {
        require(recipientNodeId == MeshCrypto.nodeId(recipientPublicKeyBase64)) {
            "Recipient node ID does not match recipient public key"
        }
        require(ttlMs > 0) { "TTL must be positive" }
        require(maxHops > 0) { "maxHops must be positive" }

        val senderNodeId = sender.nodeId
        val expiresAtMs = Math.addExact(nowMs, ttlMs)
        val envelopeTemplate = MeshEnvelope(
            packetId = packetId,
            sourceNodeId = senderNodeId,
            destinationNodeId = recipientNodeId,
            createdAtMs = nowMs,
            expiresAtMs = expiresAtMs,
            maxHops = maxHops,
            priority = priority,
            contentType = OUTER_CONTENT_TYPE,
            payloadBase64 = "",
        )

        val inner = SecureInnerPayloadCodec.encode(innerContentType, plaintext)
        val aad = authenticatedRoutingHeader(envelopeTemplate)
        val sealed = MeshCrypto.seal(
            plaintext = inner,
            senderPrivateKeyBase64 = sender.privateKeyBase64,
            receiverPublicKeyBase64 = recipientPublicKeyBase64,
            aad = aad,
        )
        val container = SecureContainer(
            senderPublicKeyBase64 = sender.publicKeyBase64,
            nonce = Base64.getDecoder().decode(sealed.nonceBase64),
            ciphertext = Base64.getDecoder().decode(sealed.ciphertextBase64),
        )
        val encodedContainer = SecureContainerCodec.encode(container)
        val signature = MeshCrypto.sign(
            signedBytes(aad, container),
            sender.privateKeyBase64,
        )

        return envelopeTemplate.copy(
            payloadBase64 = Base64.getEncoder().encodeToString(encodedContainer),
            signatureBase64 = signature,
        )
    }

    fun open(
        envelope: MeshEnvelope,
        recipient: MeshKeyPair,
    ): OpenedSecureMessage {
        require(envelope.contentType == OUTER_CONTENT_TYPE) { "Envelope is not an E2E OpenMesh message" }
        require(envelope.destinationNodeId == recipient.nodeId) { "Envelope is addressed to another node" }

        val signature = requireNotNull(envelope.signatureBase64) { "Secure envelope has no signature" }
        val containerBytes = Base64.getDecoder().decode(envelope.payloadBase64)
        val container = SecureContainerCodec.decode(containerBytes)
        val senderNodeId = MeshCrypto.nodeId(container.senderPublicKeyBase64)
        require(senderNodeId == envelope.sourceNodeId) { "Sender node ID does not match sender key" }

        val aad = authenticatedRoutingHeader(envelope)
        require(
            MeshCrypto.verify(
                data = signedBytes(aad, container),
                signatureBase64 = signature,
                publicKeyBase64 = container.senderPublicKeyBase64,
            )
        ) { "Secure envelope signature is invalid" }

        val plaintext = MeshCrypto.open(
            sealed = SealedPayload(
                nonceBase64 = Base64.getEncoder().encodeToString(container.nonce),
                ciphertextBase64 = Base64.getEncoder().encodeToString(container.ciphertext),
            ),
            receiverPrivateKeyBase64 = recipient.privateKeyBase64,
            senderPublicKeyBase64 = container.senderPublicKeyBase64,
            aad = aad,
        )
        val inner = SecureInnerPayloadCodec.decode(plaintext)
        return OpenedSecureMessage(
            senderNodeId = senderNodeId,
            senderPublicKeyBase64 = container.senderPublicKeyBase64,
            contentType = inner.contentType,
            payload = inner.payload,
        )
    }

    private fun authenticatedRoutingHeader(envelope: MeshEnvelope): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(envelope.protocolVersion)
            out.writeUTF(envelope.packetId)
            out.writeUTF(envelope.sourceNodeId)
            out.writeUTF(requireNotNull(envelope.destinationNodeId))
            out.writeLong(envelope.createdAtMs)
            out.writeLong(envelope.expiresAtMs)
            out.writeInt(envelope.maxHops)
            out.writeInt(envelope.priority.ordinal)
            out.writeUTF(envelope.contentType)
        }
        return bytes.toByteArray()
    }

    private fun signedBytes(aad: ByteArray, container: SecureContainer): ByteArray =
        aad + container.nonce + container.ciphertext
}

data class OpenedSecureMessage(
    val senderNodeId: String,
    val senderPublicKeyBase64: String,
    val contentType: String,
    val payload: ByteArray,
)

private data class SecureContainer(
    val senderPublicKeyBase64: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

private object SecureContainerCodec {
    private const val MAX_PUBLIC_KEY_BYTES = 4 * 1024
    private const val MAX_CIPHERTEXT_BYTES = 20 * 1024 * 1024

    fun encode(container: SecureContainer): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeLengthPrefixed(container.senderPublicKeyBase64.encodeToByteArray())
            out.writeLengthPrefixed(container.nonce)
            out.writeLengthPrefixed(container.ciphertext)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): SecureContainer = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val publicKey = input.readLengthPrefixed(MAX_PUBLIC_KEY_BYTES).decodeToString()
        val nonce = input.readLengthPrefixed(64)
        val ciphertext = input.readLengthPrefixed(MAX_CIPHERTEXT_BYTES)
        require(input.available() == 0) { "Unexpected trailing secure-container data" }
        SecureContainer(publicKey, nonce, ciphertext)
    }

    private fun DataOutputStream.writeLengthPrefixed(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readLengthPrefixed(maxBytes: Int): ByteArray {
        val size = readInt()
        require(size in 0..maxBytes) { "Invalid secure-container field size: $size" }
        return ByteArray(size).also(::readFully)
    }
}

private data class SecureInnerPayload(
    val contentType: String,
    val payload: ByteArray,
)

private object SecureInnerPayloadCodec {
    fun encode(contentType: String, payload: ByteArray): ByteArray {
        require(contentType.length <= 8_192) { "Inner content type is too large" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Encrypted payload is too large" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(contentType)
            out.writeInt(payload.size)
            out.write(payload)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): SecureInnerPayload = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val contentType = input.readUTF()
        val size = input.readInt()
        require(size in 0..MAX_PAYLOAD_BYTES) { "Invalid encrypted payload size: $size" }
        val payload = ByteArray(size)
        input.readFully(payload)
        require(input.available() == 0) { "Unexpected trailing encrypted-payload data" }
        SecureInnerPayload(contentType, payload)
    }

    private const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
}
