package com.openmesh.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Reference model for [DeliveryStore] semantics.
 *
 * This implementation is intentionally internal and non-durable. It exists to
 * make the transactional contract executable before an Android database owns
 * production state. Mutations are copy-on-write and become visible as one commit.
 */
internal class InMemoryDeliveryStore(
    private val limits: DeliveryStoreLimits = DeliveryStoreLimits(),
    private val beforeCommit: (CommitKind) -> Unit = {},
    private val leaseTokenSource: () -> String = { UUID.randomUUID().toString() },
    initialState: State = State(),
) : DeliveryStore {
    private val mutex = Mutex()
    private var state: State = initialState
    private val mutablePostCommitEvents = MutableSharedFlow<OutboxRecord>(
        replay = 0,
        extraBufferCapacity = POST_COMMIT_HINT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val postCommitEvents: Flow<OutboxRecord> = mutablePostCommitEvents.asSharedFlow()

    override suspend fun ingest(
        ingest: DeliveryIngest,
        nowMs: Long,
    ): DeliveryIngestResult = commit(CommitKind.INGEST) { original ->
        val incoming = ingest.deliveryObject
        require(incoming.expiresAtMs > nowMs) { "Cannot ingest an already expired object" }
        ensureObjectWithinLimits(incoming)

        val blockingTombstone = original.tombstones[incoming.deliveryId]
            ?.takeIf { it.expiresAtMs > nowMs }
        if (blockingTombstone != null) {
            return@commit Mutation.unchanged(
                DeliveryIngestResult.RejectedByTombstone(blockingTombstone)
            )
        }

        val base = if (original.tombstones.containsKey(incoming.deliveryId)) {
            original.withDeliveryRemoved(incoming.deliveryId)
        } else {
            original
        }
        val existingObject = base.objects[incoming.deliveryId]
        if (existingObject != null) {
            if (existingObject != incoming) {
                throw CanonicalDeliveryConflictException(incoming.deliveryId)
            }
            val existingRecord = checkNotNull(base.records[incoming.deliveryId])
            val provenanceAdded = ingest.provenance !in existingRecord.provenance
            if (!provenanceAdded) {
                return@commit Mutation.unchanged(
                    DeliveryIngestResult.Duplicate(existingRecord, provenanceAdded = false)
                )
            }
            if (existingRecord.provenance.size >= limits.maxProvenanceEntriesPerDelivery) {
                throw DeliveryStoreAdmissionException(
                    "Provenance limit reached for ${incoming.deliveryId}"
                )
            }
            val reconciled = existingRecord.copy(
                updatedAtMs = nowMs,
                version = existingRecord.version + 1,
                provenance = existingRecord.provenance + ingest.provenance,
            )
            return@commit Mutation.changed(
                state = base.copy(records = base.records + (incoming.deliveryId to reconciled)),
                value = DeliveryIngestResult.Duplicate(reconciled, provenanceAdded = true),
            )
        }

        if (base.records.size >= limits.maxDeliveryRecords) {
            throw DeliveryStoreAdmissionException("Delivery record limit reached")
        }

        val deliveryState = if (ingest.destinationIsLocal) {
            DeliveryState.APP_PENDING
        } else {
            DeliveryState.WAITING
        }
        val record = DeliveryRecord(
            deliveryId = incoming.deliveryId,
            state = deliveryState,
            storedAtMs = nowMs,
            updatedAtMs = nowMs,
            version = 1,
            provenance = setOf(ingest.provenance),
        )
        var next = base.copy(
            objects = base.objects + (incoming.deliveryId to incoming),
            records = base.records + (incoming.deliveryId to record),
            inbox = if (ingest.destinationIsLocal) {
                base.inbox + (
                    incoming.deliveryId to InboxRecord(
                        deliveryId = incoming.deliveryId,
                        state = InboxState.PENDING,
                        enqueuedAtMs = nowMs,
                        updatedAtMs = nowMs,
                    )
                )
            } else {
                base.inbox
            },
        )
        val events = buildList {
            add(DeliveryStoreEvent.DurablyStored(incoming.deliveryId))
            if (ingest.destinationIsLocal) {
                add(DeliveryStoreEvent.ApplicationDeliveryAvailable(incoming.deliveryId))
            }
        }
        val appended = appendOutbox(next, events, nowMs)
        next = appended.state

        Mutation.changed(
            state = next,
            value = DeliveryIngestResult.Stored(record, appended.records),
            events = appended.records,
        )
    }

    override suspend fun snapshot(deliveryId: DeliveryId): DeliverySnapshot = mutex.withLock {
        state.snapshot(deliveryId)
    }

    override suspend fun reserveTransfer(
        reservation: TransferReservation,
        nowMs: Long,
    ): TransferReservationResult = commit(CommitKind.RESERVE_TRANSFER) { original ->
        val delivery = original.objects[reservation.deliveryId]
        val record = original.records[reservation.deliveryId]
        if (
            delivery == null ||
            record?.state != DeliveryState.WAITING ||
            delivery.expiresAtMs <= nowMs ||
            original.tombstones.containsKey(reservation.deliveryId)
        ) {
            return@commit Mutation.unchanged(TransferReservationResult.NotEligible)
        }

        var attempts = original.attempts
        var changed = false
        original.attempts.values
            .filter {
                it.deliveryId == reservation.deliveryId &&
                    it.state in ACTIVE_ATTEMPT_STATES &&
                    it.leaseExpiresAtMs <= nowMs
            }
            .forEach { expired ->
                attempts = attempts + (
                    expired.attemptId to expired.copy(
                        state = TransferAttemptState.LEASE_EXPIRED,
                        leaseExpiredAtMs = nowMs,
                        finishedAtMs = nowMs,
                    )
                )
                changed = true
            }

        val busy = attempts.values.firstOrNull {
            it.deliveryId == reservation.deliveryId &&
                it.context == reservation.context &&
                it.state in ACTIVE_ATTEMPT_STATES &&
                it.leaseExpiresAtMs > nowMs
        }
        if (busy != null) {
            val next = if (changed) original.copy(attempts = attempts) else original
            val value = TransferReservationResult.Busy(busy)
            return@commit if (changed) {
                Mutation.changed(state = next, value = value)
            } else {
                Mutation.unchanged(value)
            }
        }

        val attemptCount = attempts.values.count { it.deliveryId == reservation.deliveryId }
        if (attemptCount >= limits.maxTransferAttemptsPerDelivery) {
            throw DeliveryStoreAdmissionException(
                "Transfer attempt limit reached for ${reservation.deliveryId}"
            )
        }

        val leaseExpiresAtMs = safeAdd(nowMs, reservation.leaseDurationMs)
        val attemptId = TransferAttemptId("local-attempt-${original.nextAttemptSequence}")
        val attempt = TransferAttempt(
            attemptId = attemptId,
            deliveryId = reservation.deliveryId,
            context = reservation.context,
            state = TransferAttemptState.RESERVED,
            reservedAtMs = nowMs,
            leaseExpiresAtMs = leaseExpiresAtMs,
        )
        val ownerToken = leaseTokenSource()
        require(ownerToken.isNotBlank()) { "Lease token source returned a blank token" }
        val next = original.copy(
            attempts = attempts + (attemptId to attempt),
            leaseOwners = original.leaseOwners + (attemptId to ownerToken),
            nextAttemptSequence = original.nextAttemptSequence + 1,
        )
        Mutation.changed(
            state = next,
            value = TransferReservationResult.Acquired(
                lease = TransferLease(attemptId, leaseExpiresAtMs, ownerToken),
                attempt = attempt,
            ),
        )
    }

    override suspend fun markTransferStarted(
        lease: TransferLease,
        nowMs: Long,
    ): TransferAttempt = updateAttempt(
        kind = CommitKind.UPDATE_TRANSFER,
        lease = lease,
        nowMs = nowMs,
        permittedStates = setOf(TransferAttemptState.RESERVED),
    ) { attempt ->
        attempt.copy(
            state = TransferAttemptState.TRANSFERRING,
            startedAtMs = nowMs,
        )
    }

    override suspend fun recordLinkWriteCompleted(
        lease: TransferLease,
        nowMs: Long,
    ): TransferAttempt = updateAttempt(
        kind = CommitKind.UPDATE_TRANSFER,
        lease = lease,
        nowMs = nowMs,
        permittedStates = setOf(TransferAttemptState.TRANSFERRING),
    ) { attempt ->
        attempt.copy(
            state = TransferAttemptState.LINK_WRITE_COMPLETED,
            linkWriteCompletedAtMs = nowMs,
        )
    }

    override suspend fun recordNextHopAccepted(
        lease: TransferLease,
        nowMs: Long,
    ): TransferAttempt = updateAttempt(
        kind = CommitKind.UPDATE_TRANSFER,
        lease = lease,
        nowMs = nowMs,
        permittedStates = setOf(
            TransferAttemptState.TRANSFERRING,
            TransferAttemptState.LINK_WRITE_COMPLETED,
        ),
    ) { attempt ->
        attempt.copy(
            state = TransferAttemptState.NEXT_HOP_ACCEPTED,
            nextHopAcceptedAtMs = nowMs,
            finishedAtMs = nowMs,
        )
    }

    override suspend fun recordTransferFailure(
        lease: TransferLease,
        reason: String,
        nowMs: Long,
    ): TransferAttempt {
        requireBoundedInternalText("Transfer failure reason", reason, 1_024)
        return updateAttempt(
            kind = CommitKind.UPDATE_TRANSFER,
            lease = lease,
            nowMs = nowMs,
            permittedStates = ACTIVE_ATTEMPT_STATES,
        ) { attempt ->
            attempt.copy(
                state = TransferAttemptState.FAILED,
                finishedAtMs = nowMs,
                failureReason = reason,
            )
        }
    }

    override suspend fun recordReceipt(
        receipt: ReceiptInput,
        nowMs: Long,
    ): ReceiptWriteResult = commit(CommitKind.RECORD_RECEIPT) { original ->
        val deliveryRecord = original.records[receipt.deliveryId]
            ?: throw InvalidDeliveryTransitionException(
                "Cannot attach receipt to unknown delivery ${receipt.deliveryId}"
            )
        if (receipt.protectedBytes.size > limits.maxReceiptBytes) {
            throw DeliveryStoreAdmissionException(
                "Receipt exceeds ${limits.maxReceiptBytes} bytes"
            )
        }

        val existing = original.receipts[receipt.receiptId]
        if (existing != null) {
            if (existing.receipt != receipt) {
                throw ReceiptIdentityConflictException(receipt.receiptId)
            }
            return@commit Mutation.unchanged(ReceiptWriteResult.Duplicate(existing))
        }
        val receiptCount = original.receipts.values.count {
            it.receipt.deliveryId == receipt.deliveryId
        }
        if (receiptCount >= limits.maxReceiptsPerDelivery) {
            throw DeliveryStoreAdmissionException(
                "Receipt limit reached for ${receipt.deliveryId}"
            )
        }

        val linkedAttempt = receipt.linkedTransferAttemptId?.let { attemptId ->
            original.attempts[attemptId]
                ?: throw InvalidDeliveryTransitionException(
                    "Receipt references unknown transfer attempt $attemptId"
                )
        }
        if (linkedAttempt != null && linkedAttempt.deliveryId != receipt.deliveryId) {
            throw InvalidDeliveryTransitionException(
                "Receipt and transfer attempt belong to different deliveries"
            )
        }

        val authorized = receipt.verification ==
            ReceiptVerification.AUTHENTICATED_AND_AUTHORIZED
        val stored = ReceiptRecord(
            receipt = receipt,
            storedAtMs = nowMs,
            appliedAtMs = nowMs.takeIf { authorized },
        )
        var next = original.copy(
            receipts = original.receipts + (receipt.receiptId to stored),
        )

        if (
            authorized &&
            receipt.evidence == ReceiptEvidence.NEXT_HOP_ACCEPTED_DURABLY &&
            linkedAttempt != null
        ) {
            next = next.copy(
                attempts = next.attempts + (
                    linkedAttempt.attemptId to linkedAttempt.copy(
                        state = TransferAttemptState.NEXT_HOP_ACCEPTED,
                        nextHopAcceptedAtMs = nowMs,
                        finishedAtMs = nowMs,
                    )
                )
            )
        }

        if (authorized && receipt.evidence == ReceiptEvidence.APP_DELIVERED) {
            next = next.copy(
                records = next.records + (
                    receipt.deliveryId to deliveryRecord.copy(
                        state = DeliveryState.APP_DELIVERED,
                        updatedAtMs = nowMs,
                        version = deliveryRecord.version + 1,
                    )
                ),
                inbox = next.inbox[receipt.deliveryId]?.let { inbox ->
                    next.inbox + (
                        receipt.deliveryId to inbox.copy(
                            state = InboxState.DELIVERED,
                            updatedAtMs = nowMs,
                        )
                    )
                } ?: next.inbox,
            )
        }

        val appended = appendOutbox(
            next,
            events = listOf(
                DeliveryStoreEvent.ReceiptRecorded(
                    deliveryId = receipt.deliveryId,
                    receiptId = receipt.receiptId,
                )
            ),
            nowMs = nowMs,
        )
        Mutation.changed(
            state = appended.state,
            value = ReceiptWriteResult.Stored(stored),
            events = appended.records,
        )
    }

    override suspend fun acknowledgeApplicationDelivery(
        deliveryId: DeliveryId,
        nowMs: Long,
    ): DeliveryRecord = commit(CommitKind.ACKNOWLEDGE_APPLICATION) { original ->
        val current = original.records[deliveryId]
            ?: throw InvalidDeliveryTransitionException("Unknown delivery $deliveryId")
        if (current.state == DeliveryState.APP_DELIVERED) {
            return@commit Mutation.unchanged(current)
        }
        if (current.state != DeliveryState.APP_PENDING) {
            throw InvalidDeliveryTransitionException(
                "Delivery $deliveryId is ${current.state}, not APP_PENDING"
            )
        }
        val inbox = original.inbox[deliveryId]
            ?: throw InvalidDeliveryTransitionException("Delivery $deliveryId has no inbox record")
        val delivered = current.copy(
            state = DeliveryState.APP_DELIVERED,
            updatedAtMs = nowMs,
            version = current.version + 1,
        )
        val next = original.copy(
            records = original.records + (deliveryId to delivered),
            inbox = original.inbox + (
                deliveryId to inbox.copy(
                    state = InboxState.DELIVERED,
                    updatedAtMs = nowMs,
                )
            ),
        )
        val appended = appendOutbox(
            next,
            listOf(DeliveryStoreEvent.ApplicationDelivered(deliveryId)),
            nowMs,
        )
        Mutation.changed(
            state = appended.state,
            value = delivered,
            events = appended.records,
        )
    }

    override suspend fun expireObjects(
        nowMs: Long,
        tombstoneRetentionMs: Long,
    ): Int = commit(CommitKind.EXPIRE) { original ->
        require(tombstoneRetentionMs > 0) { "Tombstone retention must be positive" }
        val expiring = original.objects.values
            .filter { it.expiresAtMs <= nowMs }
            .sortedBy { it.deliveryId.value }
        if (expiring.isEmpty()) return@commit Mutation.unchanged(0)

        var next = original
        expiring.forEach { delivery ->
            val currentRecord = checkNotNull(next.records[delivery.deliveryId])
            val attempts = next.attempts.mapValues { (_, attempt) ->
                if (
                    attempt.deliveryId == delivery.deliveryId &&
                    attempt.state in ACTIVE_ATTEMPT_STATES
                ) {
                    attempt.copy(
                        state = TransferAttemptState.CANCELLED,
                        finishedAtMs = nowMs,
                        failureReason = "delivery expired",
                    )
                } else {
                    attempt
                }
            }
            next = next.copy(
                objects = next.objects - delivery.deliveryId,
                records = next.records + (
                    delivery.deliveryId to currentRecord.copy(
                        state = DeliveryState.EXPIRED,
                        updatedAtMs = nowMs,
                        version = currentRecord.version + 1,
                    )
                ),
                attempts = attempts,
                inbox = next.inbox[delivery.deliveryId]?.let { inbox ->
                    next.inbox + (
                        delivery.deliveryId to inbox.copy(
                            state = InboxState.EXPIRED,
                            updatedAtMs = nowMs,
                        )
                    )
                } ?: next.inbox,
                tombstones = next.tombstones + (
                    delivery.deliveryId to Tombstone(
                        deliveryId = delivery.deliveryId,
                        reason = TombstoneReason.EXPIRED,
                        createdAtMs = nowMs,
                        expiresAtMs = safeAdd(nowMs, tombstoneRetentionMs),
                    )
                ),
            )
        }
        val appended = appendOutbox(
            next,
            expiring.map { DeliveryStoreEvent.DeliveryExpired(it.deliveryId) },
            nowMs,
        )
        Mutation.changed(
            state = appended.state,
            value = expiring.size,
            events = appended.records,
        )
    }

    override suspend fun pruneExpiredTombstones(
        nowMs: Long,
        limit: Int,
    ): Int = commit(CommitKind.PRUNE_TOMBSTONES) { original ->
        require(limit in 1..limits.maxOutboxReadBatch) {
            "Tombstone prune limit must be between 1 and ${limits.maxOutboxReadBatch}"
        }
        val deliveryIds = original.tombstones.values
            .filter { it.expiresAtMs <= nowMs }
            .sortedBy { it.expiresAtMs }
            .take(limit)
            .map { it.deliveryId }
        if (deliveryIds.isEmpty()) return@commit Mutation.unchanged(0)

        var next = original
        deliveryIds.forEach { deliveryId ->
            next = next.withDeliveryRemoved(deliveryId)
        }
        Mutation.changed(next, deliveryIds.size)
    }

    override suspend fun listOutbox(
        afterSequenceExclusive: Long,
        limit: Int,
    ): List<OutboxRecord> = mutex.withLock {
        require(afterSequenceExclusive >= 0) { "Outbox sequence must not be negative" }
        require(limit in 1..limits.maxOutboxReadBatch) {
            "Outbox read limit must be between 1 and ${limits.maxOutboxReadBatch}"
        }
        state.outbox.asSequence()
            .filter { it.sequence > afterSequenceExclusive }
            .take(limit)
            .toList()
    }

    override suspend fun acknowledgeOutbox(
        eventId: OutboxEventId,
        nowMs: Long,
    ): OutboxRecord = commit(CommitKind.ACKNOWLEDGE_OUTBOX) { original ->
        val current = original.outbox.firstOrNull { it.eventId == eventId }
            ?: throw InvalidDeliveryTransitionException("Unknown outbox event $eventId")
        if (current.acknowledgedAtMs != null) return@commit Mutation.unchanged(current)

        val acknowledged = current.copy(acknowledgedAtMs = nowMs)
        Mutation.changed(
            state = original.copy(
                outbox = original.outbox.map {
                    if (it.eventId == eventId) acknowledged else it
                }
            ),
            value = acknowledged,
        )
    }

    override suspend fun pruneAcknowledgedOutbox(
        beforeOrAtMs: Long,
        limit: Int,
    ): Int = commit(CommitKind.PRUNE_OUTBOX) { original ->
        require(limit in 1..limits.maxOutboxReadBatch) {
            "Outbox prune limit must be between 1 and ${limits.maxOutboxReadBatch}"
        }
        val eventIds = original.outbox.asSequence()
            .filter { record ->
                record.acknowledgedAtMs?.let { it <= beforeOrAtMs } == true
            }
            .take(limit)
            .map { it.eventId }
            .toSet()
        if (eventIds.isEmpty()) return@commit Mutation.unchanged(0)

        Mutation.changed(
            state = original.copy(
                outbox = original.outbox.filterNot { it.eventId in eventIds }
            ),
            value = eventIds.size,
        )
    }

    internal suspend fun checkpoint(): Checkpoint = mutex.withLock { Checkpoint(state) }

    private suspend fun updateAttempt(
        kind: CommitKind,
        lease: TransferLease,
        nowMs: Long,
        permittedStates: Set<TransferAttemptState>,
        update: (TransferAttempt) -> TransferAttempt,
    ): TransferAttempt = commit(kind) { original ->
        val attempt = original.attempts[lease.attemptId]
            ?: throw StaleTransferLeaseException(lease.attemptId)
        val owner = original.leaseOwners[lease.attemptId]
        if (
            owner != lease.ownerToken ||
            lease.expiresAtMs != attempt.leaseExpiresAtMs ||
            nowMs >= attempt.leaseExpiresAtMs
        ) {
            throw StaleTransferLeaseException(lease.attemptId)
        }
        if (attempt.state !in permittedStates) {
            throw InvalidDeliveryTransitionException(
                "Transfer ${attempt.attemptId} is ${attempt.state}"
            )
        }
        val updated = update(attempt)
        Mutation.changed(
            state = original.copy(
                attempts = original.attempts + (attempt.attemptId to updated)
            ),
            value = updated,
        )
    }

    private suspend fun <T> commit(
        kind: CommitKind,
        mutation: (State) -> Mutation<T>,
    ): T = mutex.withLock {
        val result = mutation(state)
        result.state?.let { committedState ->
            beforeCommit(kind)
            state = committedState
            result.events.forEach { mutablePostCommitEvents.tryEmit(it) }
        }
        result.value
    }

    private fun ensureObjectWithinLimits(deliveryObject: DeliveryObject) {
        if (deliveryObject.canonicalBytes.size > limits.maxObjectBytes) {
            throw DeliveryStoreAdmissionException(
                "Delivery object exceeds ${limits.maxObjectBytes} bytes"
            )
        }
    }

    private fun appendOutbox(
        original: State,
        events: List<DeliveryStoreEvent>,
        nowMs: Long,
    ): AppendedOutbox {
        if (original.outbox.size + events.size > limits.maxOutboxRecords) {
            throw DeliveryStoreAdmissionException("Outbox record limit reached")
        }
        var sequence = original.nextOutboxSequence
        val records = events.map { event ->
            OutboxRecord(
                sequence = sequence,
                eventId = OutboxEventId("local-outbox-$sequence"),
                event = event,
                committedAtMs = nowMs,
            ).also { sequence += 1 }
        }
        return AppendedOutbox(
            state = original.copy(
                outbox = original.outbox + records,
                nextOutboxSequence = sequence,
            ),
            records = records,
        )
    }

    internal class Checkpoint internal constructor(internal val state: State)

    internal enum class CommitKind {
        INGEST,
        RESERVE_TRANSFER,
        UPDATE_TRANSFER,
        RECORD_RECEIPT,
        ACKNOWLEDGE_APPLICATION,
        EXPIRE,
        PRUNE_TOMBSTONES,
        ACKNOWLEDGE_OUTBOX,
        PRUNE_OUTBOX,
    }

    internal data class State(
        val objects: Map<DeliveryId, DeliveryObject> = emptyMap(),
        val records: Map<DeliveryId, DeliveryRecord> = emptyMap(),
        val attempts: Map<TransferAttemptId, TransferAttempt> = emptyMap(),
        val leaseOwners: Map<TransferAttemptId, String> = emptyMap(),
        val receipts: Map<ReceiptId, ReceiptRecord> = emptyMap(),
        val inbox: Map<DeliveryId, InboxRecord> = emptyMap(),
        val outbox: List<OutboxRecord> = emptyList(),
        val tombstones: Map<DeliveryId, Tombstone> = emptyMap(),
        val nextAttemptSequence: Long = 1,
        val nextOutboxSequence: Long = 1,
    ) {
        fun snapshot(deliveryId: DeliveryId): DeliverySnapshot = DeliverySnapshot(
            deliveryObject = objects[deliveryId],
            deliveryRecord = records[deliveryId],
            transferAttempts = attempts.values
                .filter { it.deliveryId == deliveryId }
                .sortedBy { it.attemptId.value },
            receipts = receipts.values
                .filter { it.receipt.deliveryId == deliveryId }
                .sortedBy { it.receipt.receiptId.value },
            inboxRecord = inbox[deliveryId],
            tombstone = tombstones[deliveryId],
        )

        fun withDeliveryRemoved(deliveryId: DeliveryId): State {
            val removedAttemptIds = attempts.values
                .filter { it.deliveryId == deliveryId }
                .mapTo(mutableSetOf()) { it.attemptId }
            val removedReceiptIds = receipts.values
                .filter { it.receipt.deliveryId == deliveryId }
                .mapTo(mutableSetOf()) { it.receipt.receiptId }
            return copy(
                objects = objects - deliveryId,
                records = records - deliveryId,
                attempts = attempts - removedAttemptIds,
                leaseOwners = leaseOwners - removedAttemptIds,
                receipts = receipts - removedReceiptIds,
                inbox = inbox - deliveryId,
                tombstones = tombstones - deliveryId,
            )
        }
    }

    private data class Mutation<T>(
        val state: State?,
        val value: T,
        val events: List<OutboxRecord>,
    ) {
        companion object {
            fun <T> unchanged(value: T): Mutation<T> = Mutation(
                state = null,
                value = value,
                events = emptyList(),
            )

            fun <T> changed(
                state: State,
                value: T,
                events: List<OutboxRecord> = emptyList(),
            ): Mutation<T> = Mutation(state, value, events)
        }
    }

    private data class AppendedOutbox(
        val state: State,
        val records: List<OutboxRecord>,
    )

    companion object {
        private const val POST_COMMIT_HINT_BUFFER = 64
        private val ACTIVE_ATTEMPT_STATES = setOf(
            TransferAttemptState.RESERVED,
            TransferAttemptState.TRANSFERRING,
            TransferAttemptState.LINK_WRITE_COMPLETED,
        )

        internal fun recover(
            checkpoint: Checkpoint,
            limits: DeliveryStoreLimits = DeliveryStoreLimits(),
            beforeCommit: (CommitKind) -> Unit = {},
            leaseTokenSource: () -> String = { UUID.randomUUID().toString() },
        ): InMemoryDeliveryStore = InMemoryDeliveryStore(
            limits = limits,
            beforeCommit = beforeCommit,
            leaseTokenSource = leaseTokenSource,
            initialState = checkpoint.state,
        )
    }
}

private fun safeAdd(value: Long, positiveDuration: Long): Long {
    require(positiveDuration > 0) { "Duration must be positive" }
    if (value > Long.MAX_VALUE - positiveDuration) {
        throw IllegalArgumentException("Timestamp overflow")
    }
    return value + positiveDuration
}

private fun requireBoundedInternalText(label: String, value: String, maxBytes: Int) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value == value.trim()) { "$label must not contain surrounding whitespace" }
    require(value.encodeToByteArray().size <= maxBytes) { "$label exceeds $maxBytes bytes" }
}
