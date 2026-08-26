package com.openmesh.core

import kotlinx.coroutines.flow.Flow
import java.net.URI

/** Logical application destination. It is never a node ID or transport address. */
@JvmInline
value class EndpointId(val value: String) {
    init {
        require(value.isNotBlank()) { "Endpoint ID must not be blank" }
        require(value == value.trim()) { "Endpoint ID must not contain surrounding whitespace" }
        require(value.encodeToByteArray().size <= MAX_BYTES) {
            "Endpoint ID exceeds $MAX_BYTES bytes"
        }

        val parsed = runCatching { URI(value) }.getOrNull()
        require(parsed != null && parsed.isAbsolute && !parsed.scheme.isNullOrBlank()) {
            "Endpoint ID must be an absolute URI with an explicit namespace"
        }
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_BYTES = 2_048
    }
}

/** Self-certifying identity of a protocol node; distinct from [EndpointId]. */
@JvmInline
value class NodeId(val value: String) {
    init {
        require(MeshNodeId.isValid(value)) { "Invalid OpenMesh node ID: $value" }
    }

    override fun toString(): String = value
}

/**
 * Application delivery intent shared by every implementation of [DeliveryService].
 *
 * Transport selection and local execution constraints deliberately do not belong
 * here. The v1 compatibility implementation supports no authenticated receipt,
 * so [DesiredEvidence.NONE] is the only evidence requirement currently exposed.
 */
data class DeliveryPolicy(
    val lifetimeMs: Long = DEFAULT_LIFETIME_MS,
    val priority: Priority = Priority.NORMAL,
    val desiredEvidence: DesiredEvidence = DesiredEvidence.NONE,
    val contentType: String = DEFAULT_CONTENT_TYPE,
) {
    init {
        require(lifetimeMs > 0) { "Delivery lifetime must be positive" }
        require(contentType.isNotBlank()) { "Delivery content type must not be blank" }
        require(contentType.encodeToByteArray().size <= MAX_CONTENT_TYPE_BYTES) {
            "Delivery content type exceeds $MAX_CONTENT_TYPE_BYTES bytes"
        }
    }

    enum class Priority {
        LOW,
        NORMAL,
        HIGH,
        EMERGENCY,
    }

    enum class DesiredEvidence {
        /** No remote receipt is requested or implied. */
        NONE,
    }

    companion object {
        const val DEFAULT_LIFETIME_MS = 72L * 60L * 60L * 1_000L
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
        private const val MAX_CONTENT_TYPE_BYTES = 8 * 1_024
    }
}

/**
 * Opaque local handle returned only after the implementation has committed the
 * request to its local durable store.
 *
 * Observations state only facts the runtime can prove. They never promote a
 * local commit or link write into next-hop acceptance or final delivery.
 */
class DeliveryHandle(
    val requestId: RequestId,
    val observations: Flow<Observation>,
) {
    @JvmInline
    value class RequestId(val value: String) {
        init {
            require(value.isNotBlank()) { "Delivery request ID must not be blank" }
            require(value.length <= MAX_CHARACTERS) {
                "Delivery request ID exceeds $MAX_CHARACTERS characters"
            }
        }

        override fun toString(): String = value

        private companion object {
            const val MAX_CHARACTERS = 256
        }
    }

    sealed interface Observation {
        /** The origin's local store committed the object; no remote delivery is implied. */
        data object DurablyStoredLocally : Observation
    }
}

/** Transport-neutral application boundary for submitting a delivery intent. */
fun interface DeliveryService {
    suspend fun deliver(
        destination: EndpointId,
        payload: ByteArray,
        policy: DeliveryPolicy,
    ): DeliveryHandle
}
