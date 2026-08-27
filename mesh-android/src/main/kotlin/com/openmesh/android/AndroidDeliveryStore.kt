package com.openmesh.android

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import com.openmesh.core.AcceptanceEvidenceId
import com.openmesh.core.AcceptanceEvidenceIdentityConflictException
import com.openmesh.core.CanonicalDeliveryConflictException
import com.openmesh.core.DeliveryId
import com.openmesh.core.DeliveryIngest
import com.openmesh.core.DeliveryIngestResult
import com.openmesh.core.DeliveryObject
import com.openmesh.core.DeliveryRecord
import com.openmesh.core.DeliverySnapshot
import com.openmesh.core.DeliveryState
import com.openmesh.core.DeliveryStore
import com.openmesh.core.DeliveryStoreAdmissionException
import com.openmesh.core.DeliveryStoreEvent
import com.openmesh.core.DeliveryStoreImplementationSupport
import com.openmesh.core.DeliveryStoreLimits
import com.openmesh.core.EndpointId
import com.openmesh.core.InboxRecord
import com.openmesh.core.InboxState
import com.openmesh.core.IngressProvenance
import com.openmesh.core.InvalidDeliveryTransitionException
import com.openmesh.core.NextHopAcceptanceEvidenceKind
import com.openmesh.core.NextHopAcceptanceRecord
import com.openmesh.core.NextHopAcceptanceWriteResult
import com.openmesh.core.OpaqueBytes
import com.openmesh.core.OutboxEventId
import com.openmesh.core.OutboxRecord
import com.openmesh.core.ReceiptEvidence
import com.openmesh.core.ReceiptId
import com.openmesh.core.ReceiptIdentityConflictException
import com.openmesh.core.ReceiptInput
import com.openmesh.core.ReceiptRecord
import com.openmesh.core.ReceiptVerification
import com.openmesh.core.ReceiptWriteResult
import com.openmesh.core.StaleTransferLeaseException
import com.openmesh.core.Tombstone
import com.openmesh.core.TombstoneReason
import com.openmesh.core.TransferAttempt
import com.openmesh.core.TransferAttemptId
import com.openmesh.core.TransferAttemptState
import com.openmesh.core.TransferContext
import com.openmesh.core.TransferLease
import com.openmesh.core.TransferReservation
import com.openmesh.core.TransferReservationResult
import com.openmesh.core.VerifiedNextHopAcceptance
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SQLite-backed Android implementation of the ratified [DeliveryStore] contract.
 *
 * This class is intentionally not wired into [SharedPreferencesPacketStore] or
 * the BLE runtime yet. PR #13 proves persistence and recovery before cutover.
 */
