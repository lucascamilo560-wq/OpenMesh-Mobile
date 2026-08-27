package com.openmesh.core

import kotlinx.coroutines.flow.Flow

/**
 * Stable identity of one transport implementation.
 *
 * This identifies code/configuration local to the node. It is not a route,
 * peer identity, endpoint, or transport address.
 */
@JvmInline
value class TransportAdapterId(val value: String) {
    init {
        requireBoundedTransportText("Transport adapter ID", value, MAX_BYTES)
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_BYTES = 128
    }
}

/** Adapter-scoped identity of one temporary, locally observed opportunity. */
@JvmInline
value class TransportOpportunityId(val value: String) {
    init {
        requireBoundedTransportText("Transport opportunity ID", value, MAX_BYTES)
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_BYTES = 256
    }
}

/** Correlation identity created for one local invocation of [TransportAdapter.transfer]. */
@JvmInline
value class TransportTransferId(val value: String) {
    init {
        requireBoundedTransportText("Transport transfer ID", value, MAX_BYTES)
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_BYTES = 256
    }
}

/**
 * An opaque, short-lived locator understood only by its owning adapter.
 *
 * The bytes are defensively copied and deliberately have no implicit string,
 * [NodeId], or [EndpointId] representation.
 */
class TransportAddress private constructor(
    val adapterId: TransportAdapterId,
    private val value: ByteArray,
) {
    val size: Int
        get() = value.size

    fun copyToByteArray(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is TransportAddress &&
            adapterId == other.adapterId &&
            value.contentEquals(other.value)

    override fun hashCode(): Int = 31 * adapterId.hashCode() + value.contentHashCode()

    override fun toString(): String =
        "TransportAddress(adapterId=$adapterId, size=$size)"

    companion object {
        const val MAX_BYTES = 2 * 1_024

        fun copyOf(
            adapterId: TransportAdapterId,
            bytes: ByteArray,
        ): TransportAddress {
            require(bytes.isNotEmpty()) { "Transport address must not be empty" }
            require(bytes.size <= MAX_BYTES) {
                "Transport address exceeds $MAX_BYTES bytes"
            }
            return TransportAddress(adapterId, bytes.copyOf())
        }
    }
}

/** Peer knowledge is optional; an unidentified transmitter is a valid observation. */
sealed interface TransportPeer {
    data object Unknown : TransportPeer

    data class KnownNode(val nodeId: NodeId) : TransportPeer
}

/** Direction observed locally for one opportunity. Unknown never implies duplex. */
enum class TransportDirection {
    SEND_ONLY,
    RECEIVE_ONLY,
    BIDIRECTIONAL,
    UNKNOWN,
    ;

    val isSendKnown: Boolean
        get() = this == SEND_ONLY || this == BIDIRECTIONAL

    val isReceiveKnown: Boolean
        get() = this == RECEIVE_ONLY || this == BIDIRECTIONAL
}

data class TransportOpportunityKey(
    val adapterId: TransportAdapterId,
    val opportunityId: TransportOpportunityId,
)

/**
 * Temporary local fact emitted by an adapter. It is never a route.
 *
 * [validUntilMs] is exclusive and every opportunity is deliberately bounded.
 * Longer-lived predictions/contact plans require a future, explicit extension
 * instead of turning this observation into permanent routing state.
 */
data class TransportOpportunity(
    val adapterId: TransportAdapterId,
    val opportunityId: TransportOpportunityId,
    val observedAtMs: Long,
    val validUntilMs: Long,
    val direction: TransportDirection,
    val peer: TransportPeer = TransportPeer.Unknown,
    val address: TransportAddress? = null,
) {
    init {
        require(observedAtMs >= 0) { "Opportunity observation time must not be negative" }
        require(validUntilMs > observedAtMs) {
            "Opportunity validity must end after it was observed"
        }
        require(validUntilMs - observedAtMs <= MAX_VALIDITY_MS) {
            "Opportunity validity exceeds $MAX_VALIDITY_MS ms"
        }
        require(address == null || address.adapterId == adapterId) {
            "Transport address belongs to a different adapter"
        }
    }

    val key: TransportOpportunityKey
        get() = TransportOpportunityKey(adapterId, opportunityId)

    fun isFreshAt(nowMs: Long): Boolean =
        nowMs >= observedAtMs && nowMs < validUntilMs

    companion object {
        const val MAX_VALIDITY_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}

enum class TransportOpportunityUnavailableReason {
    EXPIRED,
    LOST,
    ADAPTER_STOPPED,
    REPLACED,
    UNKNOWN,
}

/** Facts emitted by an adapter. None of them is durable protocol acceptance. */
sealed interface TransportEvent {
    val adapterId: TransportAdapterId
    val occurredAtMs: Long

    data class OpportunityAvailable(
        val opportunity: TransportOpportunity,
    ) : TransportEvent {
        override val adapterId: TransportAdapterId
            get() = opportunity.adapterId
        override val occurredAtMs: Long
            get() = opportunity.observedAtMs
    }

    data class OpportunityChanged(
        val opportunity: TransportOpportunity,
    ) : TransportEvent {
        override val adapterId: TransportAdapterId
            get() = opportunity.adapterId
        override val occurredAtMs: Long
            get() = opportunity.observedAtMs
    }

    data class OpportunityUnavailable(
        override val adapterId: TransportAdapterId,
        val opportunityId: TransportOpportunityId,
        override val occurredAtMs: Long,
        val reason: TransportOpportunityUnavailableReason,
    ) : TransportEvent {
        init {
            require(occurredAtMs >= 0) { "Opportunity event time must not be negative" }
        }
    }

    /**
     * Opaque bytes observed on a transport.
     *
     * The Protocol Agent still has to parse, authenticate, admit, and persist
     * them; this event proves only that the adapter received bytes locally.
     */
    data class InboundBytes(
        override val adapterId: TransportAdapterId,
        val opportunityId: TransportOpportunityId?,
        val peer: TransportPeer,
        val sourceAddress: TransportAddress?,
        override val occurredAtMs: Long,
        val bytes: OpaqueBytes,
    ) : TransportEvent {
        init {
            require(occurredAtMs >= 0) { "Inbound event time must not be negative" }
            require(bytes.size > 0) { "Inbound transport bytes must not be empty" }
            require(bytes.size <= TransportContractLimits.MAX_OPAQUE_BYTES) {
                "Inbound transport bytes exceed ${TransportContractLimits.MAX_OPAQUE_BYTES} bytes"
            }
            require(sourceAddress == null || sourceAddress.adapterId == adapterId) {
                "Inbound transport address belongs to a different adapter"
            }
        }

        companion object {
            fun copyOf(
                adapterId: TransportAdapterId,
                opportunityId: TransportOpportunityId? = null,
                peer: TransportPeer = TransportPeer.Unknown,
                sourceAddress: TransportAddress? = null,
                occurredAtMs: Long,
                bytes: ByteArray,
            ): InboundBytes = InboundBytes(
                adapterId = adapterId,
                opportunityId = opportunityId,
                peer = peer,
                sourceAddress = sourceAddress,
                occurredAtMs = occurredAtMs,
                bytes = OpaqueBytes.copyOf(bytes),
            )
        }
    }
}

object TransportContractLimits {
    /** Aligned with the currently ratified DeliveryStore object admission bound. */
    const val MAX_OPAQUE_BYTES = 16 * 1_024 * 1_024
}

/**
 * Local request to move opaque bytes through one concrete opportunity.
 *
 * It intentionally contains no DeliveryPolicy, DeliveryId, TransferLease,
 * routing choice, copy budget, or global retry instruction. A future decision
 * layer owns the mapping from persistent attempts to [transferId].
 */
data class TransportTransferRequest(
    val transferId: TransportTransferId,
    val opportunity: TransportOpportunityKey,
    val bytes: OpaqueBytes,
) {
    init {
        require(bytes.size > 0) { "Transport transfer bytes must not be empty" }
        require(bytes.size <= TransportContractLimits.MAX_OPAQUE_BYTES) {
            "Transport transfer bytes exceed ${TransportContractLimits.MAX_OPAQUE_BYTES} bytes"
        }
    }

    companion object {
        fun copyOf(
            transferId: TransportTransferId,
            opportunity: TransportOpportunityKey,
            bytes: ByteArray,
        ): TransportTransferRequest = TransportTransferRequest(
            transferId = transferId,
            opportunity = opportunity,
            bytes = OpaqueBytes.copyOf(bytes),
        )
    }
}

enum class TransportLocalFailureCode {
    ADAPTER_STOPPED,
    PERMISSION_DENIED,
    TIMED_OUT,
    IO_ERROR,
    RESOURCE_LIMIT,
    UNSUPPORTED,
    INVALID_REQUEST,
    UNKNOWN,
}

data class TransportLocalFailure(
    val code: TransportLocalFailureCode,
    val detail: String? = null,
) {
    init {
        detail?.let {
            require(it.isNotBlank()) { "Transport failure detail must not be blank" }
            require(it.encodeToByteArray().size <= MAX_DETAIL_BYTES) {
                "Transport failure detail exceeds $MAX_DETAIL_BYTES bytes"
            }
        }
    }

    private companion object {
        const val MAX_DETAIL_BYTES = 1_024
    }
}

/**
 * Outcome of the adapter's local obligation only.
 *
 * Even [CompletedLocally] is no stronger than a transport/link completion. It
 * cannot stand for NextHopAcceptedDurably, DestinationStored, or AppDelivered.
 */
sealed interface TransportTransferResult {
    val transferId: TransportTransferId
    val occurredAtMs: Long

    data class CompletedLocally(
        override val transferId: TransportTransferId,
        override val occurredAtMs: Long,
    ) : TransportTransferResult {
        init {
            require(occurredAtMs >= 0) { "Transfer completion time must not be negative" }
        }
    }

    data class OpportunityUnavailable(
        override val transferId: TransportTransferId,
        override val occurredAtMs: Long,
        val opportunity: TransportOpportunityKey,
    ) : TransportTransferResult {
        init {
            require(occurredAtMs >= 0) { "Transfer result time must not be negative" }
        }
    }

    data class FailedLocally(
        override val transferId: TransportTransferId,
        override val occurredAtMs: Long,
        val failure: TransportLocalFailure,
    ) : TransportTransferResult {
        init {
            require(occurredAtMs >= 0) { "Transfer failure time must not be negative" }
        }
    }
}

/**
 * Platform-neutral boundary implemented by BLE, Wi-Fi, radio, Internet,
 * satellite, or any future byte-moving mechanism outside mesh-core.
 *
 * Implementations observe local facts and move bytes. They never select a
 * route/object/transport, receive application policy, or promote delivery
 * evidence. Lifecycle operations must be idempotent; one adapter's failure is
 * local to its own caller and does not define global delivery state.
 */
interface TransportAdapter {
    val adapterId: TransportAdapterId
    val events: Flow<TransportEvent>

    suspend fun start()

    suspend fun stop()

    suspend fun transfer(request: TransportTransferRequest): TransportTransferResult
}

/** Immutable registration snapshot. An empty OpenMesh node is explicitly valid. */
class TransportAdapterSet private constructor(
    private val adaptersById: Map<TransportAdapterId, TransportAdapter>,
) : Iterable<TransportAdapter> {
    val size: Int
        get() = adaptersById.size

    val isEmpty: Boolean
        get() = adaptersById.isEmpty()

    operator fun get(adapterId: TransportAdapterId): TransportAdapter? =
        adaptersById[adapterId]

    override fun iterator(): Iterator<TransportAdapter> =
        adaptersById.values.iterator()

    companion object {
        val Empty: TransportAdapterSet = TransportAdapterSet(emptyMap())

        fun copyOf(adapters: Iterable<TransportAdapter>): TransportAdapterSet {
            val indexed = linkedMapOf<TransportAdapterId, TransportAdapter>()
            adapters.forEach { adapter ->
                require(indexed.put(adapter.adapterId, adapter) == null) {
                    "Duplicate transport adapter ID: ${adapter.adapterId}"
                }
            }
            return if (indexed.isEmpty()) Empty else TransportAdapterSet(indexed.toMap())
        }
    }
}

private fun requireBoundedTransportText(
    label: String,
    value: String,
    maxBytes: Int,
) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value == value.trim()) { "$label must not contain surrounding whitespace" }
    require(value.none(Char::isISOControl)) { "$label must not contain control characters" }
    require(value.encodeToByteArray().size <= maxBytes) { "$label exceeds $maxBytes bytes" }
}
