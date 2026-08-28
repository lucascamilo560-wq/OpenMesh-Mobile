package com.openmesh.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openmesh.core.AcceptanceEvidenceId
import com.openmesh.core.CanonicalDeliveryConflictException
import com.openmesh.core.DeliveryId
import com.openmesh.core.DeliveryIngest
import com.openmesh.core.DeliveryIngestResult
import com.openmesh.core.DeliveryObject
import com.openmesh.core.DeliveryState
import com.openmesh.core.DeliveryStoreAdmissionException
import com.openmesh.core.DeliveryStoreEvent
import com.openmesh.core.DeliveryStoreLimits
import com.openmesh.core.EndpointId
import com.openmesh.core.InboxState
import com.openmesh.core.IngressProvenance
import com.openmesh.core.NextHopAcceptanceEvidenceKind
import com.openmesh.core.NextHopAcceptanceWriteResult
import com.openmesh.core.ReceiptEvidence
import com.openmesh.core.ReceiptId
import com.openmesh.core.ReceiptIdentityConflictException
import com.openmesh.core.ReceiptInput
import com.openmesh.core.ReceiptVerification
import com.openmesh.core.ReceiptWriteResult
import com.openmesh.core.StaleTransferLeaseException
import com.openmesh.core.TombstoneReason
import com.openmesh.core.TransferAttemptId
import com.openmesh.core.TransferAttemptState
import com.openmesh.core.TransferContext
import com.openmesh.core.TransportAdapterId
import com.openmesh.core.TransportOpportunityId
import com.openmesh.core.TransportOpportunityKey
import com.openmesh.core.TransportOpportunityReference
import com.openmesh.core.TransportOpportunityRevision
import com.openmesh.core.TransferReservation
import com.openmesh.core.TransferReservationResult
import com.openmesh.core.VerifiedNextHopAcceptance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AndroidDeliveryStoreTest {
    private lateinit var context: Context
    private val databaseNames = mutableSetOf<String>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun `ingest commits object record inbox and ordered outbox before notification`() = runBlocking {
        val store = newStore()
        val delivery = deliveryObject("sqlite-local")
        val observed = Channel<com.openmesh.core.DeliverySnapshot>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            store.postCommitEvents.collect { outbox ->
                if (outbox.event is DeliveryStoreEvent.ApplicationDeliveryAvailable) {
                    observed.send(store.snapshot(outbox.event.deliveryId))
                }
            }
        }

        val result = store.ingest(ingest(delivery, destinationIsLocal = true), nowMs = 100)

        assertTrue(result is DeliveryIngestResult.Stored)
        val stored = result as DeliveryIngestResult.Stored
        assertEquals(DeliveryState.APP_PENDING, stored.record.state)
        assertEquals(listOf(1L, 2L), stored.committedEvents.map { it.sequence })
        val snapshot = withTimeout(2_000) { observed.receive() }
        assertEquals(InboxState.PENDING, snapshot.inboxRecord?.state)
        assertArrayEquals(
            delivery.canonicalBytes.copyToByteArray(),
            snapshot.deliveryObject?.canonicalBytes?.copyToByteArray(),
        )
        assertEquals(2, store.listOutbox().size)

        observer.cancelAndJoin()
        store.close()
    }

    @Test
    fun `failed transaction exposes no row outbox or post commit hint`() = runBlocking {
        val name = databaseName()
        val notifications = Channel<Unit>(Channel.UNLIMITED)
        val store = AndroidDeliveryStore(
            context = context,
            databaseName = name,
            limits = DeliveryStoreLimits(),
            beforeCommit = { kind ->
                if (kind == AndroidDeliveryStore.CommitKind.INGEST) {
                    assertTrue(notifications.tryReceive().isFailure)
                    throw SimulatedCrash()
                }
            },
            leaseTokenSource = { "test-owner-token" },
        )
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            store.postCommitEvents.collect { notifications.send(Unit) }
        }
        val delivery = deliveryObject("sqlite-rollback")

        expectSuspendThrows(SimulatedCrash::class.java) {
            store.ingest(ingest(delivery), nowMs = 100)
        }

        assertNull(store.snapshot(delivery.deliveryId).deliveryRecord)
        assertTrue(store.listOutbox().isEmpty())
        assertTrue(notifications.tryReceive().isFailure)
        store.close()

        val reopened = AndroidDeliveryStore(context, name)
        assertNull(reopened.snapshot(delivery.deliveryId).deliveryRecord)
        assertTrue(reopened.listOutbox().isEmpty())
        reopened.close()
        observer.cancelAndJoin()
    }

    @Test
    fun `concurrent duplicate ingest creates one SQLite truth`() = runBlocking {
        val store = newStore()
        val request = ingest(deliveryObject("sqlite-race"))
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
        assertEquals(1, store.listOutbox().size)
        assertEquals(DeliveryState.WAITING, store.snapshot(request.deliveryObject.deliveryId).deliveryRecord?.state)
        store.close()
    }

    @Test
    fun `canonical conflict rolls back and preserves original bytes`() = runBlocking {
        val store = newStore()
        val first = deliveryObject("sqlite-conflict", byteArrayOf(1, 2, 3))
        val conflict = deliveryObject("sqlite-conflict", byteArrayOf(9, 8, 7))
        store.ingest(ingest(first), nowMs = 100)

        expectSuspendThrows(CanonicalDeliveryConflictException::class.java) {
            store.ingest(ingest(conflict), nowMs = 101)
        }

        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            store.snapshot(first.deliveryId).deliveryObject?.canonicalBytes?.copyToByteArray(),
        )
        assertEquals(1, store.listOutbox().size)
        store.close()
    }

    @Test
    fun `duplicate receipt is idempotent and conflicting reuse rolls back`() = runBlocking {
        val name = databaseName()
        val store = AndroidDeliveryStore(context, name)
        val delivery = deliveryObject("sqlite-receipt")
        store.ingest(ingest(delivery), nowMs = 100)
        val receipt = ReceiptInput.copyOf(
            receiptId = ReceiptId("sqlite-receipt-1"),
            deliveryId = delivery.deliveryId,
            evidence = ReceiptEvidence.DESTINATION_STORED,
            issuer = "endpoint-authority-1",
            verification = ReceiptVerification.AUTHENTICATED_AND_AUTHORIZED,
            protectedBytes = byteArrayOf(1, 2, 3),
        )

        assertTrue(store.recordReceipt(receipt, nowMs = 200) is ReceiptWriteResult.Stored)
        store.close()

        val recovered = AndroidDeliveryStore(context, name)
        assertTrue(recovered.recordReceipt(receipt, nowMs = 201) is ReceiptWriteResult.Duplicate)
        val conflicting = ReceiptInput.copyOf(
            receiptId = receipt.receiptId,
            deliveryId = delivery.deliveryId,
            evidence = receipt.evidence,
            issuer = receipt.issuer,
            verification = receipt.verification,
            protectedBytes = byteArrayOf(9),
        )
        expectSuspendThrows(ReceiptIdentityConflictException::class.java) {
            recovered.recordReceipt(conflicting, nowMs = 202)
        }

        assertEquals(1, recovered.snapshot(delivery.deliveryId).receipts.size)
        assertEquals(2, recovered.listOutbox().size)
        recovered.close()
    }

    @Test
    fun `lease and attempt recover after database close and reopen`() = runBlocking {
        val name = databaseName()
        val firstStore = AndroidDeliveryStore(
            context = context,
            databaseName = name,
            limits = DeliveryStoreLimits(),
            beforeCommit = {},
            leaseTokenSource = { "persistent-unpredictable-owner" },
        )
        val delivery = deliveryObject("sqlite-recovery")
        firstStore.ingest(ingest(delivery), nowMs = 100)
        val acquired = firstStore.reserveTransfer(
            TransferReservation(delivery.deliveryId, transferContext(), leaseDurationMs = 1_000),
            nowMs = 110,
        ) as TransferReservationResult.Acquired
        firstStore.markTransferStarted(acquired.lease, nowMs = 120)
        val expected = firstStore.snapshot(delivery.deliveryId)
        firstStore.close()

        val recovered = AndroidDeliveryStore(context, name)

        assertEquals(expected, recovered.snapshot(delivery.deliveryId))
        val completed = recovered.recordLinkWriteCompleted(acquired.lease, nowMs = 130)
        assertEquals(TransferAttemptState.LINK_WRITE_COMPLETED, completed.state)
        recovered.close()
    }

    @Test
    fun `expired persisted lease preserves its attempt and permits a new reservation`() =
        runBlocking {
            val name = databaseName()
            val store = AndroidDeliveryStore(context, name)
            val delivery = deliveryObject("sqlite-expired-lease")
            store.ingest(ingest(delivery), nowMs = 100)
            val request = TransferReservation(
                deliveryId = delivery.deliveryId,
                context = transferContext(),
                leaseDurationMs = 10,
            )
            val first = store.reserveTransfer(request, nowMs = 110)
                as TransferReservationResult.Acquired
            assertTrue(store.reserveTransfer(request, nowMs = 119) is TransferReservationResult.Busy)
            store.close()

            val recovered = AndroidDeliveryStore(context, name)
            val resumed = recovered.reserveTransfer(request, nowMs = 120)
                as TransferReservationResult.Acquired

            assertNotEquals(first.attempt.attemptId, resumed.attempt.attemptId)
            assertEquals(
                setOf(TransferAttemptState.LEASE_EXPIRED, TransferAttemptState.RESERVED),
                recovered.snapshot(delivery.deliveryId).transferAttempts
                    .mapTo(mutableSetOf()) { it.state },
            )
            expectSuspendThrows(StaleTransferLeaseException::class.java) {
                recovered.markTransferStarted(first.lease, nowMs = 121)
            }
            recovered.close()
        }

    @Test
    fun `revisioned contexts persist exactly and are busy only for the same binding`() =
        runBlocking {
            val name = databaseName()
            val store = AndroidDeliveryStore(context, name)
            val delivery = deliveryObject("sqlite-revisioned-context")
            store.ingest(ingest(delivery), nowMs = 100)
            val key = TransportOpportunityKey(
                TransportAdapterId("sqlite-test-adapter"),
                TransportOpportunityId("sqlite-test-opportunity"),
            )
            val revisionOne = TransferContext.forOpportunity(
                TransportOpportunityReference(key, TransportOpportunityRevision(1)),
            )
            val revisionTwo = TransferContext.forOpportunity(
                TransportOpportunityReference(key, TransportOpportunityRevision(2)),
            )

            val first = store.reserveTransfer(
                TransferReservation(delivery.deliveryId, revisionOne, 1_000),
                nowMs = 110,
            )
            val busy = store.reserveTransfer(
                TransferReservation(delivery.deliveryId, revisionOne, 1_000),
                nowMs = 111,
            )
            val second = store.reserveTransfer(
                TransferReservation(delivery.deliveryId, revisionTwo, 1_000),
                nowMs = 112,
            )

            assertTrue(first is TransferReservationResult.Acquired)
            assertTrue(busy is TransferReservationResult.Busy)
            assertTrue(second is TransferReservationResult.Acquired)
            store.close()

            val reopened = AndroidDeliveryStore(context, name)
            assertEquals(
                setOf(revisionOne, revisionTwo),
                reopened.snapshot(delivery.deliveryId).transferAttempts.mapTo(mutableSetOf()) {
                    it.context
                },
            )
            reopened.close()
        }

    @Test
    fun `link write and untrusted receipts never create durable acceptance`() = runBlocking {
        val store = newStore()
        val delivery = deliveryObject("sqlite-link-only")
        store.ingest(ingest(delivery), nowMs = 100)
        val acquired = store.reserveTransfer(
            TransferReservation(delivery.deliveryId, transferContext(), 1_000),
            nowMs = 110,
        ) as TransferReservationResult.Acquired
        store.markTransferStarted(acquired.lease, nowMs = 120)
        store.recordLinkWriteCompleted(acquired.lease, nowMs = 130)

        listOf(
            ReceiptVerification.UNVERIFIED,
            ReceiptVerification.INVALID,
            ReceiptVerification.AUTHENTICATED,
        ).forEachIndexed { index, verification ->
            val result = store.recordReceipt(
                ReceiptInput.copyOf(
                    receiptId = ReceiptId("sqlite-untrusted-$index"),
                    deliveryId = delivery.deliveryId,
                    evidence = ReceiptEvidence.NEXT_HOP_ACCEPTED_DURABLY,
                    issuer = "claimed-peer",
                    verification = verification,
                    protectedBytes = byteArrayOf(index.toByte()),
                    linkedTransferAttemptId = acquired.attempt.attemptId,
                ),
                nowMs = 140L + index,
            )
            assertTrue(result is ReceiptWriteResult.Stored)
            assertNull(result.record.appliedAtMs)
        }

        val snapshot = store.snapshot(delivery.deliveryId)
        assertEquals(DeliveryState.WAITING, snapshot.deliveryRecord?.state)
        assertEquals(TransferAttemptState.LINK_WRITE_COMPLETED, snapshot.transferAttempts.single().state)
        assertTrue(snapshot.nextHopAcceptances.isEmpty())
        assertFalse(store.listOutbox().any { it.event is DeliveryStoreEvent.NextHopAcceptedDurably })
        store.close()
    }

    @Test
    fun `verified acceptance persists separately and leaves delivery waiting after reopen`() = runBlocking {
        val name = databaseName()
        val store = AndroidDeliveryStore(context, name)
        val delivery = deliveryObject("sqlite-verified")
        store.ingest(ingest(delivery), nowMs = 100)
        val acquired = store.reserveTransfer(
            TransferReservation(delivery.deliveryId, transferContext(), 1_000),
            nowMs = 110,
        ) as TransferReservationResult.Acquired
        store.markTransferStarted(acquired.lease, nowMs = 120)
        store.recordLinkWriteCompleted(acquired.lease, nowMs = 130)
        val acceptance = verifiedSessionAcceptance(
            deliveryId = delivery.deliveryId,
            attemptId = acquired.attempt.attemptId,
            evidenceId = AcceptanceEvidenceId("sqlite-session-evidence"),
        )

        store.recordNextHopAcceptedDurably(acceptance, nowMs = 140)
        store.close()

        val recovered = AndroidDeliveryStore(context, name)
        val snapshot = recovered.snapshot(delivery.deliveryId)
        assertEquals(DeliveryState.WAITING, snapshot.deliveryRecord?.state)
        assertEquals(TransferAttemptState.NEXT_HOP_ACCEPTED_DURABLY, snapshot.transferAttempts.single().state)
        assertEquals(NextHopAcceptanceEvidenceKind.AUTHENTICATED_SESSION, snapshot.nextHopAcceptances.single().kind)
        assertTrue(snapshot.receipts.isEmpty())
        recovered.close()
    }

    @Test
    fun `authorized receipt becomes durable acceptance only through verified authority`() =
        runBlocking {
            val name = databaseName()
            val store = AndroidDeliveryStore(context, name)
            val delivery = deliveryObject("sqlite-authorized-receipt")
            store.ingest(ingest(delivery), nowMs = 100)
            val acquired = store.reserveTransfer(
                TransferReservation(delivery.deliveryId, transferContext(), 1_000),
                nowMs = 110,
            ) as TransferReservationResult.Acquired
            store.markTransferStarted(acquired.lease, nowMs = 120)
            store.recordLinkWriteCompleted(acquired.lease, nowMs = 130)
            val receipt = ReceiptInput.copyOf(
                receiptId = ReceiptId("sqlite-authorized-next-hop"),
                deliveryId = delivery.deliveryId,
                evidence = ReceiptEvidence.NEXT_HOP_ACCEPTED_DURABLY,
                issuer = "authenticated-next-hop",
                verification = ReceiptVerification.AUTHENTICATED_AND_AUTHORIZED,
                protectedBytes = byteArrayOf(1, 2, 3),
                linkedTransferAttemptId = acquired.attempt.attemptId,
            )

            val storedReceipt = store.recordReceipt(receipt, nowMs = 135)
            assertNull(storedReceipt.record.appliedAtMs)
            assertEquals(
                TransferAttemptState.LINK_WRITE_COMPLETED,
                store.snapshot(delivery.deliveryId).transferAttempts.single().state,
            )
            val acceptance = verifiedReceiptAcceptance(receipt)
            assertTrue(
                store.recordNextHopAcceptedDurably(acceptance, nowMs = 140) is
                    NextHopAcceptanceWriteResult.Stored
            )
            store.close()

            val recovered = AndroidDeliveryStore(context, name)
            assertTrue(
                recovered.recordNextHopAcceptedDurably(acceptance, nowMs = 141) is
                    NextHopAcceptanceWriteResult.Duplicate
            )
            val snapshot = recovered.snapshot(delivery.deliveryId)
            assertEquals(DeliveryState.WAITING, snapshot.deliveryRecord?.state)
            assertEquals(
                TransferAttemptState.NEXT_HOP_ACCEPTED_DURABLY,
                snapshot.transferAttempts.single().state,
            )
            assertEquals(
                NextHopAcceptanceEvidenceKind.AUTHENTICATED_RECEIPT,
                snapshot.nextHopAcceptances.single().kind,
            )
            assertEquals(140L, snapshot.receipts.single().appliedAtMs)
            assertEquals(
                1,
                recovered.listOutbox().count {
                    it.event is DeliveryStoreEvent.NextHopAcceptedDurably
                },
            )
            recovered.close()
        }

    @Test
    fun `outbox sequence remains monotonic after acknowledgement prune and reopen`() = runBlocking {
        val name = databaseName()
        val store = AndroidDeliveryStore(context, name)
        val first = store.ingest(ingest(deliveryObject("sqlite-sequence-1")), 100)
            as DeliveryIngestResult.Stored
        store.acknowledgeOutbox(first.committedEvents.single().eventId, nowMs = 110)
        assertEquals(1, store.pruneAcknowledgedOutbox(beforeOrAtMs = 110, limit = 10))
        store.close()

        val reopened = AndroidDeliveryStore(context, name)
        val second = reopened.ingest(ingest(deliveryObject("sqlite-sequence-2")), 120)
            as DeliveryIngestResult.Stored

        assertEquals(2L, second.committedEvents.single().sequence)
        reopened.close()
    }

    @Test
    fun `expired object bytes are removed while tombstone survives restart`() = runBlocking {
        val name = databaseName()
        val store = AndroidDeliveryStore(context, name)
        val delivery = deliveryObject("sqlite-tombstone", createdAtMs = 100, expiresAtMs = 200)
        store.ingest(ingest(delivery), nowMs = 100)

        assertEquals(1, store.expireObjects(nowMs = 200, tombstoneRetentionMs = 1_000))
        store.close()

        val recovered = AndroidDeliveryStore(context, name)
        val snapshot = recovered.snapshot(delivery.deliveryId)
        assertNull(snapshot.deliveryObject)
        assertEquals(DeliveryState.EXPIRED, snapshot.deliveryRecord?.state)
        assertEquals(TombstoneReason.EXPIRED, snapshot.tombstone?.reason)
        val replay = deliveryObject("sqlite-tombstone", createdAtMs = 200, expiresAtMs = 2_000)
        assertTrue(
            recovered.ingest(ingest(replay), nowMs = 201) is
                DeliveryIngestResult.RejectedByTombstone
        )
        recovered.close()
    }

    @Test
    fun `application acknowledgement and outbox fact commit atomically`() = runBlocking {
        val name = databaseName()
        var failOn: AndroidDeliveryStore.CommitKind? = null
        val store = AndroidDeliveryStore(
            context = context,
            databaseName = name,
            limits = DeliveryStoreLimits(),
            beforeCommit = { kind -> if (kind == failOn) throw SimulatedCrash() },
            leaseTokenSource = { "test-owner-token" },
        )
        val delivery = deliveryObject("sqlite-app-ack")
        store.ingest(ingest(delivery, destinationIsLocal = true), nowMs = 100)
        val baselineOutbox = store.listOutbox()
        failOn = AndroidDeliveryStore.CommitKind.ACKNOWLEDGE_APPLICATION

        expectSuspendThrows(SimulatedCrash::class.java) {
            store.acknowledgeApplicationDelivery(delivery.deliveryId, nowMs = 200)
        }

        val afterFailure = store.snapshot(delivery.deliveryId)
        assertEquals(DeliveryState.APP_PENDING, afterFailure.deliveryRecord?.state)
        assertEquals(InboxState.PENDING, afterFailure.inboxRecord?.state)
        assertEquals(baselineOutbox, store.listOutbox())
        store.close()

        val recovered = AndroidDeliveryStore(context, name)
        recovered.acknowledgeApplicationDelivery(delivery.deliveryId, nowMs = 201)
        val committed = recovered.snapshot(delivery.deliveryId)
        assertEquals(DeliveryState.APP_DELIVERED, committed.deliveryRecord?.state)
        assertEquals(InboxState.DELIVERED, committed.inboxRecord?.state)
        assertTrue(recovered.listOutbox().last().event is DeliveryStoreEvent.ApplicationDelivered)
        recovered.close()
    }

    @Test
    fun `object and outbox admission bounds roll back the complete SQLite transaction`() =
        runBlocking {
            val oversized = deliveryObject(
                id = "sqlite-too-large",
                bytes = byteArrayOf(1, 2, 3),
            )
            val objectBounded = newStore(
                DeliveryStoreLimits(maxObjectBytes = 2),
            )
            expectSuspendThrows(DeliveryStoreAdmissionException::class.java) {
                objectBounded.ingest(ingest(oversized), nowMs = 100)
            }
            assertNull(objectBounded.snapshot(oversized.deliveryId).deliveryRecord)
            assertTrue(objectBounded.listOutbox().isEmpty())
            objectBounded.close()

            val local = deliveryObject("sqlite-outbox-bound")
            val outboxBounded = newStore(
                DeliveryStoreLimits(maxOutboxRecords = 1),
            )
            expectSuspendThrows(DeliveryStoreAdmissionException::class.java) {
                outboxBounded.ingest(
                    ingest(local, destinationIsLocal = true),
                    nowMs = 100,
                )
            }
            assertNull(outboxBounded.snapshot(local.deliveryId).deliveryRecord)
            assertTrue(outboxBounded.listOutbox().isEmpty())
            outboxBounded.close()
        }

    @Test
    fun `schema owns every ratified aggregate and migration metadata`() = runBlocking {
        val name = databaseName()
        val store = AndroidDeliveryStore(context, name)
        store.snapshot(DeliveryId("schema-open"))
        store.close()
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )

        val tables = database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        assertTrue(
            tables.containsAll(
                setOf(
                    "delivery_objects",
                    "delivery_records",
                    "delivery_provenance",
                    "transfer_attempts",
                    "acceptance_evidence",
                    "receipts",
                    "inbox",
                    "outbox",
                    "tombstones",
                    "migration_metadata",
                    "legacy_import_items",
                )
            )
        )
        val migrationColumns = database.rawQuery(
            "PRAGMA table_info(migration_metadata)",
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        val importItemColumns = database.rawQuery(
            "PRAGMA table_info(legacy_import_items)",
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        assertTrue("local_node_id" in migrationColumns)
        assertTrue("disposition" in importItemColumns)
        val attemptColumns = database.rawQuery(
            "PRAGMA table_info(transfer_attempts)",
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        assertTrue("opportunity_revision" in attemptColumns)
        assertEquals(OpenMeshDeliveryDatabase.DATABASE_VERSION, database.version)
        database.close()
    }

    private fun newStore(limits: DeliveryStoreLimits = DeliveryStoreLimits()): AndroidDeliveryStore =
        AndroidDeliveryStore(context, databaseName(), limits)

    private fun databaseName(): String = "delivery-${UUID.randomUUID()}.db".also(databaseNames::add)

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
        provenance = IngressProvenance(
            IngressProvenance.Kind.LOCAL_APPLICATION,
            "android-store-test",
        ),
    )

    private fun transferContext(): TransferContext = TransferContext(
        adapterId = "test-adapter",
        opportunityId = "test-opportunity",
    )

    private fun verifiedSessionAcceptance(
        deliveryId: DeliveryId,
        attemptId: TransferAttemptId,
        evidenceId: AcceptanceEvidenceId,
    ): VerifiedNextHopAcceptance {
        val companion = VerifiedNextHopAcceptance.Companion
        val method = companion::class.java.declaredMethods.single {
            it.name.startsWith("fromAuthenticatedSession")
        }
        method.isAccessible = true
        val stringValues = ArrayDeque(
            listOf(
                deliveryId.value,
                attemptId.value,
                evidenceId.value,
                "authenticated-session-peer",
            )
        )
        val arguments = method.parameterTypes.map { type ->
            when {
                type == String::class.java -> stringValues.removeFirst()
                type == ByteArray::class.java -> byteArrayOf(4, 5, 6)
                type == DeliveryId::class.java -> deliveryId
                type == TransferAttemptId::class.java -> attemptId
                type == AcceptanceEvidenceId::class.java -> evidenceId
                else -> error("Unexpected verified-acceptance factory parameter ${type.name}")
            }
        }.toTypedArray()
        return method.invoke(companion, *arguments) as VerifiedNextHopAcceptance
    }

    private fun verifiedReceiptAcceptance(
        receipt: ReceiptInput,
    ): VerifiedNextHopAcceptance {
        val companion = VerifiedNextHopAcceptance.Companion
        val method = companion::class.java.declaredMethods.single {
            it.name.startsWith("fromAuthenticatedReceipt")
        }
        method.isAccessible = true
        return method.invoke(companion, receipt) as VerifiedNextHopAcceptance
    }

    private suspend fun <T : Throwable> expectSuspendThrows(
        type: Class<T>,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (thrown: Throwable) {
            if (type.isInstance(thrown)) return
            throw AssertionError("Expected ${type.name}, got ${thrown::class.java.name}", thrown)
        }
        throw AssertionError("Expected ${type.name} to be thrown")
    }

    private class SimulatedCrash : RuntimeException("simulated SQLite process death")
}