class AndroidDeliveryStore internal constructor(
    context: Context,
    databaseName: String,
    private val limits: DeliveryStoreLimits,
    private val beforeCommit: (CommitKind) -> Unit,
    private val leaseTokenSource: () -> String,
) : DeliveryStore, Closeable {

    @JvmOverloads
    constructor(
        context: Context,
        databaseName: String = DEFAULT_DATABASE_NAME,
        limits: DeliveryStoreLimits = DeliveryStoreLimits(),
    ) : this(
        context = context,
        databaseName = databaseName,
        limits = limits,
        beforeCommit = {},
        leaseTokenSource = ::secureLeaseToken,
    )

    private val helper: OpenMeshDeliveryDatabase
    private val mutex = Mutex()
    private val mutablePostCommitEvents = MutableSharedFlow<OutboxRecord>(
        replay = 0,
        extraBufferCapacity = POST_COMMIT_HINT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        require(databaseName.isNotBlank()) { "Delivery database name must not be blank" }
        helper = OpenMeshDeliveryDatabase(context, databaseName)
        helper.setWriteAheadLoggingEnabled(true)
    }

    override val postCommitEvents: Flow<OutboxRecord> =
        mutablePostCommitEvents.asSharedFlow()

    override suspend fun ingest(
        ingest: DeliveryIngest,
        nowMs: Long,
    ): DeliveryIngestResult = write(CommitKind.INGEST) { transaction ->
        ingest(transaction, ingest, nowMs)
    }

    override suspend fun snapshot(deliveryId: DeliveryId): DeliverySnapshot = read { database ->
        snapshot(database, deliveryId)
    }

    override suspend fun reserveTransfer(
        reservation: TransferReservation,
        nowMs: Long,
    ): TransferReservationResult = write(CommitKind.RESERVE_TRANSFER) { transaction ->
        val database = transaction.database
        val delivery = loadDeliveryObject(database, reservation.deliveryId)
        val record = loadDeliveryRecord(database, reservation.deliveryId)
        if (
            delivery == null ||
            record?.state != DeliveryState.WAITING ||
            delivery.expiresAtMs <= nowMs ||
            loadTombstone(database, reservation.deliveryId) != null
        ) {
            return@write TransferReservationResult.NotEligible
        }

        var attempts = loadAttempts(database, reservation.deliveryId)
        attempts.filter { loaded ->
            loaded.attempt.state in ACTIVE_ATTEMPT_STATES &&
                loaded.attempt.leaseExpiresAtMs <= nowMs
        }.forEach { loaded ->
            writeAttempt(
                transaction,
                loaded.attempt.copy(
                    state = TransferAttemptState.LEASE_EXPIRED,
                    leaseExpiredAtMs = nowMs,
                    finishedAtMs = nowMs,
                ),
                loaded.ownerToken,
            )
        }

        attempts = loadAttempts(database, reservation.deliveryId)
        val busy = attempts.firstOrNull { loaded ->
            loaded.attempt.context == reservation.context &&
                loaded.attempt.state in ACTIVE_ATTEMPT_STATES &&
                loaded.attempt.leaseExpiresAtMs > nowMs
        }
        if (busy != null) return@write TransferReservationResult.Busy(busy.attempt)

        if (attempts.size >= limits.maxTransferAttemptsPerDelivery) {
            throw DeliveryStoreAdmissionException(
                "Transfer attempt limit reached for ${reservation.deliveryId}"
            )
        }

        val leaseExpiresAtMs = safeAdd(nowMs, reservation.leaseDurationMs)
        val sequence = nextAttemptSequence(transaction)
        val attemptId = TransferAttemptId("local-attempt-$sequence")
        val ownerToken = leaseTokenSource()
        requireBoundedAndroidStoreText("Lease owner token", ownerToken, MAX_LEASE_TOKEN_BYTES)
        val attempt = TransferAttempt(
            attemptId = attemptId,
            deliveryId = reservation.deliveryId,
            context = reservation.context,
            state = TransferAttemptState.RESERVED,
            reservedAtMs = nowMs,
            leaseExpiresAtMs = leaseExpiresAtMs,
        )
        insertAttempt(transaction, attempt, ownerToken)
        TransferReservationResult.Acquired(
            lease = DeliveryStoreImplementationSupport.issueTransferLease(
                attemptId = attemptId,
                expiresAtMs = leaseExpiresAtMs,
                ownerToken = ownerToken,
            ),
            attempt = attempt,
        )
    }

    override suspend fun markTransferStarted(
        lease: TransferLease,
        nowMs: Long,
    ): TransferAttempt = updateAttempt(
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
        lease = lease,
        nowMs = nowMs,
        permittedStates = setOf(TransferAttemptState.TRANSFERRING),
    ) { attempt ->
        attempt.copy(
            state = TransferAttemptState.LINK_WRITE_COMPLETED,
            linkWriteCompletedAtMs = nowMs,
        )
    }

    override suspend fun recordTransferFailure(
        lease: TransferLease,
        reason: String,
        nowMs: Long,
    ): TransferAttempt {
        requireBoundedAndroidStoreText("Transfer failure reason", reason, 1_024)
        return updateAttempt(
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

    override suspend fun recordNextHopAcceptedDurably(
        acceptance: VerifiedNextHopAcceptance,
        nowMs: Long,
    ): NextHopAcceptanceWriteResult = write(
        CommitKind.RECORD_NEXT_HOP_ACCEPTANCE
    ) { transaction ->
        val database = transaction.database
        if (loadDeliveryRecord(database, acceptance.deliveryId) == null) {
            throw InvalidDeliveryTransitionException(
                "Cannot accept unknown delivery ${acceptance.deliveryId}"
            )
        }
        val loadedAttempt = loadAttempt(database, acceptance.transferAttemptId)
            ?: throw InvalidDeliveryTransitionException(
                "Acceptance references unknown transfer attempt ${acceptance.transferAttemptId}"
            )
        val attempt = loadedAttempt.attempt
        if (attempt.deliveryId != acceptance.deliveryId) {
            throw InvalidDeliveryTransitionException(
                "Acceptance and transfer attempt belong to different deliveries"
            )
        }
        if (acceptance.protectedBytes.size > limits.maxAcceptanceEvidenceBytes) {
            throw DeliveryStoreAdmissionException(
                "Acceptance evidence exceeds ${limits.maxAcceptanceEvidenceBytes} bytes"
            )
        }

        val receipt = DeliveryStoreImplementationSupport.verifiedReceipt(acceptance)
        val existingAcceptance = loadAcceptance(database, acceptance.evidenceId)
        if (existingAcceptance != null) {
            if (!existingAcceptance.matches(acceptance, receipt)) {
                throw AcceptanceEvidenceIdentityConflictException(acceptance.evidenceId)
            }
            return@write NextHopAcceptanceWriteResult.Duplicate(
                record = existingAcceptance,
                attempt = attempt,
            )
        }
        if (
            countRows(
                database,
                DeliverySchema.ACCEPTANCE_EVIDENCE,
                "delivery_id = ?",
                arrayOf(acceptance.deliveryId.value),
            ) >= limits.maxAcceptancesPerDelivery
        ) {
            throw DeliveryStoreAdmissionException(
                "Next-hop acceptance evidence limit reached for ${acceptance.deliveryId}"
            )
        }
        if (
            attempt.state != TransferAttemptState.NEXT_HOP_ACCEPTED_DURABLY &&
            attempt.startedAtMs == null
        ) {
            throw InvalidDeliveryTransitionException(
                "Transfer ${attempt.attemptId} never entered TRANSFERRING"
            )
        }

        var receiptWasNew = false
        receipt?.let { verifiedReceipt ->
            check(verifiedReceipt.deliveryId == acceptance.deliveryId)
            check(verifiedReceipt.linkedTransferAttemptId == acceptance.transferAttemptId)
            if (verifiedReceipt.protectedBytes.size > limits.maxReceiptBytes) {
                throw DeliveryStoreAdmissionException(
                    "Receipt exceeds ${limits.maxReceiptBytes} bytes"
                )
            }
            val existingReceipt = loadReceipt(database, verifiedReceipt.receiptId)
            if (existingReceipt != null && existingReceipt.receipt != verifiedReceipt) {
                throw ReceiptIdentityConflictException(verifiedReceipt.receiptId)
            }
            if (existingReceipt == null) {
                ensureReceiptCapacity(database, verifiedReceipt.deliveryId)
                insertReceipt(
                    transaction,
                    ReceiptRecord(verifiedReceipt, storedAtMs = nowMs, appliedAtMs = nowMs),
                )
                receiptWasNew = true
            } else if (existingReceipt.appliedAtMs == null) {
                updateReceiptAppliedAt(transaction, verifiedReceipt.receiptId, nowMs)
            }
        }

        val evidenceRecord = NextHopAcceptanceRecord(
            deliveryId = acceptance.deliveryId,
            transferAttemptId = acceptance.transferAttemptId,
            evidenceId = acceptance.evidenceId,
            authority = acceptance.authority,
            kind = acceptance.kind,
            protectedBytes = acceptance.protectedBytes,
            receiptId = receipt?.receiptId,
            storedAtMs = nowMs,
        )
        insertAcceptance(transaction, evidenceRecord)
        val acceptedAttempt = attempt.copy(
            state = TransferAttemptState.NEXT_HOP_ACCEPTED_DURABLY,
            nextHopAcceptedDurablyAtMs = attempt.nextHopAcceptedDurablyAtMs ?: nowMs,
            nextHopAcceptanceEvidenceId =
                attempt.nextHopAcceptanceEvidenceId ?: acceptance.evidenceId,
            finishedAtMs = nowMs,
            failureReason = null,
        )
        writeAttempt(transaction, acceptedAttempt, loadedAttempt.ownerToken)

        transaction.appendOutbox(
            buildList {
                if (receiptWasNew) {
                    add(
                        DeliveryStoreEvent.ReceiptRecorded(
                            deliveryId = acceptance.deliveryId,
                            receiptId = checkNotNull(receipt).receiptId,
                        )
                    )
                }
                add(
                    DeliveryStoreEvent.NextHopAcceptedDurably(
                        deliveryId = acceptance.deliveryId,
                        transferAttemptId = acceptance.transferAttemptId,
                        evidenceId = acceptance.evidenceId,
                    )
                )
            },
            nowMs,
        )
        NextHopAcceptanceWriteResult.Stored(evidenceRecord, acceptedAttempt)
    }

    override suspend fun recordReceipt(
        receipt: ReceiptInput,
        nowMs: Long,
    ): ReceiptWriteResult = write(CommitKind.RECORD_RECEIPT) { transaction ->
        val database = transaction.database
        val deliveryRecord = loadDeliveryRecord(database, receipt.deliveryId)
            ?: throw InvalidDeliveryTransitionException(
                "Cannot attach receipt to unknown delivery ${receipt.deliveryId}"
            )
        if (receipt.protectedBytes.size > limits.maxReceiptBytes) {
            throw DeliveryStoreAdmissionException(
                "Receipt exceeds ${limits.maxReceiptBytes} bytes"
            )
        }

        val existing = loadReceipt(database, receipt.receiptId)
        if (existing != null) {
            if (existing.receipt != receipt) {
                throw ReceiptIdentityConflictException(receipt.receiptId)
            }
            return@write ReceiptWriteResult.Duplicate(existing)
        }
        ensureReceiptCapacity(database, receipt.deliveryId)

        receipt.linkedTransferAttemptId?.let { attemptId ->
            val linkedAttempt = loadAttempt(database, attemptId)?.attempt
                ?: throw InvalidDeliveryTransitionException(
                    "Receipt references unknown transfer attempt $attemptId"
                )
            if (linkedAttempt.deliveryId != receipt.deliveryId) {
                throw InvalidDeliveryTransitionException(
                    "Receipt and transfer attempt belong to different deliveries"
                )
            }
        }

        val appliesToDelivery =
            receipt.verification == ReceiptVerification.AUTHENTICATED_AND_AUTHORIZED &&
                receipt.evidence == ReceiptEvidence.APP_DELIVERED
        val stored = ReceiptRecord(
            receipt = receipt,
            storedAtMs = nowMs,
            appliedAtMs = nowMs.takeIf { appliesToDelivery },
        )
        insertReceipt(transaction, stored)

        if (appliesToDelivery) {
            writeDeliveryRecord(
                transaction,
                deliveryRecord.copy(
                    state = DeliveryState.APP_DELIVERED,
                    updatedAtMs = nowMs,
                    version = deliveryRecord.version + 1,
                ),
            )
            loadInbox(database, receipt.deliveryId)?.let { inbox ->
                writeInbox(
                    transaction,
                    inbox.copy(state = InboxState.DELIVERED, updatedAtMs = nowMs),
                )
            }
        }

        transaction.appendOutbox(
            listOf(DeliveryStoreEvent.ReceiptRecorded(receipt.deliveryId, receipt.receiptId)),
            nowMs,
        )
        ReceiptWriteResult.Stored(stored)
    }

    override suspend fun acknowledgeApplicationDelivery(
        deliveryId: DeliveryId,
        nowMs: Long,
    ): DeliveryRecord = write(CommitKind.ACKNOWLEDGE_APPLICATION) { transaction ->
        val database = transaction.database
        val current = loadDeliveryRecord(database, deliveryId)
            ?: throw InvalidDeliveryTransitionException("Unknown delivery $deliveryId")
        if (current.state == DeliveryState.APP_DELIVERED) return@write current
        if (current.state != DeliveryState.APP_PENDING) {
            throw InvalidDeliveryTransitionException(
                "Delivery $deliveryId is ${current.state}, not APP_PENDING"
            )
        }
        val inbox = loadInbox(database, deliveryId)
            ?: throw InvalidDeliveryTransitionException("Delivery $deliveryId has no inbox record")
        val delivered = current.copy(
            state = DeliveryState.APP_DELIVERED,
            updatedAtMs = nowMs,
            version = current.version + 1,
        )
        writeDeliveryRecord(transaction, delivered)
        writeInbox(
            transaction,
            inbox.copy(state = InboxState.DELIVERED, updatedAtMs = nowMs),
        )
        transaction.appendOutbox(
            listOf(DeliveryStoreEvent.ApplicationDelivered(deliveryId)),
            nowMs,
        )
        delivered
    }

    override suspend fun expireObjects(
        nowMs: Long,
        tombstoneRetentionMs: Long,
    ): Int = write(CommitKind.EXPIRE) { transaction ->
        require(tombstoneRetentionMs > 0) { "Tombstone retention must be positive" }
        val database = transaction.database
        val deliveryIds = database.rawQuery(
            "SELECT delivery_id FROM ${DeliverySchema.DELIVERY_OBJECTS} " +
                "WHERE expires_at_ms <= ? ORDER BY delivery_id",
            arrayOf(nowMs.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(DeliveryId(cursor.getString(0)))
            }
        }
        if (deliveryIds.isEmpty()) return@write 0

        deliveryIds.forEach { deliveryId ->
            val current = checkNotNull(loadDeliveryRecord(database, deliveryId))
            loadAttempts(database, deliveryId)
                .filter { it.attempt.state in ACTIVE_ATTEMPT_STATES }
                .forEach { loaded ->
                    writeAttempt(
                        transaction,
                        loaded.attempt.copy(
                            state = TransferAttemptState.CANCELLED,
                            finishedAtMs = nowMs,
                            failureReason = "delivery expired",
                        ),
                        loaded.ownerToken,
                    )
                }
            check(
                database.delete(
                    DeliverySchema.DELIVERY_OBJECTS,
                    "delivery_id = ?",
                    arrayOf(deliveryId.value),
                ) == 1
            )
            transaction.markChanged()
            writeDeliveryRecord(
                transaction,
                current.copy(
                    state = DeliveryState.EXPIRED,
                    updatedAtMs = nowMs,
                    version = current.version + 1,
                ),
            )
            loadInbox(database, deliveryId)?.let { inbox ->
                writeInbox(
                    transaction,
                    inbox.copy(state = InboxState.EXPIRED, updatedAtMs = nowMs),
                )
            }
            writeTombstone(
                transaction,
                Tombstone(
                    deliveryId = deliveryId,
                    reason = TombstoneReason.EXPIRED,
                    createdAtMs = nowMs,
                    expiresAtMs = safeAdd(nowMs, tombstoneRetentionMs),
                ),
            )
        }
        transaction.appendOutbox(
            deliveryIds.map { DeliveryStoreEvent.DeliveryExpired(it) },
            nowMs,
        )
        deliveryIds.size
    }

    override suspend fun pruneExpiredTombstones(
        nowMs: Long,
        limit: Int,
    ): Int = write(CommitKind.PRUNE_TOMBSTONES) { transaction ->
        require(limit in 1..limits.maxOutboxReadBatch) {
            "Tombstone prune limit must be between 1 and ${limits.maxOutboxReadBatch}"
        }
        val deliveryIds = transaction.database.rawQuery(
            "SELECT delivery_id FROM ${DeliverySchema.TOMBSTONES} " +
                "WHERE expires_at_ms <= ? ORDER BY expires_at_ms, delivery_id LIMIT ?",
            arrayOf(nowMs.toString(), limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        deliveryIds.forEach { deliveryId ->
            check(
                transaction.database.delete(
                    DeliverySchema.DELIVERY_RECORDS,
                    "delivery_id = ?",
                    arrayOf(deliveryId),
                ) == 1
            )
            transaction.markChanged()
        }
        deliveryIds.size
    }

    override suspend fun listOutbox(
        afterSequenceExclusive: Long,
        limit: Int,
    ): List<OutboxRecord> = read { database ->
        require(afterSequenceExclusive >= 0) { "Outbox sequence must not be negative" }
        require(limit in 1..limits.maxOutboxReadBatch) {
            "Outbox read limit must be between 1 and ${limits.maxOutboxReadBatch}"
        }
        database.rawQuery(
            "SELECT * FROM ${DeliverySchema.OUTBOX} WHERE sequence > ? " +
                "ORDER BY sequence LIMIT ?",
            arrayOf(afterSequenceExclusive.toString(), limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toOutboxRecord())
            }
        }
    }

    override suspend fun acknowledgeOutbox(
        eventId: OutboxEventId,
        nowMs: Long,
    ): OutboxRecord = write(CommitKind.ACKNOWLEDGE_OUTBOX) { transaction ->
        val current = loadOutbox(transaction.database, eventId)
            ?: throw InvalidDeliveryTransitionException("Unknown outbox event $eventId")
        if (current.acknowledgedAtMs != null) return@write current
        val values = ContentValues().apply { put("acknowledged_at_ms", nowMs) }
        check(
            transaction.database.update(
                DeliverySchema.OUTBOX,
                values,
                "event_id = ?",
                arrayOf(eventId.value),
            ) == 1
        )
        transaction.markChanged()
        current.copy(acknowledgedAtMs = nowMs)
    }

    override suspend fun pruneAcknowledgedOutbox(
        beforeOrAtMs: Long,
        limit: Int,
    ): Int = write(CommitKind.PRUNE_OUTBOX) { transaction ->
        require(limit in 1..limits.maxOutboxReadBatch) {
            "Outbox prune limit must be between 1 and ${limits.maxOutboxReadBatch}"
        }
        val eventIds = transaction.database.rawQuery(
            "SELECT event_id FROM ${DeliverySchema.OUTBOX} " +
                "WHERE acknowledged_at_ms <= ? ORDER BY sequence LIMIT ?",
            arrayOf(beforeOrAtMs.toString(), limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        eventIds.forEach { eventId ->
            check(
                transaction.database.delete(
                    DeliverySchema.OUTBOX,
                    "event_id = ?",
                    arrayOf(eventId),
                ) == 1
            )
            transaction.markChanged()
        }
        eventIds.size
    }

    override fun close() {
        helper.close()
    }

    internal val configuredLimits: DeliveryStoreLimits
        get() = limits

    internal suspend fun <T> readForLegacyMigration(
        block: (SQLiteDatabase) -> T,
    ): T = read(block)

    internal suspend fun <T> writeForLegacyMigration(
        block: (Transaction) -> T,
    ): T = write(CommitKind.LEGACY_MIGRATION, block)

    internal fun ingestForLegacyMigration(
        transaction: Transaction,
        ingest: DeliveryIngest,
        nowMs: Long,
    ): DeliveryIngestResult = ingest(transaction, ingest, nowMs)

    private fun ensureReceiptCapacity(
        database: SQLiteDatabase,
        deliveryId: DeliveryId,
    ) {
        if (
            countRows(
                database,
                DeliverySchema.RECEIPTS,
                "delivery_id = ?",
                arrayOf(deliveryId.value),
            ) >= limits.maxReceiptsPerDelivery
        ) {
            throw DeliveryStoreAdmissionException("Receipt limit reached for $deliveryId")
        }
    }

    private suspend fun updateAttempt(
        lease: TransferLease,
        nowMs: Long,
        permittedStates: Set<TransferAttemptState>,
        update: (TransferAttempt) -> TransferAttempt,
    ): TransferAttempt = write(CommitKind.UPDATE_TRANSFER) { transaction ->
        val loaded = loadAttempt(transaction.database, lease.attemptId)
            ?: throw StaleTransferLeaseException(lease.attemptId)
        if (
            !DeliveryStoreImplementationSupport.leaseMatchesOwnerToken(
                lease,
                loaded.ownerToken,
            ) ||
            lease.expiresAtMs != loaded.attempt.leaseExpiresAtMs ||
            nowMs >= loaded.attempt.leaseExpiresAtMs
        ) {
            throw StaleTransferLeaseException(lease.attemptId)
        }
        if (loaded.attempt.state !in permittedStates) {
            throw InvalidDeliveryTransitionException(
                "Transfer ${loaded.attempt.attemptId} is ${loaded.attempt.state}"
            )
        }
        val updated = update(loaded.attempt)
        writeAttempt(transaction, updated, loaded.ownerToken)
        updated
    }

    private fun ingest(
        transaction: Transaction,
        ingest: DeliveryIngest,
        nowMs: Long,
    ): DeliveryIngestResult {
        val database = transaction.database
        val incoming = ingest.deliveryObject
        require(incoming.expiresAtMs > nowMs) { "Cannot ingest an already expired object" }
        if (incoming.canonicalBytes.size > limits.maxObjectBytes) {
            throw DeliveryStoreAdmissionException(
                "Delivery object exceeds ${limits.maxObjectBytes} bytes"
            )
        }

        val tombstone = loadTombstone(database, incoming.deliveryId)
        if (tombstone != null && tombstone.expiresAtMs > nowMs) {
            return DeliveryIngestResult.RejectedByTombstone(tombstone)
        }
        if (tombstone != null) {
            check(
                database.delete(
                    DeliverySchema.DELIVERY_RECORDS,
                    "delivery_id = ?",
                    arrayOf(incoming.deliveryId.value),
                ) == 1
            )
            transaction.markChanged()
        }

        val existingObject = loadDeliveryObject(database, incoming.deliveryId)
        if (existingObject != null) {
            if (existingObject != incoming) {
                throw CanonicalDeliveryConflictException(incoming.deliveryId)
            }
            val existingRecord = checkNotNull(loadDeliveryRecord(database, incoming.deliveryId))
            if (hasProvenance(database, incoming.deliveryId, ingest.provenance)) {
                return DeliveryIngestResult.Duplicate(existingRecord, provenanceAdded = false)
            }
            if (existingRecord.provenance.size >= limits.maxProvenanceEntriesPerDelivery) {
                throw DeliveryStoreAdmissionException(
                    "Provenance limit reached for ${incoming.deliveryId}"
                )
            }
            insertProvenance(transaction, incoming.deliveryId, ingest.provenance)
            val reconciled = existingRecord.copy(
                updatedAtMs = nowMs,
                version = existingRecord.version + 1,
                provenance = existingRecord.provenance + ingest.provenance,
            )
            writeDeliveryRecord(transaction, reconciled)
            return DeliveryIngestResult.Duplicate(reconciled, provenanceAdded = true)
        }

        if (countRows(database, DeliverySchema.DELIVERY_RECORDS) >= limits.maxDeliveryRecords) {
            throw DeliveryStoreAdmissionException("Delivery record limit reached")
        }
        val state = if (ingest.destinationIsLocal) {
            DeliveryState.APP_PENDING
        } else {
            DeliveryState.WAITING
        }
        val record = DeliveryRecord(
            deliveryId = incoming.deliveryId,
            state = state,
            storedAtMs = nowMs,
            updatedAtMs = nowMs,
            version = 1,
            provenance = setOf(ingest.provenance),
        )
        insertDeliveryRecord(transaction, record)
        insertProvenance(transaction, incoming.deliveryId, ingest.provenance)
        insertDeliveryObject(transaction, incoming)
        if (ingest.destinationIsLocal) {
            insertInbox(
                transaction,
                InboxRecord(
                    deliveryId = incoming.deliveryId,
                    state = InboxState.PENDING,
                    enqueuedAtMs = nowMs,
                    updatedAtMs = nowMs,
                ),
            )
        }
        val committedEvents = transaction.appendOutbox(
            buildList {
                add(DeliveryStoreEvent.DurablyStored(incoming.deliveryId))
                if (ingest.destinationIsLocal) {
                    add(DeliveryStoreEvent.ApplicationDeliveryAvailable(incoming.deliveryId))
                }
            },
            nowMs,
        )
        return DeliveryIngestResult.Stored(record, committedEvents)
    }

    private suspend fun <T> read(block: (SQLiteDatabase) -> T): T = mutex.withLock {
        block(helper.readableDatabase)
    }

    private suspend fun <T> write(
        kind: CommitKind,
        block: (Transaction) -> T,
    ): T = mutex.withLock {
        val database = helper.writableDatabase
        val transaction = Transaction(database)
        database.beginTransaction()
        val value: T
        try {
            value = block(transaction)
            if (transaction.changed) beforeCommit(kind)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        transaction.postCommitEvents.forEach(mutablePostCommitEvents::tryEmit)
        value
    }

    internal inner class Transaction(val database: SQLiteDatabase) {
        var changed: Boolean = false
            private set
        val postCommitEvents = mutableListOf<OutboxRecord>()

        fun markChanged() {
            changed = true
        }

        fun appendOutbox(
            events: List<DeliveryStoreEvent>,
            nowMs: Long,
        ): List<OutboxRecord> {
            if (events.isEmpty()) return emptyList()
            if (
                countRows(database, DeliverySchema.OUTBOX) + events.size >
                limits.maxOutboxRecords
            ) {
                throw DeliveryStoreAdmissionException("Outbox record limit reached")
            }
            val records = events.map { event ->
                val values = ContentValues().apply {
                    putNull("event_id")
                    put("event_type", event.typeName())
                    put("delivery_id", event.deliveryId.value)
                    putNullable("receipt_id", (event as? DeliveryStoreEvent.ReceiptRecorded)?.receiptId?.value)
                    putNullable(
                        "transfer_attempt_id",
                        (event as? DeliveryStoreEvent.NextHopAcceptedDurably)
                            ?.transferAttemptId?.value,
                    )
                    putNullable(
                        "evidence_id",
                        (event as? DeliveryStoreEvent.NextHopAcceptedDurably)?.evidenceId?.value,
                    )
                    put("committed_at_ms", nowMs)
                    putNull("acknowledged_at_ms")
                }
                val sequence = database.insertOrThrow(DeliverySchema.OUTBOX, null, values)
                val eventId = OutboxEventId("local-outbox-$sequence")
                val idValues = ContentValues().apply { put("event_id", eventId.value) }
                check(
                    database.update(
                        DeliverySchema.OUTBOX,
                        idValues,
                        "sequence = ?",
                        arrayOf(sequence.toString()),
                    ) == 1
                )
                OutboxRecord(
                    sequence = sequence,
                    eventId = eventId,
                    event = event,
                    committedAtMs = nowMs,
                )
            }
            markChanged()
            postCommitEvents += records
            return records
        }
    }

    internal enum class CommitKind {
        INGEST,
        RESERVE_TRANSFER,
        UPDATE_TRANSFER,
        RECORD_NEXT_HOP_ACCEPTANCE,
        RECORD_RECEIPT,
        ACKNOWLEDGE_APPLICATION,
        EXPIRE,
        PRUNE_TOMBSTONES,
        ACKNOWLEDGE_OUTBOX,
        PRUNE_OUTBOX,
        LEGACY_MIGRATION,
    }

    companion object {
        const val DEFAULT_DATABASE_NAME = "openmesh_delivery_store.db"
        private const val POST_COMMIT_HINT_BUFFER = 64
        private const val MAX_LEASE_TOKEN_BYTES = 4_096
        internal val ACTIVE_ATTEMPT_STATES = setOf(
            TransferAttemptState.RESERVED,
            TransferAttemptState.TRANSFERRING,
            TransferAttemptState.LINK_WRITE_COMPLETED,
        )
    }
}

private data class LoadedAttempt(
    val attempt: TransferAttempt,
    val ownerToken: String,
)

private fun snapshot(database: SQLiteDatabase, deliveryId: DeliveryId): DeliverySnapshot =
    DeliverySnapshot(
        deliveryObject = loadDeliveryObject(database, deliveryId),
        deliveryRecord = loadDeliveryRecord(database, deliveryId),
        transferAttempts = loadAttempts(database, deliveryId).map(LoadedAttempt::attempt),
        nextHopAcceptances = loadAcceptances(database, deliveryId),
        receipts = loadReceipts(database, deliveryId),
        inboxRecord = loadInbox(database, deliveryId),
        tombstone = loadTombstone(database, deliveryId),
    )

private fun loadDeliveryObject(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): DeliveryObject? = database.query(
    DeliverySchema.DELIVERY_OBJECTS,
    null,
    "delivery_id = ?",
    arrayOf(deliveryId.value),
    null,
    null,
    null,
).use { cursor ->
    if (!cursor.moveToFirst()) return@use null
    DeliveryObject(
        deliveryId = deliveryId,
        destination = EndpointId(cursor.string("destination")),
        source = cursor.nullableString("source")?.let(::EndpointId),
        createdAtMs = cursor.long("created_at_ms"),
        expiresAtMs = cursor.long("expires_at_ms"),
        canonicalBytes = OpaqueBytes.copyOf(cursor.blob("canonical_bytes")),
    )
}

private fun loadDeliveryRecord(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): DeliveryRecord? = database.query(
    DeliverySchema.DELIVERY_RECORDS,
    null,
    "delivery_id = ?",
    arrayOf(deliveryId.value),
    null,
    null,
    null,
).use { cursor ->
    if (!cursor.moveToFirst()) return@use null
    DeliveryRecord(
        deliveryId = deliveryId,
        state = enumValueOf(cursor.string("state")),
        storedAtMs = cursor.long("stored_at_ms"),
        updatedAtMs = cursor.long("updated_at_ms"),
        version = cursor.long("version"),
        provenance = loadProvenance(database, deliveryId),
    )
}

private fun loadProvenance(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): Set<IngressProvenance> = database.query(
    DeliverySchema.DELIVERY_PROVENANCE,
    arrayOf("kind", "reference_value"),
    "delivery_id = ?",
    arrayOf(deliveryId.value),
    null,
    null,
    "kind, reference_value",
).use { cursor ->
    buildSet {
        while (cursor.moveToNext()) {
            val reference = cursor.getString(1).ifEmpty { null }
            add(IngressProvenance(enumValueOf(cursor.getString(0)), reference))
        }
    }
}

private fun hasProvenance(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
    provenance: IngressProvenance,
): Boolean = countRows(
    database,
    DeliverySchema.DELIVERY_PROVENANCE,
    "delivery_id = ? AND kind = ? AND reference_value = ?",
    arrayOf(deliveryId.value, provenance.kind.name, provenance.reference.orEmpty()),
) > 0

private fun loadAttempts(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): List<LoadedAttempt> = database.query(
    DeliverySchema.TRANSFER_ATTEMPTS,
    null,
    "delivery_id = ?",
    arrayOf(deliveryId.value),
    null,
    null,
    "attempt_id",
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(cursor.toLoadedAttempt())
    }
}

private fun loadAttempt(
    database: SQLiteDatabase,
    attemptId: TransferAttemptId,
): LoadedAttempt? = database.query(
    DeliverySchema.TRANSFER_ATTEMPTS,
    null,
    "attempt_id = ?",
    arrayOf(attemptId.value),
    null,
    null,
    null,
).use { cursor ->
    if (!cursor.moveToFirst()) null else cursor.toLoadedAttempt()
}

private fun Cursor.toLoadedAttempt(): LoadedAttempt = LoadedAttempt(
    attempt = TransferAttempt(
        attemptId = TransferAttemptId(string("attempt_id")),
        deliveryId = DeliveryId(string("delivery_id")),
        context = TransferContext(string("adapter_id"), string("opportunity_id")),
        state = enumValueOf(string("state")),
        reservedAtMs = long("reserved_at_ms"),
        leaseExpiresAtMs = long("lease_expires_at_ms"),
        startedAtMs = nullableLong("started_at_ms"),
        linkWriteCompletedAtMs = nullableLong("link_write_completed_at_ms"),
        nextHopAcceptedDurablyAtMs = nullableLong("next_hop_accepted_durably_at_ms"),
        nextHopAcceptanceEvidenceId = nullableString("next_hop_acceptance_evidence_id")
            ?.let(::AcceptanceEvidenceId),
        leaseExpiredAtMs = nullableLong("lease_expired_at_ms"),
        finishedAtMs = nullableLong("finished_at_ms"),
        failureReason = nullableString("failure_reason"),
    ),
    ownerToken = string("lease_owner_token"),
)

private fun loadAcceptances(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): List<NextHopAcceptanceRecord> = database.query(
    DeliverySchema.ACCEPTANCE_EVIDENCE,
    null,
    "delivery_id = ?",
    arrayOf(deliveryId.value),
    null,
    null,
    "evidence_id",
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(cursor.toAcceptance())
    }
}

private fun loadAcceptance(
    database: SQLiteDatabase,
    evidenceId: AcceptanceEvidenceId,
): NextHopAcceptanceRecord? = database.query(
    DeliverySchema.ACCEPTANCE_EVIDENCE,
    null,
    "evidence_id = ?",
    arrayOf(evidenceId.value),
    null,
    null,
    null,
).use { cursor ->
    if (!cursor.moveToFirst()) null else cursor.toAcceptance()
}

private fun Cursor.toAcceptance(): NextHopAcceptanceRecord = NextHopAcceptanceRecord(
    deliveryId = DeliveryId(string("delivery_id")),
    transferAttemptId = TransferAttemptId(string("transfer_attempt_id")),
    evidenceId = AcceptanceEvidenceId(string("evidence_id")),
    authority = string("authority"),
    kind = enumValueOf(string("kind")),
    protectedBytes = OpaqueBytes.copyOf(blob("protected_bytes")),
    receiptId = nullableString("receipt_id")?.let(::ReceiptId),
    storedAtMs = long("stored_at_ms"),
)

private fun loadReceipts(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): List<ReceiptRecord> = database.query(
    DeliverySchema.RECEIPTS,
    null,
    "delivery_id = ?",
    arrayOf(deliveryId.value),
    null,
    null,
    "receipt_id",
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(cursor.toReceipt())
    }
}

private fun loadReceipt(
    database: SQLiteDatabase,
    receiptId: ReceiptId,
): ReceiptRecord? = database.query(
    DeliverySchema.RECEIPTS,
    null,
    "receipt_id = ?",
    arrayOf(receiptId.value),
    null,
    null,
    null,
).use { cursor ->
    if (!cursor.moveToFirst()) null else cursor.toReceipt()
}

private fun Cursor.toReceipt(): ReceiptRecord {
    val input = ReceiptInput.copyOf(
        receiptId = ReceiptId(string("receipt_id")),
        deliveryId = DeliveryId(string("delivery_id")),
        evidence = enumValueOf(string("evidence")),
        issuer = string("issuer"),
        verification = enumValueOf(string("verification")),
        protectedBytes = blob("protected_bytes"),
        linkedTransferAttemptId = nullableString("linked_transfer_attempt_id")
            ?.let(::TransferAttemptId),
    )
    return ReceiptRecord(
        receipt = input,
        storedAtMs = long("stored_at_ms"),
        appliedAtMs = nullableLong("applied_at_ms"),
    )
}

private fun loadInbox(database: SQLiteDatabase, deliveryId: DeliveryId): InboxRecord? =
    database.query(
        DeliverySchema.INBOX,
        null,
        "delivery_id = ?",
        arrayOf(deliveryId.value),
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        InboxRecord(
            deliveryId = deliveryId,
            state = enumValueOf(cursor.string("state")),
            enqueuedAtMs = cursor.long("enqueued_at_ms"),
            updatedAtMs = cursor.long("updated_at_ms"),
        )
    }

private fun loadTombstone(database: SQLiteDatabase, deliveryId: DeliveryId): Tombstone? =
    database.query(
        DeliverySchema.TOMBSTONES,
        null,
        "delivery_id = ?",
        arrayOf(deliveryId.value),
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        Tombstone(
            deliveryId = deliveryId,
            reason = enumValueOf(cursor.string("reason")),
            createdAtMs = cursor.long("created_at_ms"),
            expiresAtMs = cursor.long("expires_at_ms"),
        )
    }

private fun loadOutbox(database: SQLiteDatabase, eventId: OutboxEventId): OutboxRecord? =
    database.query(
        DeliverySchema.OUTBOX,
        null,
        "event_id = ?",
        arrayOf(eventId.value),
        null,
        null,
        null,
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.toOutboxRecord()
    }

private fun Cursor.toOutboxRecord(): OutboxRecord {
    val deliveryId = DeliveryId(string("delivery_id"))
    val event = when (string("event_type")) {
        "DURABLY_STORED" -> DeliveryStoreEvent.DurablyStored(deliveryId)
        "APPLICATION_DELIVERY_AVAILABLE" ->
            DeliveryStoreEvent.ApplicationDeliveryAvailable(deliveryId)
        "APPLICATION_DELIVERED" -> DeliveryStoreEvent.ApplicationDelivered(deliveryId)
        "RECEIPT_RECORDED" -> DeliveryStoreEvent.ReceiptRecorded(
            deliveryId,
            ReceiptId(string("receipt_id")),
        )
        "NEXT_HOP_ACCEPTED_DURABLY" -> DeliveryStoreEvent.NextHopAcceptedDurably(
            deliveryId,
            TransferAttemptId(string("transfer_attempt_id")),
            AcceptanceEvidenceId(string("evidence_id")),
        )
        "DELIVERY_EXPIRED" -> DeliveryStoreEvent.DeliveryExpired(deliveryId)
        else -> error("Unknown persisted outbox event type ${string("event_type")}")
    }
    return OutboxRecord(
        sequence = long("sequence"),
        eventId = OutboxEventId(string("event_id")),
        event = event,
        committedAtMs = long("committed_at_ms"),
        acknowledgedAtMs = nullableLong("acknowledged_at_ms"),
    )
}

private fun insertDeliveryRecord(
    transaction: AndroidDeliveryStore.Transaction,
    record: DeliveryRecord,
) {
    val values = ContentValues().apply {
        put("delivery_id", record.deliveryId.value)
        put("state", record.state.name)
        put("stored_at_ms", record.storedAtMs)
        put("updated_at_ms", record.updatedAtMs)
        put("version", record.version)
    }
    transaction.database.insertOrThrow(DeliverySchema.DELIVERY_RECORDS, null, values)
    transaction.markChanged()
}

private fun writeDeliveryRecord(
    transaction: AndroidDeliveryStore.Transaction,
    record: DeliveryRecord,
) {
    val values = ContentValues().apply {
        put("state", record.state.name)
        put("stored_at_ms", record.storedAtMs)
        put("updated_at_ms", record.updatedAtMs)
        put("version", record.version)
    }
    check(
        transaction.database.update(
            DeliverySchema.DELIVERY_RECORDS,
            values,
            "delivery_id = ?",
            arrayOf(record.deliveryId.value),
        ) == 1
    )
    transaction.markChanged()
}

private fun insertDeliveryObject(
    transaction: AndroidDeliveryStore.Transaction,
    deliveryObject: DeliveryObject,
) {
    val bytes = deliveryObject.canonicalBytes.copyToByteArray()
    val values = ContentValues().apply {
        put("delivery_id", deliveryObject.deliveryId.value)
        put("destination", deliveryObject.destination.value)
        putNullable("source", deliveryObject.source?.value)
        put("created_at_ms", deliveryObject.createdAtMs)
        put("expires_at_ms", deliveryObject.expiresAtMs)
        put("canonical_bytes", bytes)
        put("canonical_hash", sha256Hex(bytes))
    }
    transaction.database.insertOrThrow(DeliverySchema.DELIVERY_OBJECTS, null, values)
    transaction.markChanged()
}

private fun insertProvenance(
    transaction: AndroidDeliveryStore.Transaction,
    deliveryId: DeliveryId,
    provenance: IngressProvenance,
) {
    val values = ContentValues().apply {
        put("delivery_id", deliveryId.value)
        put("kind", provenance.kind.name)
        put("reference_value", provenance.reference.orEmpty())
    }
    transaction.database.insertOrThrow(DeliverySchema.DELIVERY_PROVENANCE, null, values)
    transaction.markChanged()
}

private fun insertInbox(
    transaction: AndroidDeliveryStore.Transaction,
    inbox: InboxRecord,
) {
    val values = ContentValues().apply {
        put("delivery_id", inbox.deliveryId.value)
        put("state", inbox.state.name)
        put("enqueued_at_ms", inbox.enqueuedAtMs)
        put("updated_at_ms", inbox.updatedAtMs)
    }
    transaction.database.insertOrThrow(DeliverySchema.INBOX, null, values)
    transaction.markChanged()
}

private fun writeInbox(
    transaction: AndroidDeliveryStore.Transaction,
    inbox: InboxRecord,
) {
    val values = ContentValues().apply {
        put("state", inbox.state.name)
        put("enqueued_at_ms", inbox.enqueuedAtMs)
        put("updated_at_ms", inbox.updatedAtMs)
    }
    check(
        transaction.database.update(
            DeliverySchema.INBOX,
            values,
            "delivery_id = ?",
            arrayOf(inbox.deliveryId.value),
        ) == 1
    )
    transaction.markChanged()
}

private fun insertAttempt(
    transaction: AndroidDeliveryStore.Transaction,
    attempt: TransferAttempt,
    ownerToken: String,
) {
    transaction.database.insertOrThrow(
        DeliverySchema.TRANSFER_ATTEMPTS,
        null,
        attemptValues(attempt, ownerToken),
    )
    transaction.markChanged()
}

private fun writeAttempt(
    transaction: AndroidDeliveryStore.Transaction,
    attempt: TransferAttempt,
    ownerToken: String,
) {
    val values = attemptValues(attempt, ownerToken).apply { remove("attempt_id") }
    check(
        transaction.database.update(
            DeliverySchema.TRANSFER_ATTEMPTS,
            values,
            "attempt_id = ?",
            arrayOf(attempt.attemptId.value),
        ) == 1
    )
    transaction.markChanged()
}

private fun attemptValues(attempt: TransferAttempt, ownerToken: String): ContentValues =
    ContentValues().apply {
        put("attempt_id", attempt.attemptId.value)
        put("delivery_id", attempt.deliveryId.value)
        put("adapter_id", attempt.context.adapterId)
        put("opportunity_id", attempt.context.opportunityId)
        put("state", attempt.state.name)
        put("reserved_at_ms", attempt.reservedAtMs)
        put("lease_expires_at_ms", attempt.leaseExpiresAtMs)
        put("lease_owner_token", ownerToken)
        putNullable("started_at_ms", attempt.startedAtMs)
        putNullable("link_write_completed_at_ms", attempt.linkWriteCompletedAtMs)
        putNullable(
            "next_hop_accepted_durably_at_ms",
            attempt.nextHopAcceptedDurablyAtMs,
        )
        putNullable(
            "next_hop_acceptance_evidence_id",
            attempt.nextHopAcceptanceEvidenceId?.value,
        )
        putNullable("lease_expired_at_ms", attempt.leaseExpiredAtMs)
        putNullable("finished_at_ms", attempt.finishedAtMs)
        putNullable("failure_reason", attempt.failureReason)
    }

private fun insertAcceptance(
    transaction: AndroidDeliveryStore.Transaction,
    record: NextHopAcceptanceRecord,
) {
    val values = ContentValues().apply {
        put("evidence_id", record.evidenceId.value)
        put("delivery_id", record.deliveryId.value)
        put("transfer_attempt_id", record.transferAttemptId.value)
        put("authority", record.authority)
        put("kind", record.kind.name)
        put("protected_bytes", record.protectedBytes.copyToByteArray())
        putNullable("receipt_id", record.receiptId?.value)
        put("stored_at_ms", record.storedAtMs)
    }
    transaction.database.insertOrThrow(DeliverySchema.ACCEPTANCE_EVIDENCE, null, values)
    transaction.markChanged()
}

private fun insertReceipt(
    transaction: AndroidDeliveryStore.Transaction,
    record: ReceiptRecord,
) {
    val receipt = record.receipt
    val values = ContentValues().apply {
        put("receipt_id", receipt.receiptId.value)
        put("delivery_id", receipt.deliveryId.value)
        put("evidence", receipt.evidence.name)
        put("issuer", receipt.issuer)
        put("verification", receipt.verification.name)
        put("protected_bytes", receipt.protectedBytes.copyToByteArray())
        putNullable("linked_transfer_attempt_id", receipt.linkedTransferAttemptId?.value)
        put("stored_at_ms", record.storedAtMs)
        putNullable("applied_at_ms", record.appliedAtMs)
    }
    transaction.database.insertOrThrow(DeliverySchema.RECEIPTS, null, values)
    transaction.markChanged()
}

private fun updateReceiptAppliedAt(
    transaction: AndroidDeliveryStore.Transaction,
    receiptId: ReceiptId,
    nowMs: Long,
) {
    val values = ContentValues().apply { put("applied_at_ms", nowMs) }
    check(
        transaction.database.update(
            DeliverySchema.RECEIPTS,
            values,
            "receipt_id = ?",
            arrayOf(receiptId.value),
        ) == 1
    )
    transaction.markChanged()
}

private fun writeTombstone(
    transaction: AndroidDeliveryStore.Transaction,
    tombstone: Tombstone,
) {
    val values = ContentValues().apply {
        put("delivery_id", tombstone.deliveryId.value)
        put("reason", tombstone.reason.name)
        put("created_at_ms", tombstone.createdAtMs)
        put("expires_at_ms", tombstone.expiresAtMs)
    }
    transaction.database.insertWithOnConflict(
        DeliverySchema.TOMBSTONES,
        null,
        values,
        SQLiteDatabase.CONFLICT_REPLACE,
    ).also { check(it != -1L) }
    transaction.markChanged()
}

private fun nextAttemptSequence(
    transaction: AndroidDeliveryStore.Transaction,
): Long {
    val sequence = DatabaseUtils.longForQuery(
        transaction.database,
        "SELECT long_value FROM ${DeliverySchema.STORE_METADATA} WHERE key = ?",
        arrayOf(DeliverySchema.NEXT_ATTEMPT_SEQUENCE),
    )
    val values = ContentValues().apply { put("long_value", sequence + 1) }
    check(
        transaction.database.update(
            DeliverySchema.STORE_METADATA,
            values,
            "key = ?",
            arrayOf(DeliverySchema.NEXT_ATTEMPT_SEQUENCE),
        ) == 1
    )
    transaction.markChanged()
    return sequence
}

private fun countRows(
    database: SQLiteDatabase,
    table: String,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
): Long = DatabaseUtils.queryNumEntries(database, table, selection, selectionArgs)

private fun DeliveryStoreEvent.typeName(): String = when (this) {
    is DeliveryStoreEvent.DurablyStored -> "DURABLY_STORED"
    is DeliveryStoreEvent.ApplicationDeliveryAvailable -> "APPLICATION_DELIVERY_AVAILABLE"
    is DeliveryStoreEvent.ApplicationDelivered -> "APPLICATION_DELIVERED"
    is DeliveryStoreEvent.ReceiptRecorded -> "RECEIPT_RECORDED"
    is DeliveryStoreEvent.NextHopAcceptedDurably -> "NEXT_HOP_ACCEPTED_DURABLY"
    is DeliveryStoreEvent.DeliveryExpired -> "DELIVERY_EXPIRED"
}

private fun NextHopAcceptanceRecord.matches(
    acceptance: VerifiedNextHopAcceptance,
    receipt: ReceiptInput?,
): Boolean =
    deliveryId == acceptance.deliveryId &&
        transferAttemptId == acceptance.transferAttemptId &&
        evidenceId == acceptance.evidenceId &&
        authority == acceptance.authority &&
        kind == acceptance.kind &&
        protectedBytes == acceptance.protectedBytes &&
        receiptId == receipt?.receiptId

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.blob(name: String): ByteArray = getBlob(getColumnIndexOrThrow(name))
private fun Cursor.nullableString(name: String): String? =
    getColumnIndexOrThrow(name).let { index -> if (isNull(index)) null else getString(index) }
private fun Cursor.nullableLong(name: String): Long? =
    getColumnIndexOrThrow(name).let { index -> if (isNull(index)) null else getLong(index) }

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { "%02x".format(it) }

private fun secureLeaseToken(): String {
    val bytes = ByteArray(32)
    SecureRandomHolder.instance.nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private object SecureRandomHolder {
    val instance = SecureRandom()
}

private fun safeAdd(value: Long, positiveDuration: Long): Long {
    require(positiveDuration > 0) { "Duration must be positive" }
    if (value > Long.MAX_VALUE - positiveDuration) {
        throw IllegalArgumentException("Timestamp overflow")
    }
    return value + positiveDuration
}

private fun requireBoundedAndroidStoreText(label: String, value: String, maxBytes: Int) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value == value.trim()) { "$label must not contain surrounding whitespace" }
    require(value.encodeToByteArray().size <= maxBytes) { "$label exceeds $maxBytes bytes" }
}
