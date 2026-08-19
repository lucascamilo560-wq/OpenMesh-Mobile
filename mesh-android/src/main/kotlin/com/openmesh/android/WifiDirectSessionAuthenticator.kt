package com.openmesh.android

import com.openmesh.core.MeshCrypto
import com.openmesh.core.MeshKeyPair
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Cryptographically binds a Wi-Fi Direct TCP session back to an OpenMesh identity. */
object WifiDirectSessionAuthenticator {

    suspend fun authenticate(
        session: WifiDirectSocketSession,
        sessionId: String,
        localIdentity: MeshKeyPair,
        expectedPeerNodeId: String,
        expectedPeerPublicKeyBase64: String,
        timeoutMs: Long = 10_000,
    ): Boolean {
        if (MeshCrypto.nodeId(expectedPeerPublicKeyBase64) != expectedPeerNodeId) return false

        val remoteVerified = CompletableDeferred<Boolean>()
        session.onFrame { frame ->
            if (remoteVerified.isCompleted) return@onFrame
            val hello = runCatching { WifiDirectSessionHelloCodec.decode(frame) }.getOrNull()
                ?: return@onFrame
            if (hello.sessionId != sessionId) {
                remoteVerified.complete(false)
                return@onFrame
            }
            if (hello.nodeId != expectedPeerNodeId) {
                remoteVerified.complete(false)
                return@onFrame
            }
            if (hello.publicKeyBase64 != expectedPeerPublicKeyBase64) {
                remoteVerified.complete(false)
                return@onFrame
            }
            val valid = runCatching {
                MeshCrypto.verify(
                    data = signedHelloBytes(hello.sessionId, hello.nodeId, hello.publicKeyBase64),
                    signatureBase64 = hello.signatureBase64,
                    publicKeyBase64 = hello.publicKeyBase64,
                )
            }.getOrDefault(false)
            remoteVerified.complete(valid)
        }

        session.startReading()

        val localHello = WifiDirectSessionHello(
            sessionId = sessionId,
            nodeId = localIdentity.nodeId,
            publicKeyBase64 = localIdentity.publicKeyBase64,
            signatureBase64 = MeshCrypto.sign(
                signedHelloBytes(sessionId, localIdentity.nodeId, localIdentity.publicKeyBase64),
                localIdentity.privateKeyBase64,
            ),
        )

        if (!session.sendFrame(WifiDirectSessionHelloCodec.encode(localHello))) return false
        return withTimeoutOrNull(timeoutMs) { remoteVerified.await() } == true
    }

    private fun signedHelloBytes(sessionId: String, nodeId: String, publicKeyBase64: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeUTF(SIGNING_DOMAIN)
            out.writeUTF(sessionId)
            out.writeUTF(nodeId)
            out.writeUTF(publicKeyBase64)
        }
        return bytes.toByteArray()
    }

    private const val SIGNING_DOMAIN = "OpenMesh-WifiDirect-Hello-v1"
}

data class WifiDirectSessionHello(
    val sessionId: String,
    val nodeId: String,
    val publicKeyBase64: String,
    val signatureBase64: String,
)

object WifiDirectSessionHelloCodec {
    fun encode(hello: WifiDirectSessionHello): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(hello.sessionId)
            out.writeUTF(hello.nodeId)
            out.writeUTF(hello.publicKeyBase64)
            out.writeUTF(hello.signatureBase64)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): WifiDirectSessionHello =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Not an OpenMesh Wi-Fi Direct hello" }
            require(input.readInt() == VERSION) { "Unsupported Wi-Fi Direct hello version" }
            WifiDirectSessionHello(
                sessionId = input.readUTF(),
                nodeId = input.readUTF(),
                publicKeyBase64 = input.readUTF(),
                signatureBase64 = input.readUTF(),
            )
        }

    private const val MAGIC = 0x4F4D484C // OMHL
    private const val VERSION = 1
}
