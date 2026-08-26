package com.openmesh.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryStoreConformanceTest {

    @Test
    fun `ingest atomically commits object lifecycle inbox and outbox before notification`() =
        runBlocking {
            val store = InMemoryDeliveryStore()
            val delivery = deliveryObject(id = "delivery-local")
            val observedSnapshots = Channel<DeliverySnapshot>(Channel.UNLIMITED)
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                store.postCommitEvents.collect { outbox ->
                    if (outbox.event is DeliveryStoreEvent.ApplicationDeliveryAvailable) {
                        observedSnapshots.send(store.snapshot(outbox.event.deliveryId))
                    }
                }
            }

            val result = store.ingest(
                ingest = ingest(delivery, destinationIsLocal = true),
                nowMs = 100,
            )

            assertTrue(result is DeliveryIngestResult.Stored)
            val stored = result as DeliveryIngestResult.Stored
            assertEquals(DeliveryState.APP_PENDING, stored.record.state)
            assertEquals(2, stored.committedEvents.size)
            val snapshotAtNotification = withTimeout(1_000) { observedSnapshots.receive() }
            assertNotNull(snapshotAtNotification.deliveryObject)
            assertEquals(DeliveryState.APP_PENDING, snapshotAtNotification.deliveryRecord?.state)
            assertEquals(InboxState.PENDING, snapshotAtNotification.inboxRecord?.state)
            assertEquals(2, store.listOutbox().size)
            assertArrayEquals(
                delivery.canonicalBytes.copyToByteArray(),
                snapshotAtNotification.deliveryObject?.canonicalBytes?.copyToByteArray(),
            )

            observer.cancelAndJoin()
        }

    @Test
    fun `concurrent duplicate ingestion creates one canonical truth`() = runBlocking {
        val store = InMemoryDeliveryStore()
        val request = ingest(deliveryObject(id = "delivery-race"))
        val start = CompletableDeferred<Unit>()
        val writes = List(32) {
            async(Dispatchers.Default) {
                start.await()
                store.ingest(request, nowMs = 200)
            }
        }

        start.complete(Unit)
        val results = writes.awaitAll()

        assertEquals(1, results.count { it is DeliveryIngestResult.Stored })
        assertEquals(31, results.count { it is DeliveryIngestResult.Duplicate })
        val snapshot = store.snapshot(request.deliveryObject.deliveryId)
        assertNotNull(snapshot.deliveryObject)
        assertEquals(DeliveryState.WAITING, snapshot.deliveryRecord?.state)
        assertEquals(1, store.listOutbox().size)
    }

    @Test
    fun `same canonical identity with different bytes fails closed`() = runBlocking {
        val store = InMemoryDeliveryStore()
        val first = deliveryObject(id = "delivery-conflict", bytes = byteArrayOf(1, 2, 3))
        val conflicting = deliveryObject(id = "delivery-conflict", bytes = byteArrayOf(9, 8, 7))
        store.ingest(ingest(first), nowMs = 100)

        expectSuspendThrows(CanonicalDeliveryConflictException::class.java) {
            store.ingest(ingest(conflicting), nowMs = 101)
        }

        val snapshot = store.snapshot(first.deliveryId)
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            snapshot.deliveryObject?.canonicalBytes?.copyToByteArray(),
        )
        assertEquals(1, store.listOutbox().size)
    }

    @Test
    fun `failed commit returns no DeliveryHandle and emits no acceptance`() = runBlocking {
        val store = InMemoryDeliveryStore(
            beforeCommit = { kind ->
                if (kind == InMemoryDeliveryStore.CommitKind.INGEST) throw SimulatedCrash()
            },
        )
        val notifications = Channel<OutboxRecord>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            store.postCommitEvents.collect { notifications.send(it) }
        }
        val deliveryId = DeliveryId("delivery-failed-commit")
        val service = DeliveryService { destination, payload, policy ->
            val result = store.ingest(
                DeliveryIngest(
                    deliveryObject = DeliveryObject.copyOf(
                        deliveryId = deliveryId,
                        destination = destination,
                        createdAtMs = 100,
                        expiresAtMs = 100 + policy.lifetimeMs,
                        canonicalBytes = payload,
                    ),
                    destinationIsLocal = false,
                    provenance = LOCAL_PROVENANCE,
                ),
                nowMs = 100,
            )
            check(result is DeliveryIngestResult.Stored)
            DeliveryHandle(
                requestId = DeliveryHandle.RequestId(deliveryId.value),
                observations = flowOf(DeliveryHandle.Observation.DurablyStoredLocally),
            )
        }
        var returnedHandle: DeliveryHandle? = null

        expectSuspendThrows(SimulatedCrash::class.java) {
            returnedHandle = service.deliver(
                destination = EndpointId("dtn://example.test/inbox/failure"),
                payload = byteArrayOf(1),
                policy = DeliveryPolicy(lifetimeMs = 1_000),
            )
        }

        assertNull(returnedHandle)
        assertNull(store.snapshot(deliveryId).deliveryRecord)
        assertTrue(store.listOutbox().isEmpty())
        assertTrue(notifications.tryReceive().isFailure)
        observer.cancelAndJoin()
    }

    @Test
    fun `committed checkpoint recovers object state outbox and active lease`() = runBlocking {
        val store = InMemoryDeliveryStore(leaseTokenSource = { "unpredictable-test-token" })
        val delivery = deliveryObject(id = "delivery-recovery")
        store.ingest(ingest(delivery), nowMs = 100)
        val reservation = store.reserveTransfer(
            TransferReservation(
                deliveryId = delivery.deliveryId,
                context = transferContext(),
                leaseDurationMs = 1_000,
            ),
            nowMs = 110,
        ) as TransferReservationResult.Acquired
        store.markTransferStarted(reservation.lease, nowMs = 120)
        val expectedSnapshot = store.snapshot(delivery.deliveryId)
        val expectedOutbox = store.listOutbox()

        val recovered = InMemoryDeliveryStore.recover(store.checkpoint())

        assertEquals(expectedSnapshot, recovered.snapshot(delivery.deliveryId))
        assertEquals(expectedOutbox, recovered.listOutbox())
        val continued = recovered.recordLinkWriteCompleted(reservation.lease, nowMs = 130)
        assertEquals(TransferAttemptState.LINK_WRITE_COMPLETED, continued.state)
    }

    @Test
    fun `duplicate receipt is idempotent and conflicting reuse is rejected`() = runBlocking {
        val store = InMemoryDeliveryStore()
        val delivery = deliveryObject(id = "delivery-receipt")
        store.ingest(ingest(delivery), nowMs = 100)
        val receipt = ReceiptInput.copyOf(
            receiptId = ReceiptId("receipt-1"),
            deliveryId = delivery.deliveryId,
            evidence = ReceiptEvidence.DESTINATION_STORED,
            issuer = "endpoint-authority-1",
            verification = ReceiptVerification.AUTHENTICATED_AND_AUTHORIZED,
            protectedBytes = byteArrayOf(1, 2, 3),
        )

        val first = store.recordReceipt(receipt, nowMs = 200)
        val duplicate = store.recordReceipt(receipt, nowMs = 201)

        assertTrue(first is ReceiptWriteResult.Stored)
        assertTrue(duplicate is ReceiptWriteResult.Duplicate)
        assertEquals(1, store.snapshot(delivery.deliveryId).receipts.size)
        assertEquals(2, store.listOutbox().size)

        val conflicting = ReceiptInput.copyOf(
            receiptId = receipt.receiptId,
            deliveryId = delivery.deliveryId,
            evidence = receipt.evidence,
            issuer = receipt.issuer,
            verification = receipt.verification,
            protectedBytes = byteArrayOf(9),
        )
        expectSuspendThrows(ReceiptIdentityConflictException::class.java) {
            store.recordReceipt(conflicting, nowMs = 202)
        }
        assertEquals(1, store.snapshot(delivery.deliveryId).receipts.size)
        assertEquals(2, store.listOutbox().size)
    }

    @Test
    fun `expired lease preserves old attempt and permits a new reservation`() = runBlocking {
        val store = InMemoryDeliveryStore()
        val delivery = deliveryObject(id = "delivery-lease")
        store.ingest(ingest(delivery), nowMs = 100)
        val request = TransferReservation(
            deliveryId = delivery.deliveryId,
            context = transferContext(),
            leaseDurationMs = 10,
        )

        val first = store.reserveTransfer(request, nowMs = 110)
            as TransferReservationResult.Acquired
        val busy = store.reserveTransfer(request, nowMs = 119)
        val resumed = store.reserveTransfer(request, nowMs = 120)
            as TransferReservationResult.Acquired

        assertTrue(busy is TransferReservationResult.Busy)
        assertNotEquals(first.attempt.attemptId, resumed.attempt.attemptId)
        val attempts = store.snapshot(delivery.deliveryId).transferAttempts
        assertEquals(2, attempts.size)
        assertEquals(
            setOf(TransferAttemptState.LEASE_EXPIRED, TransferAttemptState.RESERVED),
            attempts.mapTo(mutableSetOf()) { it.state },
        )
        expectSuspendThrows(StaleTransferLeaseException::class.java) {
            store.markTransferStarted(first.lease, nowMs = 121)
        }
    }

    @Test
    fun `transfer lifecycle is independent and next hop acceptance is not delivery`() = runBlocking {
        val store = InMemoryDeliveryStore()
        val delivery = deliveryObject(id = "delivery-next-hop")
        store.ingest(ingest(delivery), nowMs = 100)
        val reserved = store.reserveTransfer(
            TransferReservation(
                deliveryId = delivery.deliveryId,
                context = transferContext(),
                leaseDurationMs = 1_000,
            ),
            nowMs = 110,
        ) as TransferReservationResult.Acquired

        store.markTransferStarted(reserved.lease, nowMs = 120)
        val duringTransfer = store.snapshot(delivery.deliveryId)
        assertEquals(DeliveryState.WAITING, duringTransfer.deliveryRecord?.state)
        assertEquals(
            TransferAttemptState.TRANSFERRING,
            duringTransfer.transferAttempts.single().state,
        )
        store.recordLinkWriteCompleted(reserved.lease, nowMs = 130)
        store.recordNextHopAccepted(reserved.lease, nowMs = 140)

        val accepted = store.snapshot(delivery.deliveryId)
        assertEquals(DeliveryState.WAITING, accepted.deliveryRecord?.state)
        assertEquals(
            TransferAttemptState.NEXT_HOP_ACCEPTED,
            accepted.transferAttempts.single().state,
        )
        assertFalse(accepted.deliveryRecord?.state == DeliveryState.APP_DELIVERED)
    }

    @Test
    fun `tombstone outlives expired object bytes and blocks replay during retention`() =
        runBlocking {
            val store = InMemoryDeliveryStore()
            val delivery = deliveryObject(
                id = "delivery-tombstone",
                createdAtMs = 100,
                expiresAtMs = 200,
            )
            store.ingest(ingest(delivery), nowMs = 100)

            assertEquals(1, store.expireObjects(nowMs = 200, tombstoneRetentionMs = 1_000))
            val expired = store.snapshot(delivery.deliveryId)
            assertNull(expired.deliveryObject)
            assertEquals(DeliveryState.EXPIRED, expired.deliveryRecord?.state)
            assertEquals(TombstoneReason.EXPIRED, expired.tombstone?.reason)

            val replay = deliveryObject(
                id = delivery.deliveryId.value,
                createdAtMs = 200,
                expiresAtMs = 5_000,
            )
            val rejected = store.ingest(ingest(replay), nowMs = 201)
            assertTrue(rejected is DeliveryIngestResult.RejectedByTombstone)
            assertNull(store.snapshot(delivery.deliveryId).deliveryObject)

            assertEquals(1, store.pruneExpiredTombstones(nowMs = 1_200, limit = 10))
            val pruned = store.snapshot(delivery.deliveryId)
            assertNull(pruned.deliveryObject)
            assertNull(pruned.deliveryRecord)
            assertNull(pruned.tombstone)
        }

    @Test
    fun `application acknowledgement and its outbox fact commit together`() = runBlocking {
        var failOn: InMemoryDeliveryStore.CommitKind? = null
        val store = InMemoryDeliveryStore(
            beforeCommit = { kind -> if (kind == failOn) throw SimulatedCrash() },
        )
        val delivery = deliveryObject(id = "delivery-app-ack")
        store.ingest(ingest(delivery, destinationIsLocal = true), nowMs = 100)
        val baselineOutbox = store.listOutbox()
        failOn = InMemoryDeliveryStore.CommitKind.ACKNOWLEDGE_APPLICATION

        expectSuspendThrows(SimulatedCrash::class.java) {
            store.acknowledgeApplicationDelivery(delivery.deliveryId, nowMs = 200)
        }

        val afterFailure = store.snapshot(delivery.deliveryId)
        assertEquals(DeliveryState.APP_PENDING, afterFailure.deliveryRecord?.state)
        assertEquals(InboxState.PENDING, afterFailure.inboxRecord?.state)
        assertEquals(baselineOutbox, store.listOutbox())

        failOn = null
        store.acknowledgeApplicationDelivery(delivery.deliveryId, nowMs = 201)
        val committed = store.snapshot(delivery.deliveryId)
        assertEquals(DeliveryState.APP_DELIVERED, committed.deliveryRecord?.state)
        assertEquals(InboxState.DELIVERED, committed.inboxRecord?.state)
        assertTrue(
            store.listOutbox().last().event is DeliveryStoreEvent.ApplicationDelivered
        )
    }

    @Test
    fun `admission and outbox bounds fail the whole transaction`() = runBlocking {
        val objectBoundedStore = InMemoryDeliveryStore(
            limits = DeliveryStoreLimits(maxObjectBytes = 2),
        )
        val oversized = deliveryObject(id = "delivery-too-large", bytes = byteArrayOf(1, 2, 3))

        expectSuspendThrows(DeliveryStoreAdmissionException::class.java) {
            objectBoundedStore.ingest(ingest(oversized), nowMs = 100)
        }
        assertNull(objectBoundedStore.snapshot(oversized.deliveryId).deliveryRecord)
        assertTrue(objectBoundedStore.listOutbox().isEmpty())

        val outboxBoundedStore = InMemoryDeliveryStore(
            limits = DeliveryStoreLimits(maxOutboxRecords = 1),
        )
        val local = deliveryObject(id = "delivery-outbox-bound")
        expectSuspendThrows(DeliveryStoreAdmissionException::class.java) {
            // Local ingest needs DurablyStored + ApplicationDeliveryAvailable.
            outboxBoundedStore.ingest(
                ingest(local, destinationIsLocal = true),
                nowMs = 100,
            )
        }
        assertNull(outboxBoundedStore.snapshot(local.deliveryId).deliveryRecord)
        assertTrue(outboxBoundedStore.listOutbox().isEmpty())
    }

    private fun deliveryObject(
        id: String,
        bytes: ByteArray = "opaque-$id".encodeToByteArray(),
        createdAtMs: Long = 100,
        expiresAtMs: Long = 10_000,
    ): DeliveryObject = DeliveryObject.copyOf(
        deliveryId = DeliveryId(id),
        destination = EndpointId("dtn://example.test/inbox/alice"),
        source = EndpointId("dtn://example.test/outbox/bob"),
        createdAtMs = createdAtMs,
        expiresAtMs = expiresAtMs,
        canonicalBytes = bytes,
    )

    private fun ingest(
        deliveryObject: DeliveryObject,
        destinationIsLocal: Boolean = false,
    ): DeliveryIngest = DeliveryIngest(
        deliveryObject = deliveryObject,
        destinationIsLocal = destinationIsLocal,
        provenance = LOCAL_PROVENANCE,
    )

    private fun transferContext(): TransferContext = TransferContext(
        adapterId = "test-adapter",
        opportunityId = "test-opportunity",
    )

    private suspend fun <T : Throwable> expectSuspendThrows(
        type: Class<T>,
        block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (thrown: Throwable) {
            if (type.isInstance(thrown)) return type.cast(thrown)
            throw AssertionError("Expected ${type.name}, got ${thrown::class.java.name}", thrown)
        }
        throw AssertionError("Expected ${type.name} to be thrown")
    }

    private class SimulatedCrash : RuntimeException("simulated failure before commit")

    private companion object {
        val LOCAL_PROVENANCE = IngressProvenance(
            kind = IngressProvenance.Kind.LOCAL_APPLICATION,
            reference = "test-application",
        )
    }
}
