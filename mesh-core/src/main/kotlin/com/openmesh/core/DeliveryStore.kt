package com.openmesh.core

import kotlinx.coroutines.flow.Flow

/**
 * Opaque canonical identity supplied by the protocol profile.
 *
 * This type deliberately does not choose a canonicalization or digest algorithm;
 * that remains an ADR-007 decision. A store must nevertheless reject two
 * different canonical objects presented under the same identity.
 */
@JvmInline
value class DeliveryId(val value: String) {
    init {
        requireBoundedStoreText("Delivery ID", value, MAX_BYTES)
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_BYTES = 512
    }
}

@JvmInline
value class TransferAttemptId(val value: String) {
    init {
        requireBoundedStoreText("Transfer attempt ID", value, 256)
    }

    override fun toString(): String = value
}

@JvmInline
value class ReceiptId(val value: String) {
    init {
        requireBoundedStoreText("Receipt ID", value, 512)
    }

    override fun toString(): String = value
}

@JvmInline
value class AcceptanceEvidenceId(val value: String) {
    init {
        requireBoundedStoreText("Acceptance evidence ID", value, 1_024)
    }

    override fun toString(): String = value
}

@JvmInline
value class OutboxEventId(val value: String) {
    init {
        requireBoundedStoreText("Outbox event ID", value, 256)
    }

    override fun toString(): String = value
}

/** Immutable, defensive-copy wrapper for opaque protocol bytes. */
class OpaqueBytes private constructor(private val value: ByteArray) {
    val size: Int
        get() = value.size

