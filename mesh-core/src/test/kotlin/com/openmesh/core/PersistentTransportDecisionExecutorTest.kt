package com.openmesh.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentTransportDecisionExecutorTest {

    @Test
    fun `transfer context binds exact revision while legacy context remains unversioned`() {
        val revisionOne = opportunity(revision = TransportOpportunityRevision(1)).reference
        val revisionTwo = opportunity(revision = TransportOpportunityRevision(2)).reference
        val first = TransferContext.forOpportunity(revisionOne)
        val second = TransferContext.forOpportunity(revisionTwo)
        val legacy = TransferContext(ADAPTER_ID.value, OPPORTUNITY_ID.value)

        assertEquals(ADAPTER_ID.value, first.adapterId)
        assertEquals(OPPORTUNITY_ID.value, first.opportunityId)
        assertEquals(1L, first.opportunityRevision)
        assertEquals(revisionOne, first.exactOpportunityReferenceOrNull())
        assertNotEquals(first, second)
        assertNull(legacy.opportunityRevision)
        assertNull(legacy.exactOpportunityReferenceOrNull())
        expectThrows<IllegalArgumentException> {
            TransferContext(ADAPTER_ID.value, OPPORTUNITY_ID.value, 0)
        }
    }

    @Test
    fun `revisioned contexts share busy semantics only for the exact same binding`() = runBlocking {
        val store = waitingStore("revisioned-busy")
        val deliveryId = DeliveryId("revisioned-busy")
        val revisionOne = TransferContext.forOpportunity(
            opportunity(revision = TransportOpportunityRevision(1)).reference,
        )
        val revisionTwo = TransferContext.forOpportunity(
            opportunity(revision = TransportOpportunityRevision(2)).reference,
        )

        val acquired = store.reserveTransfer(
            TransferReservation(deliveryId, revisionOne, 1_000),
            nowMs = 110,
        )
        val busy = store.reserveTransfer(
            TransferReservation(deliveryId, revisionOne, 1_000),
            nowMs = 111,
        )
        val differentRevision = store.reserveTransfer(
            TransferReservation(deliveryId, revisionTwo, 1_000),
            nowMs = 112,
        )

        assertTrue(acquired is TransferReservationResult.Acquired)
        assertTrue(busy is TransferReservationResult.Busy)
        assertTrue(differentRevision is TransferReservationResult.Acquired)
        assertEquals(2, store.snapshot(deliveryId).transferAttempts.size)
    }

    @Test
    fun `decision subject mismatch cannot execute another delivery`() = runBlocking {
        val store = InMemoryDeliveryStore()
        val deliveryA = deliveryObject("subject-delivery-a")
        val deliveryB = deliveryObject("subject-delivery-b")
        store.ingest(ingest(deliveryA), nowMs = 100)
        store.ingest(ingest(deliveryB), nowMs = 100)
        val tracking = TrackingDeliveryStore(store)
        val adapter = RecordingAdapter()
        val executor = executor(tracking, adapter)
        val bindingA = DeliveryTransportDecisionBinding.bind(deliveryA.deliveryId, SUBJECT_ID)
        val bindingB = DeliveryTransportDecisionBinding.bind(
            deliveryB.deliveryId,
            TransportDecisionSubjectId("different-subject"),
        )

        val mismatch = executor.execute(bindingB, transferDecision())

        assertTrue(mismatch is PersistentTransportExecutionResult.SubjectMismatch)
        assertEquals(0, tracking.mutationCalls)
        assertEquals(0, adapter.calls)
        assertTrue(store.snapshot(deliveryB.deliveryId).transferAttempts.isEmpty())

        val matching = executor.execute(bindingA, transferDecision())

        assertTrue(matching is PersistentTransportExecutionResult.LinkWriteCompleted)
        assertEquals(1, adapter.calls)
        assertEquals(
            TransferAttemptState.LINK_WRITE_COMPLETED,
            store.snapshot(deliveryA.deliveryId).transferAttempts.single().state,
        )
    }

    @Test
    fun `wait performs no store mutation and no adapter call`() = runBlocking {
        val backing = waitingStore("executor-wait")
        val tracking = TrackingDeliveryStore(backing)
        val adapter = RecordingAdapter()
        val before = backing.snapshot(DeliveryId("executor-wait"))
        val result = executor(tracking, adapter).execute(
            DeliveryId("executor-wait"),
            waitDecision(),
        )

        assertTrue(result is PersistentTransportExecutionResult.Wait)
        assertEquals(0, tracking.mutationCalls)
        assertEquals(0, adapter.calls)
        assertEquals(before, backing.snapshot(DeliveryId("executor-wait")))
    }

    @Test
    fun `missing adapter creates no transfer attempt and performs no fallback`() = runBlocking {
        val store = waitingStore("executor-no-adapter")
        val result = executor(store, adapters = TransportAdapterSet.Empty).execute(
            DeliveryId("executor-no-adapter"),
            transferDecision(),
        )

        assertTrue(result is PersistentTransportExecutionResult.AdapterUnavailable)
        assertTrue(store.snapshot(DeliveryId("executor-no-adapter")).transferAttempts.isEmpty())
    }

    @Test
    fun `not eligible reserves nothing executable and never calls adapter`() = runBlocking {
        val store = InMemoryDeliveryStore()
        val delivery = deliveryObject("executor-not-eligible")
        store.ingest(ingest(delivery, destinationIsLocal = true), nowMs = 100)
        val adapter = RecordingAdapter()

        val result = executor(store, adapter).execute(delivery.deliveryId, transferDecision())

        assertTrue(result is PersistentTransportExecutionResult.NotEligible)
        assertEquals(0, adapter.calls)
        assertTrue(store.snapshot(delivery.deliveryId).transferAttempts.isEmpty())
    }

    @Test
    fun `same exact context is busy and cannot start a second adapter call`() = runBlocking {
        val store = waitingStore("executor-busy")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = RecordingAdapter { request ->
            entered.complete(Unit)
            release.await()
            TransportTransferResult.CompletedLocally(request.transferId, occurredAtMs = 150)
        }
        val executor = executor(store, adapter)
        val decision = transferDecision()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            executor.execute(DeliveryId("executor-busy"), decision)
        }
        entered.await()

        val second = executor.execute(DeliveryId("executor-busy"), decision)
        assertTrue(second is PersistentTransportExecutionResult.Busy)
        assertEquals(1, adapter.calls)

        release.complete(Unit)
        assertTrue(first.await() is PersistentTransportExecutionResult.LinkWriteCompleted)
        assertEquals(1, store.snapshot(DeliveryId("executor-busy")).transferAttempts.size)
    }

    @Test
    fun `canonical bytes and exact revision reach adapter only after transferring commit`() =
        runBlocking {
            val bytes = byteArrayOf(0x00, 0x7f, 0x55, 0x01)
            val backing = waitingStore("executor-byte-exact", bytes)
            val operations = mutableListOf<String>()
            val tracking = TrackingDeliveryStore(backing, operations)
            val adapter = RecordingAdapter(
                onCall = { operations += "adapter" },
            ) { request ->
                assertEquals(REFERENCE, request.opportunity)
                assertArrayEquals(bytes, request.bytes.copyToByteArray())
                TransportTransferResult.CompletedLocally(request.transferId, occurredAtMs = 150)
            }

            val result = executor(tracking, adapter).execute(
                DeliveryId("executor-byte-exact"),
                transferDecision(),
            ) as PersistentTransportExecutionResult.LinkWriteCompleted

            assertEquals(listOf("reserve", "started", "adapter", "link-write"), operations)
            assertEquals(TransferAttemptState.LINK_WRITE_COMPLETED, result.attempt.state)
            assertEquals(TransferContext.forOpportunity(REFERENCE), result.attempt.context)
            assertEquals(REFERENCE, result.attempt.context.exactOpportunityReferenceOrNull())
        }

    @Test
    fun `completed locally records only link write and no durable evidence`() = runBlocking {
        val backing = waitingStore("executor-link-only")
        val tracking = TrackingDeliveryStore(backing)
        val adapter = RecordingAdapter()

        val result = executor(tracking, adapter).execute(
            DeliveryId("executor-link-only"),
            transferDecision(),
        )
        val snapshot = backing.snapshot(DeliveryId("executor-link-only"))

        assertTrue(result is PersistentTransportExecutionResult.LinkWriteCompleted)
        assertEquals(TransferAttemptState.LINK_WRITE_COMPLETED, snapshot.transferAttempts.single().state)
        assertEquals(DeliveryState.WAITING, snapshot.deliveryRecord?.state)
        assertTrue(snapshot.nextHopAcceptances.isEmpty())
        assertTrue(snapshot.receipts.isEmpty())
        assertEquals(0, tracking.forbiddenEvidenceCalls)
        assertFalse(backing.listOutbox().any {
            it.event is DeliveryStoreEvent.NextHopAcceptedDurably ||
                it.event is DeliveryStoreEvent.ApplicationDelivered ||
                it.event is DeliveryStoreEvent.ReceiptRecorded
        })
    }

    @Test
    fun `opportunity unavailable settles failed with stable reason`() = runBlocking {
        val store = waitingStore("executor-unavailable")
        val adapter = RecordingAdapter { request ->
            TransportTransferResult.OpportunityUnavailable(
                transferId = request.transferId,
                occurredAtMs = 150,
                opportunity = request.opportunity,
            )
        }

        val result = executor(store, adapter).execute(
            DeliveryId("executor-unavailable"),
            transferDecision(),
        ) as PersistentTransportExecutionResult.Failed

        assertEquals(PersistentTransportFailureReason.OPPORTUNITY_UNAVAILABLE, result.failureReason)
        assertEquals(TransferAttemptState.FAILED, result.attempt.state)
        assertEquals(PersistentTransportFailureReason.OPPORTUNITY_UNAVAILABLE.name, result.attempt.failureReason)
    }

    @Test
    fun `typed local failure settles failed without using arbitrary detail`() = runBlocking {
        val store = waitingStore("executor-local-failure")
        val adapter = RecordingAdapter { request ->
            TransportTransferResult.FailedLocally(
                transferId = request.transferId,
                occurredAtMs = 150,
                failure = TransportLocalFailure(
                    code = TransportLocalFailureCode.TIMED_OUT,
                    detail = "diagnostic text that is not state authority",
                ),
            )
        }

        val result = executor(store, adapter).execute(
            DeliveryId("executor-local-failure"),
            transferDecision(),
        ) as PersistentTransportExecutionResult.Failed

        assertEquals(PersistentTransportFailureReason.TRANSPORT_LOCAL_TIMED_OUT, result.failureReason)
        assertEquals(PersistentTransportFailureReason.TRANSPORT_LOCAL_TIMED_OUT.name, result.attempt.failureReason)
    }

    @Test
    fun `mismatched transfer correlation fails closed before link write`() = runBlocking {
        val store = waitingStore("executor-id-mismatch")
        val adapter = RecordingAdapter { _ ->
            TransportTransferResult.CompletedLocally(
                transferId = TransportTransferId("wrong-correlation"),
                occurredAtMs = 150,
            )
        }

        val result = executor(store, adapter).execute(
            DeliveryId("executor-id-mismatch"),
            transferDecision(),
        ) as PersistentTransportExecutionResult.Failed

        assertEquals(
            PersistentTransportFailureReason.ADAPTER_CONTRACT_TRANSFER_ID_MISMATCH,
            result.failureReason,
        )
        assertEquals(TransferAttemptState.FAILED, result.attempt.state)
        assertNull(result.attempt.linkWriteCompletedAtMs)
    }

    @Test
    fun `mismatched unavailable opportunity fails closed`() = runBlocking {
        val store = waitingStore("executor-opportunity-mismatch")
        val otherReference = opportunity(
            opportunityId = TransportOpportunityId("other-opportunity"),
        ).reference
        val adapter = RecordingAdapter { request ->
            TransportTransferResult.OpportunityUnavailable(
                transferId = request.transferId,
                occurredAtMs = 150,
                opportunity = otherReference,
            )
        }

        val result = executor(store, adapter).execute(
            DeliveryId("executor-opportunity-mismatch"),
            transferDecision(),
        ) as PersistentTransportExecutionResult.Failed

        assertEquals(
            PersistentTransportFailureReason.ADAPTER_CONTRACT_OPPORTUNITY_MISMATCH,
            result.failureReason,
        )
        assertEquals(TransferAttemptState.FAILED, result.attempt.state)
    }

    @Test
    fun `adapter exception settles failed then surfaces infrastructure cause`() = runBlocking {
        val store = waitingStore("executor-exception")
        val failure = SimulatedFailure("adapter exploded")
        val adapter = RecordingAdapter { throw failure }

        val thrown = expectSuspendThrows<TransportAdapterExecutionException> {
            executor(store, adapter).execute(
                DeliveryId("executor-exception"),
                transferDecision(),
            )
        }
        val attempt = store.snapshot(DeliveryId("executor-exception")).transferAttempts.single()

        assertSame(failure, thrown.cause)
        assertEquals(attempt.attemptId, thrown.attemptId)
        assertEquals(TransferAttemptState.FAILED, attempt.state)
        assertEquals(PersistentTransportFailureReason.ADAPTER_EXCEPTION.name, attempt.failureReason)
    }

    @Test
    fun `cancellation is rethrown after non-cancellable failed settlement`() = runBlocking {
        val store = waitingStore("executor-cancel")
        val entered = CompletableDeferred<Unit>()
        val cancellation = CancellationException("cancel execution")
        val adapter = RecordingAdapter { _ ->
            entered.complete(Unit)
            awaitCancellation()
        }
        val execution = async(start = CoroutineStart.UNDISPATCHED) {
            executor(store, adapter).execute(
                DeliveryId("executor-cancel"),
                transferDecision(),
            )
        }
        entered.await()
        execution.cancel(cancellation)

        val thrown = expectSuspendThrows<CancellationException> {
            execution.await()
        }
        val attempt = store.snapshot(DeliveryId("executor-cancel")).transferAttempts.single()

        assertEquals(cancellation.message, thrown.message)
        assertEquals(TransferAttemptState.FAILED, attempt.state)
        assertEquals(PersistentTransportFailureReason.EXECUTION_CANCELLED.name, attempt.failureReason)
    }

    @Test
    fun `execution deadline settles failed before lease expiry`() = runBlocking {
        val store = waitingStore("executor-deadline")
        val entered = CompletableDeferred<Unit>()
        val adapter = RecordingAdapter { _ ->
            entered.complete(Unit)
            awaitCancellation()
        }
        val executor = executor(
            store = store,
            adapters = TransportAdapterSet.copyOf(listOf(adapter)),
            leaseDurationMs = 1_000,
            executionTimeoutMs = 25,
            settlementMarginMs = 100,
        )

        val result = executor.execute(
            DeliveryId("executor-deadline"),
            transferDecision(),
        ) as PersistentTransportExecutionResult.Failed
        entered.await()

        assertEquals(PersistentTransportFailureReason.EXECUTION_TIMED_OUT, result.failureReason)
        assertSame(PersistentLocalTransportOutcome.ExecutionTimedOut, result.localOutcome)
        assertEquals(TransferAttemptState.FAILED, result.attempt.state)
        assertTrue(result.attempt.finishedAtMs!! < result.attempt.leaseExpiresAtMs)
        assertNull(result.attempt.linkWriteCompletedAtMs)
        assertEquals(1, adapter.calls)
    }

    @Test
    fun `thirty second transport timeout settles inside configured lease margin`() = runBlocking {
        val store = waitingStore("executor-thirty-second-timeout")
        val adapter = RecordingAdapter { request ->
            TransportTransferResult.FailedLocally(
                transferId = request.transferId,
                occurredAtMs = 30_110,
                failure = TransportLocalFailure(TransportLocalFailureCode.TIMED_OUT),
            )
        }
        val executor = executor(
            store = store,
            adapters = TransportAdapterSet.copyOf(listOf(adapter)),
            clock = SequenceClock(110, 111, 112, 30_111),
            leaseDurationMs = 40_000,
            executionTimeoutMs = 30_000,
            settlementMarginMs = 5_000,
        )

        val result = executor.execute(
            DeliveryId("executor-thirty-second-timeout"),
            transferDecision(),
        ) as PersistentTransportExecutionResult.Failed

        assertEquals(PersistentTransportFailureReason.TRANSPORT_LOCAL_TIMED_OUT, result.failureReason)
        assertEquals(TransferAttemptState.FAILED, result.attempt.state)
        assertEquals(30_111L, result.attempt.finishedAtMs)
        assertTrue(result.attempt.finishedAtMs!! < result.attempt.leaseExpiresAtMs)
    }

    @Test
    fun `execution deadline and settlement margin must fit strictly inside lease`() {
        expectThrows<IllegalArgumentException> {
            PersistentTransportDecisionExecutor(
                deliveryStore = InMemoryDeliveryStore(),
                adapters = TransportAdapterSet.Empty,
                clock = IncrementingClock(),
                transferIdGenerator = PersistentTransportTransferIdGenerator {
                    TransportTransferId("invalid-deadline-correlation")
                },
                leaseDurationMs = 35_000,
                executionTimeoutMs = 30_000,
                settlementMarginMs = 5_000,
            )
        }
    }

    @Test
    fun `store failure before IO prevents adapter call`() = runBlocking {
        val backing = waitingStore("executor-before-io")
        val tracking = TrackingDeliveryStore(backing).apply { failStart = true }
        val adapter = RecordingAdapter()

        expectSuspendThrows<SimulatedFailure> {
            executor(tracking, adapter).execute(
                DeliveryId("executor-before-io"),
                transferDecision(),
            )
        }

        assertEquals(0, adapter.calls)
        assertEquals(
            TransferAttemptState.RESERVED,
            backing.snapshot(DeliveryId("executor-before-io")).transferAttempts.single().state,
        )
    }

    @Test
    fun `store failure after local completion is distinguishable and never reports link write`() =
        runBlocking {
            val backing = waitingStore("executor-settlement")
            val tracking = TrackingDeliveryStore(backing).apply { failLinkWrite = true }
            val adapter = RecordingAdapter()

            val failure = expectSuspendThrows<TransportExecutionSettlementException> {
                executor(tracking, adapter).execute(
                    DeliveryId("executor-settlement"),
                    transferDecision(),
                )
            }
            val attempt = backing.snapshot(DeliveryId("executor-settlement"))
                .transferAttempts.single()

            assertEquals(attempt.attemptId, failure.attemptId)
            assertSame(PersistentLocalTransportOutcome.CompletedLocally, failure.localOutcome)
            assertEquals(1, adapter.calls)
            assertEquals(TransferAttemptState.TRANSFERRING, attempt.state)
            assertNull(attempt.linkWriteCompletedAtMs)
        }

    @Test
    fun `one failed decision never retries or falls back to another adapter`() = runBlocking {
        val store = waitingStore("executor-no-fallback")
        val selected = RecordingAdapter(ADAPTER_ID) { request ->
            TransportTransferResult.FailedLocally(
                request.transferId,
                occurredAtMs = 150,
                failure = TransportLocalFailure(TransportLocalFailureCode.IO_ERROR),
            )
        }
        val fallback = RecordingAdapter(TransportAdapterId("fallback-adapter"))
        val adapters = TransportAdapterSet.copyOf(listOf(selected, fallback))

        val result = executor(store, adapters = adapters).execute(
            DeliveryId("executor-no-fallback"),
            transferDecision(),
        )

        assertTrue(result is PersistentTransportExecutionResult.Failed)
        assertEquals(1, selected.calls)
        assertEquals(0, fallback.calls)
    }

    private suspend fun waitingStore(
        id: String,
        bytes: ByteArray = "canonical-$id".encodeToByteArray(),
    ): InMemoryDeliveryStore = InMemoryDeliveryStore().also { store ->
        store.ingest(ingest(deliveryObject(id, bytes)), nowMs = 100)
    }

    private fun deliveryObject(id: String, bytes: ByteArray = byteArrayOf(1)): DeliveryObject =
        DeliveryObject.copyOf(
            deliveryId = DeliveryId(id),
            destination = EndpointId("dtn://example.test/inbox/executor"),
            createdAtMs = 100,
            expiresAtMs = 10_000,
            canonicalBytes = bytes,
        )

    private fun ingest(
        deliveryObject: DeliveryObject,
        destinationIsLocal: Boolean = false,
    ): DeliveryIngest = DeliveryIngest(
        deliveryObject = deliveryObject,
        destinationIsLocal = destinationIsLocal,
        provenance = IngressProvenance(
            kind = IngressProvenance.Kind.LOCAL_APPLICATION,
            reference = "persistent-executor-test",
        ),
    )

    private fun opportunity(
        adapterId: TransportAdapterId = ADAPTER_ID,
        opportunityId: TransportOpportunityId = OPPORTUNITY_ID,
        revision: TransportOpportunityRevision = TransportOpportunityRevision(7),
    ): TransportOpportunity = TransportOpportunity(
        adapterId = adapterId,
        opportunityId = opportunityId,
        revision = revision,
        observedAtMs = 100,
        validUntilMs = 1_000,
        direction = TransportDirection.SEND_ONLY,
    )

    private fun transferDecision(): TransportDecision.Transfer = TransportDecision.Transfer(
        subjectId = SUBJECT_ID,
        registrySequence = OpportunityRegistrySequence(11),
        opportunity = REFERENCE,
        candidateIndex = 0,
    )

    private fun waitDecision(): TransportDecision.Wait = TransportDecision.Wait(
        subjectId = SUBJECT_ID,
        registrySequence = OpportunityRegistrySequence(10),
        reason = TransportWaitReason.NO_CANDIDATES,
        rejectedCandidates = emptyList(),
    )

    private fun executor(
        store: DeliveryStore,
        adapter: RecordingAdapter,
    ): PersistentTransportDecisionExecutor = executor(
        store = store,
        adapters = TransportAdapterSet.copyOf(listOf(adapter)),
    )

    private fun executor(
        store: DeliveryStore,
        adapters: TransportAdapterSet,
        clock: PersistentTransportExecutionClock = IncrementingClock(),
        leaseDurationMs: Long = 1_000,
        executionTimeoutMs: Long = 100,
        settlementMarginMs: Long = 100,
    ): PersistentTransportDecisionExecutor = PersistentTransportDecisionExecutor(
        deliveryStore = store,
        adapters = adapters,
        clock = clock,
        transferIdGenerator = PersistentTransportTransferIdGenerator {
            TransportTransferId("bounded-local-correlation")
        },
        leaseDurationMs = leaseDurationMs,
        executionTimeoutMs = executionTimeoutMs,
        settlementMarginMs = settlementMarginMs,
    )

    private suspend fun PersistentTransportDecisionExecutor.execute(
        deliveryId: DeliveryId,
        decision: TransportDecision,
    ): PersistentTransportExecutionResult = execute(
        DeliveryTransportDecisionBinding.bind(deliveryId, SUBJECT_ID),
        decision,
    )

    private class IncrementingClock(private var now: Long = 110) :
        PersistentTransportExecutionClock {
        override fun nowMs(): Long = now++
    }

    private class SequenceClock(vararg values: Long) : PersistentTransportExecutionClock {
        private val remaining = ArrayDeque(values.toList())

        override fun nowMs(): Long = checkNotNull(remaining.removeFirstOrNull()) {
            "No timestamp remains in the deterministic clock"
        }
    }

    private class RecordingAdapter(
        override val adapterId: TransportAdapterId = ADAPTER_ID,
        private val onCall: () -> Unit = {},
        private val behavior: suspend (TransportTransferRequest) -> TransportTransferResult =
            { request ->
                TransportTransferResult.CompletedLocally(
                    request.transferId,
                    occurredAtMs = 150,
                )
            },
    ) : TransportAdapter {
        override val events: Flow<TransportEvent> = emptyFlow()
        var calls: Int = 0
            private set

        override suspend fun start() = Unit
        override suspend fun stop() = Unit

        override suspend fun transfer(
            request: TransportTransferRequest,
        ): TransportTransferResult {
            calls += 1
            onCall()
            return behavior(request)
        }
    }

    private class TrackingDeliveryStore(
        private val delegate: DeliveryStore,
        private val operations: MutableList<String> = mutableListOf(),
    ) : DeliveryStore by delegate {
        var mutationCalls: Int = 0
            private set
        var forbiddenEvidenceCalls: Int = 0
            private set
        var failStart: Boolean = false
        var failLinkWrite: Boolean = false

        override suspend fun reserveTransfer(
            reservation: TransferReservation,
            nowMs: Long,
        ): TransferReservationResult {
            mutationCalls += 1
            operations += "reserve"
            return delegate.reserveTransfer(reservation, nowMs)
        }

        override suspend fun markTransferStarted(
            lease: TransferLease,
            nowMs: Long,
        ): TransferAttempt {
            mutationCalls += 1
            operations += "started"
            if (failStart) throw SimulatedFailure("start commit failed")
            return delegate.markTransferStarted(lease, nowMs)
        }

        override suspend fun recordLinkWriteCompleted(
            lease: TransferLease,
            nowMs: Long,
        ): TransferAttempt {
            mutationCalls += 1
            operations += "link-write"
            if (failLinkWrite) throw SimulatedFailure("link-write commit failed")
            return delegate.recordLinkWriteCompleted(lease, nowMs)
        }

        override suspend fun recordTransferFailure(
            lease: TransferLease,
            reason: String,
            nowMs: Long,
        ): TransferAttempt {
            mutationCalls += 1
            operations += "failure"
            return delegate.recordTransferFailure(lease, reason, nowMs)
        }

        override suspend fun recordNextHopAcceptedDurably(
            acceptance: VerifiedNextHopAcceptance,
            nowMs: Long,
        ): NextHopAcceptanceWriteResult {
            forbiddenEvidenceCalls += 1
            return delegate.recordNextHopAcceptedDurably(acceptance, nowMs)
        }

        override suspend fun recordReceipt(
            receipt: ReceiptInput,
            nowMs: Long,
        ): ReceiptWriteResult {
            forbiddenEvidenceCalls += 1
            return delegate.recordReceipt(receipt, nowMs)
        }

        override suspend fun acknowledgeApplicationDelivery(
            deliveryId: DeliveryId,
            nowMs: Long,
        ): DeliveryRecord {
            forbiddenEvidenceCalls += 1
            return delegate.acknowledgeApplicationDelivery(deliveryId, nowMs)
        }
    }

    private suspend inline fun <reified T : Throwable> expectSuspendThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (thrown: Throwable) {
            if (thrown is T) return thrown
            throw AssertionError(
                "Expected ${T::class.java.name}, got ${thrown::class.java.name}",
                thrown,
            )
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit) {
        try {
            block()
        } catch (thrown: Throwable) {
            if (thrown is T) return
            throw AssertionError(
                "Expected ${T::class.java.name}, got ${thrown::class.java.name}",
                thrown,
            )
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }

    private class SimulatedFailure(message: String) : RuntimeException(message)

    private companion object {
        val ADAPTER_ID = TransportAdapterId("executor-adapter")
        val OPPORTUNITY_ID = TransportOpportunityId("executor-opportunity")
        val REFERENCE = TransportOpportunityReference(
            TransportOpportunityKey(ADAPTER_ID, OPPORTUNITY_ID),
            TransportOpportunityRevision(7),
        )
        val SUBJECT_ID = TransportDecisionSubjectId("executor-subject")
    }
}
