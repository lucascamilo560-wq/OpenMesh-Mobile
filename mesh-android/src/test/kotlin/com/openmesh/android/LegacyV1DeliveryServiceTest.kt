package com.openmesh.android

import com.openmesh.core.DeliveryHandle
import com.openmesh.core.DeliveryPolicy
import com.openmesh.core.MeshCrypto
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.NodeId
import com.openmesh.core.PacketPriority
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyV1DeliveryServiceTest {

    @Test
    fun `bridge maps facade intent explicitly to secure v1 submission`() = runBlocking {
        val sender = MeshCrypto.generateKeyPair()
        val recipient = MeshCrypto.generateKeyPair()
        val binding = LegacyV1EndpointBridge.bind(
            nodeId = NodeId(recipient.nodeId),
            recipientPublicKeyBase64 = recipient.publicKeyBase64,
        )
        val originalPayload = "facade payload".encodeToByteArray()
        var captured: LegacyV1SecureSubmission? = null
        val service = LegacyV1DeliveryService(
            senderIdentity = sender,
            resolveEndpoint = { requested -> binding.takeIf { it.endpointId == requested } },
            submitSecure = { submission ->
                captured = submission
                MeshEnvelope.expiresIn(
                    sourceNodeId = sender.nodeId,
                    destinationNodeId = submission.recipientNodeId.value,
                    ttlMs = submission.ttlMs,
                    priority = submission.priority,
                    contentType = "application/vnd.openmesh.e2e-v1",
                    payloadBase64 = "opaque",
                    nowMs = 1_700_000_000_000,
                ).copy(packetId = "legacy-packet-1")
            },
        )

        val handle = service.deliver(
            destination = binding.endpointId,
            payload = originalPayload,
            policy = DeliveryPolicy(
                lifetimeMs = 120_000,
                priority = DeliveryPolicy.Priority.EMERGENCY,
                contentType = "text/plain",
            ),
        )

        val submission = checkNotNull(captured)
        assertArrayEquals(originalPayload, submission.payload)
        assertNotSame(originalPayload, submission.payload)
        assertEquals("text/plain", submission.contentType)
        assertEquals(sender, submission.senderIdentity)
        assertEquals(NodeId(recipient.nodeId), submission.recipientNodeId)
        assertEquals(recipient.publicKeyBase64, submission.recipientPublicKeyBase64)
        assertEquals(120_000, submission.ttlMs)
        assertEquals(PacketPriority.EMERGENCY, submission.priority)
        assertEquals("legacy-packet-1", handle.requestId.value)
        assertEquals(
            DeliveryHandle.Observation.DurablyStoredLocally,
            handle.observations.first(),
        )
    }

    @Test
    fun `bridge rejects a key that conflicts with the typed node identity`() {
        val expected = MeshCrypto.generateKeyPair()
        val conflicting = MeshCrypto.generateKeyPair()

        assertThrows(IllegalArgumentException::class.java) {
            LegacyV1EndpointBridge.bind(
                nodeId = NodeId(expected.nodeId),
                recipientPublicKeyBase64 = conflicting.publicKeyBase64,
            )
        }
    }

    @Test
    fun `unknown logical endpoint is rejected before v1 submission`() {
        val sender = MeshCrypto.generateKeyPair()
        var submitted = false
        val service = LegacyV1DeliveryService(
            senderIdentity = sender,
            resolveEndpoint = { null },
            submitSecure = {
                submitted = true
                error("must not submit")
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.deliver(
                    destination = com.openmesh.core.EndpointId("dtn://example.test/missing"),
                    payload = byteArrayOf(1),
                    policy = DeliveryPolicy(),
                )
            }
        }
        assertFalse(submitted)
    }
}
