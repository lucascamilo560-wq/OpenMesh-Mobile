package com.openmesh.android

import android.content.Context
import com.openmesh.core.DeliveryId
import com.openmesh.core.DeliverySnapshot
import com.openmesh.core.DeliveryState
import com.openmesh.core.DeliveryStoreEvent
import com.openmesh.core.IngressProvenance
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.NodeId
import com.openmesh.core.PacketPriority
import com.openmesh.core.TransferContext
import com.openmesh.core.TransferReservation
import com.openmesh.core.TransferReservationResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class LegacyPacketStoreMigrationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var preferencesName: String

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "migration-${UUID.randomUUID()}.db"
        preferencesName = "legacy-${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `migration preserves remote local broadcast and expired legacy disposition`() = runBlocking {
        val legacy = SharedPreferencesPacketStore(context, preferencesName)
        val remote = envelope("legacy-remote", createdAtMs = 100, expiresAtMs = 10_000)
        val local = envelope(
            "legacy-local",
            createdAtMs = 100,
            expiresAtMs = 10_000,
            destinationNodeId = LOCAL_NODE_ID.value,
        )
        val broadcast = envelope(
            "legacy-broadcast",
            createdAtMs = 100,
            expiresAtMs = 10_000,
            destinationNodeId = null,
        )
        val expired = envelope("legacy-expired", createdAtMs = 100, expiresAtMs = 200)
        legacy.put(remote)
        legacy.put(local)
        legacy.put(broadcast)
        legacy.put(expired)
        val store = AndroidDeliveryStore(context, databaseName)
        val migrator = LegacyPacketStoreMigrator(
            context = context,
            store = store,
            localNodeId = LOCAL_NODE_ID,
            legacyPreferencesName = preferencesName,
        )

        val report = migrator.migrate(nowMs = 300)

        assertEquals(LegacyMigrationState.LEGACY_RETAINED, report.status.state)
        assertEquals(LOCAL_NODE_ID, report.status.localNodeId)
        assertEquals(4, report.status.expectedCount)
        assertEquals(4, report.status.importedCount)
        assertEquals(report.status.expectedManifestHash, report.status.importedManifestHash)
        assertTrue(report.legacyStoreRetained)
        assertEquals(4, legacy.list().size)

        val remoteSnapshot = store.snapshot(DeliveryId(remote.packetId))
        assertNotNull(remoteSnapshot.deliveryObject)
        assertEquals(DeliveryState.WAITING, remoteSnapshot.deliveryRecord?.state)
        assertTrue(
            remoteSnapshot.deliveryRecord?.provenance?.any {
                it.kind == IngressProvenance.Kind.LEGACY_IMPORT
            } == true
        )
        assertNoInventedFacts(remoteSnapshot)

        val broadcastSnapshot = store.snapshot(DeliveryId(broadcast.packetId))
        assertNotNull(broadcastSnapshot.deliveryObject)
        assertEquals(DeliveryState.WAITING, broadcastSnapshot.deliveryRecord?.state)
        assertNoInventedFacts(broadcastSnapshot)

        val localSnapshot = store.snapshot(DeliveryId(local.packetId))
        assertNotNull(localSnapshot.deliveryObject)
        assertEquals(DeliveryState.QUARANTINED, localSnapshot.deliveryRecord?.state)
        assertNoInventedFacts(localSnapshot)
        assertTrue(
            store.reserveTransfer(reservation(local.packetId), nowMs = 301) is
                TransferReservationResult.NotEligible
        )

        val expiredSnapshot = store.snapshot(DeliveryId(expired.packetId))
        assertNull(expiredSnapshot.deliveryObject)
        assertEquals(DeliveryState.EXPIRED, expiredSnapshot.deliveryRecord?.state)
        assertNotNull(expiredSnapshot.tombstone)
        assertNoInventedFacts(expiredSnapshot)

        assertFalse(
            store.listOutbox().any { record ->
                record.event is DeliveryStoreEvent.ApplicationDeliveryAvailable ||
                    record.event is DeliveryStoreEvent.ApplicationDelivered
            }
        )

        val outboxBeforeRetry = store.listOutbox()
        val retried = migrator.migrate(nowMs = 302)
        assertEquals(LegacyMigrationState.LEGACY_RETAINED, retried.status.state)
        assertEquals(outboxBeforeRetry, store.listOutbox())
        assertEquals(4, legacy.list().size)
        assertTrue(
            store.reserveTransfer(reservation(remote.packetId), nowMs = 303) is
                TransferReservationResult.Acquired
        )
        assertTrue(
            store.reserveTransfer(reservation(broadcast.packetId), nowMs = 303) is
                TransferReservationResult.Acquired
        )
        store.close()
    }

    @Test
    fun `process death resumes mixed disposition without reclassification or duplicates`() = runBlocking {
        val legacy = SharedPreferencesPacketStore(context, preferencesName)
        val local = envelope(
            "legacy-a-local",
            100,
            10_000,
            destinationNodeId = LOCAL_NODE_ID.value,
        )
        val broadcast = envelope(
            "legacy-b-broadcast",
            100,
            10_000,
            destinationNodeId = null,
        )
        val remote = envelope("legacy-c-remote", 100, 10_000)
        legacy.put(local)
        legacy.put(broadcast)
        legacy.put(remote)
        var committedEntries = 0
        val firstStore = AndroidDeliveryStore(context, databaseName)
        val crashingMigrator = LegacyPacketStoreMigrator(
            store = firstStore,
            source = SharedPreferencesLegacyPacketSource(context, preferencesName),
            migrationId = LegacyPacketStoreMigrator.DEFAULT_MIGRATION_ID,
            localNodeId = LOCAL_NODE_ID,
            hooks = LegacyMigrationHooks(
                afterEntryCommitted = {
                    committedEntries += 1
                    if (committedEntries == 1) throw SimulatedProcessDeath()
                }
            ),
        )

        expectSuspendThrows(SimulatedProcessDeath::class.java) {
            crashingMigrator.migrate(nowMs = 200)
        }

        assertEquals(LegacyMigrationState.IMPORTING, crashingMigrator.status()?.state)
        assertEquals(1, crashingMigrator.status()?.importedCount)
        firstStore.close()

        val recoveredStore = AndroidDeliveryStore(context, databaseName)
        val recoveredMigrator = LegacyPacketStoreMigrator(
            context = context,
            store = recoveredStore,
            localNodeId = LOCAL_NODE_ID,
            legacyPreferencesName = preferencesName,
        )
        val report = recoveredMigrator.migrate(nowMs = 201)

        assertEquals(LegacyMigrationState.LEGACY_RETAINED, report.status.state)
        assertEquals(3, report.status.importedCount)
        assertEquals(3, recoveredStore.listOutbox().size)
        assertEquals(3, legacy.list().size)
        assertEquals(
            DeliveryState.QUARANTINED,
            recoveredStore.snapshot(DeliveryId(local.packetId)).deliveryRecord?.state,
        )
        assertEquals(
            DeliveryState.WAITING,
            recoveredStore.snapshot(DeliveryId(broadcast.packetId)).deliveryRecord?.state,
        )
        assertEquals(
            DeliveryState.WAITING,
            recoveredStore.snapshot(DeliveryId(remote.packetId)).deliveryRecord?.state,
        )
        recoveredStore.close()
    }

    @Test
    fun `retry with a different local node fails closed before reclassification`() = runBlocking {
        val legacy = SharedPreferencesPacketStore(context, preferencesName)
        val local = envelope(
            "legacy-node-bound",
            100,
            10_000,
            destinationNodeId = LOCAL_NODE_ID.value,
        )
        legacy.put(local)
        val firstStore = AndroidDeliveryStore(context, databaseName)
        val crashing = LegacyPacketStoreMigrator(
            store = firstStore,
            source = SharedPreferencesLegacyPacketSource(context, preferencesName),
            migrationId = LegacyPacketStoreMigrator.DEFAULT_MIGRATION_ID,
            localNodeId = LOCAL_NODE_ID,
            hooks = LegacyMigrationHooks(
                afterEntryCommitted = { throw SimulatedProcessDeath() }
            ),
        )
        expectSuspendThrows(SimulatedProcessDeath::class.java) {
            crashing.migrate(nowMs = 200)
        }
        firstStore.close()

        val recoveredStore = AndroidDeliveryStore(context, databaseName)
        val wrongNodeMigrator = LegacyPacketStoreMigrator(
            context = context,
            store = recoveredStore,
            localNodeId = OTHER_LOCAL_NODE_ID,
            legacyPreferencesName = preferencesName,
        )
        expectSuspendThrows(LegacyMigrationVerificationException::class.java) {
            wrongNodeMigrator.migrate(nowMs = 201)
        }

        assertEquals(LegacyMigrationState.IMPORTING, wrongNodeMigrator.status()?.state)
        assertEquals(LOCAL_NODE_ID, wrongNodeMigrator.status()?.localNodeId)
        assertEquals(
            DeliveryState.QUARANTINED,
            recoveredStore.snapshot(DeliveryId(local.packetId)).deliveryRecord?.state,
        )
        val recovered = LegacyPacketStoreMigrator(
            context = context,
            store = recoveredStore,
            localNodeId = LOCAL_NODE_ID,
            legacyPreferencesName = preferencesName,
        ).migrate(nowMs = 202)
        assertEquals(LegacyMigrationState.LEGACY_RETAINED, recovered.status.state)
        assertEquals(1, recoveredStore.listOutbox().size)
        recoveredStore.close()
    }

    @Test
    fun `process death after VERIFYING state resumes verification safely`() = runBlocking {
        val legacy = SharedPreferencesPacketStore(context, preferencesName)
        legacy.put(envelope("legacy-verifying", 100, 10_000))
        val firstStore = AndroidDeliveryStore(context, databaseName)
        val crashingMigrator = LegacyPacketStoreMigrator(
            store = firstStore,
            source = SharedPreferencesLegacyPacketSource(context, preferencesName),
            migrationId = LegacyPacketStoreMigrator.DEFAULT_MIGRATION_ID,
            localNodeId = LOCAL_NODE_ID,
            hooks = LegacyMigrationHooks(
                afterStatePersisted = { state ->
                    if (state == LegacyMigrationState.VERIFYING) {
                        throw SimulatedProcessDeath()
                    }
                }
            ),
        )

        expectSuspendThrows(SimulatedProcessDeath::class.java) {
            crashingMigrator.migrate(nowMs = 200)
        }
        assertEquals(LegacyMigrationState.VERIFYING, crashingMigrator.status()?.state)
        firstStore.close()

        val recoveredStore = AndroidDeliveryStore(context, databaseName)
        val recovered = LegacyPacketStoreMigrator(
            context = context,
            store = recoveredStore,
            localNodeId = LOCAL_NODE_ID,
            legacyPreferencesName = preferencesName,
        ).migrate(nowMs = 201)

        assertEquals(LegacyMigrationState.LEGACY_RETAINED, recovered.status.state)
        assertEquals(1, recoveredStore.listOutbox().size)
        assertEquals(1, legacy.list().size)
        recoveredStore.close()
    }

    @Test
    fun `malformed legacy entry fails before metadata and leaves source untouched`() = runBlocking {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        assertTrue(
            preferences.edit().putString("packet.bad", "%%%not-base64%%%").commit()
        )
        val store = AndroidDeliveryStore(context, databaseName)
        val migrator = LegacyPacketStoreMigrator(
            context = context,
            store = store,
            localNodeId = LOCAL_NODE_ID,
            legacyPreferencesName = preferencesName,
        )

        expectSuspendThrows(LegacyMigrationValidationException::class.java) {
            migrator.migrate(nowMs = 200)
        }

        assertNull(migrator.status())
        assertEquals("%%%not-base64%%%", preferences.getString("packet.bad", null))
        store.close()
    }

    @Test
    fun `changed source after partial import fails closed`() = runBlocking {
        val legacy = SharedPreferencesPacketStore(context, preferencesName)
        legacy.put(envelope("legacy-stable", 100, 10_000))
        val firstStore = AndroidDeliveryStore(context, databaseName)
        val crashing = LegacyPacketStoreMigrator(
            store = firstStore,
            source = SharedPreferencesLegacyPacketSource(context, preferencesName),
            migrationId = LegacyPacketStoreMigrator.DEFAULT_MIGRATION_ID,
            localNodeId = LOCAL_NODE_ID,
            hooks = LegacyMigrationHooks(
                afterEntryCommitted = { throw SimulatedProcessDeath() }
            ),
        )
        expectSuspendThrows(SimulatedProcessDeath::class.java) {
            crashing.migrate(nowMs = 200)
        }
        firstStore.close()

        legacy.put(envelope("legacy-added", 100, 10_000))
        val recoveredStore = AndroidDeliveryStore(context, databaseName)
        val recovered = LegacyPacketStoreMigrator(
            context = context,
            store = recoveredStore,
            localNodeId = LOCAL_NODE_ID,
            legacyPreferencesName = preferencesName,
        )

        expectSuspendThrows(LegacyMigrationVerificationException::class.java) {
            recovered.migrate(nowMs = 201)
        }

        assertEquals(LegacyMigrationState.IMPORTING, recovered.status()?.state)
        assertEquals(2, legacy.list().size)
        recoveredStore.close()
    }

    private fun envelope(
        packetId: String,
        createdAtMs: Long,
        expiresAtMs: Long,
        destinationNodeId: String? = REMOTE_NODE_ID.value,
    ): MeshEnvelope = MeshEnvelope(
        packetId = packetId,
        sourceNodeId = SOURCE_NODE_ID.value,
        destinationNodeId = destinationNodeId,
        createdAtMs = createdAtMs,
        expiresAtMs = expiresAtMs,
        priority = PacketPriority.NORMAL,
        contentType = "text/plain",
        payloadBase64 = "bGVnYWN5",
    )

    private fun reservation(packetId: String): TransferReservation = TransferReservation(
        deliveryId = DeliveryId(packetId),
        context = TransferContext(
            adapterId = "legacy-migration-test",
            opportunityId = "opportunity-$packetId",
        ),
        leaseDurationMs = 1_000,
    )

    private fun assertNoInventedFacts(snapshot: DeliverySnapshot) {
        assertTrue(snapshot.transferAttempts.isEmpty())
        assertTrue(snapshot.nextHopAcceptances.isEmpty())
        assertTrue(snapshot.receipts.isEmpty())
        assertNull(snapshot.inboxRecord)
        assertFalse(snapshot.deliveryRecord?.state == DeliveryState.APP_DELIVERED)
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

    private class SimulatedProcessDeath : RuntimeException("simulated process death")

    private companion object {
        val SOURCE_NODE_ID = NodeId("om1-00000000000000000000000000000000")
        val REMOTE_NODE_ID = NodeId("om1-11111111111111111111111111111111")
        val LOCAL_NODE_ID = NodeId("om1-22222222222222222222222222222222")
        val OTHER_LOCAL_NODE_ID = NodeId("om1-33333333333333333333333333333333")
    }
}