    fun copyToByteArray(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean =
        other is OpaqueBytes && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "OpaqueBytes(size=$size)"

    companion object {
        fun copyOf(bytes: ByteArray): OpaqueBytes = OpaqueBytes(bytes.copyOf())
    }
}

/** Protocol-owned immutable object. Relays and the local store cannot rewrite it. */
data class DeliveryObject(
    val deliveryId: DeliveryId,
    val destination: EndpointId,
    val source: EndpointId?,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val canonicalBytes: OpaqueBytes,
) {
    init {
        require(expiresAtMs > createdAtMs) {
            "Delivery object expiry must be later than creation"
        }
        require(canonicalBytes.size > 0) { "Delivery object bytes must not be empty" }
    }

    companion object {
        fun copyOf(
            deliveryId: DeliveryId,
            destination: EndpointId,
            source: EndpointId? = null,
            createdAtMs: Long,
            expiresAtMs: Long,
            canonicalBytes: ByteArray,
        ): DeliveryObject = DeliveryObject(
            deliveryId = deliveryId,
            destination = destination,
            source = source,
            createdAtMs = createdAtMs,
            expiresAtMs = expiresAtMs,
            canonicalBytes = OpaqueBytes.copyOf(canonicalBytes),
        )
    }
}

data class IngressProvenance(
    val kind: Kind,
    val reference: String? = null,
) {
    init {
        reference?.let { requireBoundedStoreText("Ingress reference", it, 512) }
    }

    enum class Kind {
        LOCAL_APPLICATION,
        REMOTE_PROTOCOL,
        LEGACY_IMPORT,
    }
}

data class DeliveryIngest(
    val deliveryObject: DeliveryObject,
    val destinationIsLocal: Boolean,
    val provenance: IngressProvenance,
)

enum class DeliveryState {
    WAITING,
    APP_PENDING,
    APP_DELIVERED,
    EXPIRED,
    QUARANTINED,
}

/** Local lifecycle only. Object bytes, attempts and receipts are separate aggregates. */
data class DeliveryRecord(
    val deliveryId: DeliveryId,
    val state: DeliveryState,
    val storedAtMs: Long,
    val updatedAtMs: Long,
    val version: Long,
    val provenance: Set<IngressProvenance>,
) {
    init {
        require(version > 0) { "Delivery record version must be positive" }
        require(provenance.isNotEmpty()) { "Delivery record requires provenance" }
    }
}

enum class InboxState {
    PENDING,
    DELIVERED,
    EXPIRED,
}

data class InboxRecord(
    val deliveryId: DeliveryId,
    val state: InboxState,
    val enqueuedAtMs: Long,
    val updatedAtMs: Long,
)

data class TransferContext(
    /** Opaque local reference only; this is not a Transport SPI contract. */
    val adapterId: String,
    /** Opaque local reference only; this does not define the Opportunity model. */
    val opportunityId: String,
    /** Null means the exact opportunity revision was not recorded by legacy code. */
    val opportunityRevision: Long? = null,
) {
    init {
        requireBoundedStoreText("Adapter reference", adapterId, 256)
        requireBoundedStoreText("Opportunity reference", opportunityId, 256)
        require(opportunityRevision == null || opportunityRevision > 0) {
            "Opportunity revision must be positive when recorded"
        }
    }

    /** Reconstructs an exact SPI reference only when every revisioned fact is valid. */
    fun exactOpportunityReferenceOrNull(): TransportOpportunityReference? {
        val revision = opportunityRevision ?: return null
        return try {
            TransportOpportunityReference(
                key = TransportOpportunityKey(
                    adapterId = TransportAdapterId(adapterId),
                    opportunityId = TransportOpportunityId(opportunityId),
                ),
                revision = TransportOpportunityRevision(revision),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        fun forOpportunity(reference: TransportOpportunityReference): TransferContext =
            TransferContext(
                adapterId = reference.adapterId.value,
                opportunityId = reference.opportunityId.value,
                opportunityRevision = reference.revision.value,
            )
    }
}

enum class TransferAttemptState {
    RESERVED,
    TRANSFERRING,
    LINK_WRITE_COMPLETED,
    NEXT_HOP_ACCEPTED_DURABLY,
    FAILED,
    LEASE_EXPIRED,
    CANCELLED,
}

/** A transfer has its own lifecycle and can never stand in for delivery state. */
data class TransferAttempt(
    val attemptId: TransferAttemptId,
    val deliveryId: DeliveryId,
    val context: TransferContext,
    val state: TransferAttemptState,
    val reservedAtMs: Long,
    val leaseExpiresAtMs: Long,
    val startedAtMs: Long? = null,
    val linkWriteCompletedAtMs: Long? = null,
    val nextHopAcceptedDurablyAtMs: Long? = null,
    val nextHopAcceptanceEvidenceId: AcceptanceEvidenceId? = null,
    val leaseExpiredAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val failureReason: String? = null,
)

/** Local-only authority token. Its value is intentionally not exposed. */
class TransferLease internal constructor(
    val attemptId: TransferAttemptId,
    val expiresAtMs: Long,
    internal val ownerToken: String,
)

data class TransferReservation(
    val deliveryId: DeliveryId,
    val context: TransferContext,
    val leaseDurationMs: Long,
) {
    init {
        require(leaseDurationMs > 0) { "Transfer lease duration must be positive" }
    }
}

sealed interface TransferReservationResult {
    data class Acquired(
        val lease: TransferLease,
        val attempt: TransferAttempt,
    ) : TransferReservationResult

    data class Busy(val attempt: TransferAttempt) : TransferReservationResult

    data object NotEligible : TransferReservationResult
}

enum class ReceiptEvidence {
    NEXT_HOP_ACCEPTED_DURABLY,
    DESTINATION_STORED,
    APP_DELIVERED,
}

enum class ReceiptVerification {
    UNVERIFIED,
    INVALID,
    AUTHENTICATED,
    AUTHENTICATED_AND_AUTHORIZED,
}

/** Input already parsed and assessed by a protocol/security layer; no wire is defined here. */
data class ReceiptInput(
    val receiptId: ReceiptId,
    val deliveryId: DeliveryId,
    val evidence: ReceiptEvidence,
    val issuer: String,
    val verification: ReceiptVerification,
    val protectedBytes: OpaqueBytes,
    val linkedTransferAttemptId: TransferAttemptId? = null,
) {
    init {
        requireBoundedStoreText("Receipt issuer", issuer, 512)
        require(protectedBytes.size > 0) { "Receipt bytes must not be empty" }
    }

    companion object {
        fun copyOf(
            receiptId: ReceiptId,
            deliveryId: DeliveryId,
            evidence: ReceiptEvidence,
            issuer: String,
            verification: ReceiptVerification,
            protectedBytes: ByteArray,
            linkedTransferAttemptId: TransferAttemptId? = null,
        ): ReceiptInput = ReceiptInput(
            receiptId = receiptId,
            deliveryId = deliveryId,
            evidence = evidence,
            issuer = issuer,
            verification = verification,
            protectedBytes = OpaqueBytes.copyOf(protectedBytes),
            linkedTransferAttemptId = linkedTransferAttemptId,
        )
    }
}

data class ReceiptRecord(
    val receipt: ReceiptInput,
    val storedAtMs: Long,
    val appliedAtMs: Long?,
)

enum class NextHopAcceptanceEvidenceKind {
    AUTHENTICATED_SESSION,
    AUTHENTICATED_RECEIPT,
}

/**
 * Proof-carrying authority to record next-hop durable acceptance.
 *
 * The constructor and factories are internal so transport modules cannot mint
 * this authority from a link callback or [TransferLease]. A Protocol/Security
 * Agent may create it only after authenticating the peer/issuer, authorizing it
 * for this hop, and binding the evidence to the object and transfer attempt.
 * Cryptographic wire verification remains outside this store contract.
 */
class VerifiedNextHopAcceptance private constructor(
    val deliveryId: DeliveryId,
    val transferAttemptId: TransferAttemptId,
    val evidenceId: AcceptanceEvidenceId,
    val authority: String,
    val kind: NextHopAcceptanceEvidenceKind,
    val protectedBytes: OpaqueBytes,
    internal val receipt: ReceiptInput?,
) {
    init {
        requireBoundedStoreText("Next-hop acceptance authority", authority, 512)
        require(protectedBytes.size > 0) { "Acceptance evidence bytes must not be empty" }
    }

    companion object {
        /** Protocol/Security Agent entry after authenticated session verification. */
        internal fun fromAuthenticatedSession(
            deliveryId: DeliveryId,
            transferAttemptId: TransferAttemptId,
            evidenceId: AcceptanceEvidenceId,
            authority: String,
            protectedBytes: ByteArray,
        ): VerifiedNextHopAcceptance = VerifiedNextHopAcceptance(
            deliveryId = deliveryId,
            transferAttemptId = transferAttemptId,
            evidenceId = evidenceId,
            authority = authority,
            kind = NextHopAcceptanceEvidenceKind.AUTHENTICATED_SESSION,
            protectedBytes = OpaqueBytes.copyOf(protectedBytes),
            receipt = null,
        )

        /** Protocol/Security Agent entry after receipt authentication and authorization. */
        internal fun fromAuthenticatedReceipt(
            receipt: ReceiptInput,
        ): VerifiedNextHopAcceptance {
            require(receipt.evidence == ReceiptEvidence.NEXT_HOP_ACCEPTED_DURABLY) {
                "Receipt does not prove next-hop durable acceptance"
            }
            require(
                receipt.verification == ReceiptVerification.AUTHENTICATED_AND_AUTHORIZED
            ) { "Receipt is not authenticated and authorized for next-hop acceptance" }
            val attemptId = requireNotNull(receipt.linkedTransferAttemptId) {
                "Next-hop receipt must identify its transfer attempt"
            }
            return VerifiedNextHopAcceptance(
                deliveryId = receipt.deliveryId,
                transferAttemptId = attemptId,
                evidenceId = AcceptanceEvidenceId("receipt:${receipt.receiptId.value}"),
                authority = receipt.issuer,
                kind = NextHopAcceptanceEvidenceKind.AUTHENTICATED_RECEIPT,
                protectedBytes = receipt.protectedBytes,
                receipt = receipt,
            )
        }
    }
}

/** Persisted evidence behind one next-hop durable-acceptance fact. */
data class NextHopAcceptanceRecord(
    val deliveryId: DeliveryId,
    val transferAttemptId: TransferAttemptId,
    val evidenceId: AcceptanceEvidenceId,
    val authority: String,
    val kind: NextHopAcceptanceEvidenceKind,
    val protectedBytes: OpaqueBytes,
    val receiptId: ReceiptId?,
    val storedAtMs: Long,
)

sealed interface NextHopAcceptanceWriteResult {
    val record: NextHopAcceptanceRecord
    val attempt: TransferAttempt

    data class Stored(
        override val record: NextHopAcceptanceRecord,
        override val attempt: TransferAttempt,
    ) : NextHopAcceptanceWriteResult

    data class Duplicate(
        override val record: NextHopAcceptanceRecord,
        override val attempt: TransferAttempt,
    ) : NextHopAcceptanceWriteResult
}

enum class TombstoneReason {
    EXPIRED,
    EXPLICITLY_REMOVED,
}

data class Tombstone(
    val deliveryId: DeliveryId,
    val reason: TombstoneReason,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    init {
        require(expiresAtMs > createdAtMs) { "Tombstone expiry must be later than creation" }
    }
}

sealed interface DeliveryStoreEvent {
    val deliveryId: DeliveryId

    data class DurablyStored(
        override val deliveryId: DeliveryId,
    ) : DeliveryStoreEvent

    data class ApplicationDeliveryAvailable(
        override val deliveryId: DeliveryId,
    ) : DeliveryStoreEvent

    data class ApplicationDelivered(
        override val deliveryId: DeliveryId,
    ) : DeliveryStoreEvent

    data class ReceiptRecorded(
        override val deliveryId: DeliveryId,
        val receiptId: ReceiptId,
    ) : DeliveryStoreEvent

    data class NextHopAcceptedDurably(
        override val deliveryId: DeliveryId,
        val transferAttemptId: TransferAttemptId,
        val evidenceId: AcceptanceEvidenceId,
    ) : DeliveryStoreEvent

    data class DeliveryExpired(
        override val deliveryId: DeliveryId,
    ) : DeliveryStoreEvent
}

data class OutboxRecord(
    val sequence: Long,
    val eventId: OutboxEventId,
    val event: DeliveryStoreEvent,
    val committedAtMs: Long,
    val acknowledgedAtMs: Long? = null,
)

sealed interface DeliveryIngestResult {
    val record: DeliveryRecord?

    data class Stored(
        override val record: DeliveryRecord,
        val committedEvents: List<OutboxRecord>,
    ) : DeliveryIngestResult

    data class Duplicate(
        override val record: DeliveryRecord,
        val provenanceAdded: Boolean,
    ) : DeliveryIngestResult

    data class RejectedByTombstone(
        val tombstone: Tombstone,
    ) : DeliveryIngestResult {
        override val record: DeliveryRecord? = null
    }
}

sealed interface ReceiptWriteResult {
    val record: ReceiptRecord

    data class Stored(override val record: ReceiptRecord) : ReceiptWriteResult
    data class Duplicate(override val record: ReceiptRecord) : ReceiptWriteResult
}

data class DeliverySnapshot(
    val deliveryObject: DeliveryObject?,
    val deliveryRecord: DeliveryRecord?,
    val transferAttempts: List<TransferAttempt>,
    val nextHopAcceptances: List<NextHopAcceptanceRecord>,
    val receipts: List<ReceiptRecord>,
    val inboxRecord: InboxRecord?,
    val tombstone: Tombstone?,
)

data class DeliveryStoreLimits(
    /** Includes active records and retained tombstone-backed records. */
    val maxDeliveryRecords: Int = 10_000,
    val maxObjectBytes: Int = 16 * 1024 * 1024,
    val maxReceiptBytes: Int = 64 * 1024,
    val maxReceiptsPerDelivery: Int = 256,
    val maxAcceptanceEvidenceBytes: Int = 64 * 1024,
    val maxAcceptancesPerDelivery: Int = 256,
    val maxTransferAttemptsPerDelivery: Int = 1_024,
    val maxProvenanceEntriesPerDelivery: Int = 64,
    val maxOutboxRecords: Int = 50_000,
    val maxOutboxReadBatch: Int = 1_000,
) {
    init {
        require(maxDeliveryRecords > 0)
        require(maxObjectBytes > 0)
        require(maxReceiptBytes > 0)
        require(maxReceiptsPerDelivery > 0)
        require(maxAcceptanceEvidenceBytes > 0)
        require(maxAcceptancesPerDelivery > 0)
        require(maxTransferAttemptsPerDelivery > 0)
        require(maxProvenanceEntriesPerDelivery > 0)
        require(maxOutboxRecords > 0)
        require(maxOutboxReadBatch > 0)
    }
}

/**
 * Local authority over delivery truth.
 *
 * Every mutating operation is atomic. Returned success and [postCommitEvents]
 * are observable only after object/state/outbox commit. The flow is a wake-up
 * hint; consumers use [listOutbox] as the durable source of truth.
 */
interface DeliveryStore {
    val postCommitEvents: Flow<OutboxRecord>

    suspend fun ingest(ingest: DeliveryIngest, nowMs: Long): DeliveryIngestResult

    suspend fun snapshot(deliveryId: DeliveryId): DeliverySnapshot

    suspend fun reserveTransfer(
        reservation: TransferReservation,
        nowMs: Long,
    ): TransferReservationResult

    suspend fun markTransferStarted(lease: TransferLease, nowMs: Long): TransferAttempt

    suspend fun recordLinkWriteCompleted(lease: TransferLease, nowMs: Long): TransferAttempt

    /**
     * Records durable acceptance only from Protocol/Security-verified evidence.
     * A lease, link write, adapter callback, or unauthenticated receipt is never
     * sufficient authority for this transition.
     */
    suspend fun recordNextHopAcceptedDurably(
        acceptance: VerifiedNextHopAcceptance,
        nowMs: Long,
    ): NextHopAcceptanceWriteResult

    suspend fun recordTransferFailure(
        lease: TransferLease,
        reason: String,
        nowMs: Long,
    ): TransferAttempt

    suspend fun recordReceipt(receipt: ReceiptInput, nowMs: Long): ReceiptWriteResult

    /** Caller invokes this only after its application acceptance contract succeeds. */
    suspend fun acknowledgeApplicationDelivery(
        deliveryId: DeliveryId,
        nowMs: Long,
    ): DeliveryRecord

    suspend fun expireObjects(nowMs: Long, tombstoneRetentionMs: Long): Int

    suspend fun pruneExpiredTombstones(nowMs: Long, limit: Int): Int

    suspend fun listOutbox(
        afterSequenceExclusive: Long = 0,
        limit: Int = 100,
    ): List<OutboxRecord>

    suspend fun acknowledgeOutbox(eventId: OutboxEventId, nowMs: Long): OutboxRecord

    suspend fun pruneAcknowledgedOutbox(beforeOrAtMs: Long, limit: Int): Int
}

class DeliveryStoreAdmissionException(message: String) : IllegalStateException(message)

class CanonicalDeliveryConflictException(deliveryId: DeliveryId) : IllegalStateException(
    "Canonical delivery identity conflict for $deliveryId",
)

class ReceiptIdentityConflictException(receiptId: ReceiptId) : IllegalStateException(
    "Receipt identity conflict for $receiptId",
)

class AcceptanceEvidenceIdentityConflictException(
    evidenceId: AcceptanceEvidenceId,
) : IllegalStateException("Acceptance evidence identity conflict for $evidenceId")

class StaleTransferLeaseException(attemptId: TransferAttemptId) : IllegalStateException(
    "Stale or expired lease for transfer attempt $attemptId",
)

class InvalidDeliveryTransitionException(message: String) : IllegalStateException(message)

private fun requireBoundedStoreText(label: String, value: String, maxBytes: Int) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value == value.trim()) { "$label must not contain surrounding whitespace" }
    require(value.encodeToByteArray().size <= maxBytes) { "$label exceeds $maxBytes bytes" }
}
