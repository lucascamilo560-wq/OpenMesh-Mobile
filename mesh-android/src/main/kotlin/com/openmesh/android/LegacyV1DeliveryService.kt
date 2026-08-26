package com.openmesh.android

import com.openmesh.core.DeliveryHandle
import com.openmesh.core.DeliveryPolicy
import com.openmesh.core.DeliveryService
import com.openmesh.core.EndpointId
import com.openmesh.core.MeshCrypto
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshKeyPair
import com.openmesh.core.NodeId
import com.openmesh.core.PacketPriority
import kotlinx.coroutines.flow.flowOf

/**
 * Explicit, removable mapping from a logical endpoint to one v1 node/key pair.
 * The endpoint syntax is compatibility-only and does not reserve the future v2
 * public EID namespace.
 */
class LegacyV1EndpointBinding internal constructor(
    val endpointId: EndpointId,
    val nodeId: NodeId,
    val recipientPublicKeyBase64: String,
)

object LegacyV1EndpointBridge {
    fun bind(peer: VerifiedPeerMetadata): LegacyV1EndpointBinding = bind(
        nodeId = NodeId(peer.nodeId),
        recipientPublicKeyBase64 = peer.publicKeyBase64,
    )

    fun bind(
        nodeId: NodeId,
        recipientPublicKeyBase64: String,
    ): LegacyV1EndpointBinding {
        require(MeshCrypto.nodeId(recipientPublicKeyBase64) == nodeId.value) {
            "Legacy v1 recipient key does not match node ID"
        }
        return LegacyV1EndpointBinding(
            endpointId = EndpointId("dtn://openmesh/node/${nodeId.value}/inbox"),
            nodeId = nodeId,
            recipientPublicKeyBase64 = recipientPublicKeyBase64,
        )
    }
}

/**
 * Compatibility implementation of the transport-neutral facade over the
 * existing secure v1 sender. It performs no transport selection or routing.
 */
class LegacyV1DeliveryService internal constructor(
    private val senderIdentity: MeshKeyPair,
    private val resolveEndpoint: suspend (EndpointId) -> LegacyV1EndpointBinding?,
    private val submitSecure: suspend (LegacyV1SecureSubmission) -> MeshEnvelope,
) : DeliveryService {
    constructor(
        node: BleMeshNode,
        senderIdentity: MeshKeyPair,
        resolveEndpoint: suspend (EndpointId) -> LegacyV1EndpointBinding?,
    ) : this(
        senderIdentity = senderIdentity,
        resolveEndpoint = resolveEndpoint,
        submitSecure = { submission ->
            node.sendSecure(
                payload = submission.payload,
                contentType = submission.contentType,
                senderIdentity = submission.senderIdentity,
                recipientNodeId = submission.recipientNodeId.value,
                recipientPublicKeyBase64 = submission.recipientPublicKeyBase64,
                ttlMs = submission.ttlMs,
                priority = submission.priority,
            )
        },
    )

    override suspend fun deliver(
        destination: EndpointId,
        payload: ByteArray,
        policy: DeliveryPolicy,
    ): DeliveryHandle {
        when (policy.desiredEvidence) {
            DeliveryPolicy.DesiredEvidence.NONE -> Unit
        }

        val binding = requireNotNull(resolveEndpoint(destination)) {
            "No legacy v1 binding for endpoint $destination"
        }
        require(binding.endpointId == destination) {
            "Legacy v1 resolver returned a binding for another endpoint"
        }

        val envelope = submitSecure(
            LegacyV1SecureSubmission(
                payload = payload.copyOf(),
                contentType = policy.contentType,
                senderIdentity = senderIdentity,
                recipientNodeId = binding.nodeId,
                recipientPublicKeyBase64 = binding.recipientPublicKeyBase64,
                ttlMs = policy.lifetimeMs,
                priority = policy.priority.toLegacyV1Priority(),
            )
        )
        require(envelope.destinationNodeId == binding.nodeId.value) {
            "Legacy v1 sender returned an envelope for another node"
        }

        // The v1 packet ID backs this opaque local handle only at the bridge.
        // It is not promoted to a universal DeliveryId.
        return DeliveryHandle(
            requestId = DeliveryHandle.RequestId(envelope.packetId),
            observations = flowOf(DeliveryHandle.Observation.DurablyStoredLocally),
        )
    }
}

internal data class LegacyV1SecureSubmission(
    val payload: ByteArray,
    val contentType: String,
    val senderIdentity: MeshKeyPair,
    val recipientNodeId: NodeId,
    val recipientPublicKeyBase64: String,
    val ttlMs: Long,
    val priority: PacketPriority,
)

private fun DeliveryPolicy.Priority.toLegacyV1Priority(): PacketPriority = when (this) {
    DeliveryPolicy.Priority.LOW -> PacketPriority.LOW
    DeliveryPolicy.Priority.NORMAL -> PacketPriority.NORMAL
    DeliveryPolicy.Priority.HIGH -> PacketPriority.HIGH
    DeliveryPolicy.Priority.EMERGENCY -> PacketPriority.EMERGENCY
}
