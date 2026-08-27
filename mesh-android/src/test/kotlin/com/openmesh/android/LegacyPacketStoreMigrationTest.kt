package com.openmesh.android

import android.content.Context
import com.openmesh.core.DeliveryId
import com.openmesh.core.DeliveryState
import com.openmesh.core.IngressProvenance
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.PacketPriority
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun `migration verifies active and expired packets and retains legacy bytes`() = runBlocking {
        val legacy = SharedPreferencesPacketStore(context, preferencesName)
        val active = envelope("legacy-active", createdAtMs = 100, expiresAtMs = 10_000)
        val expired = envelope("legacy-expired", createdAtMs = 100, expiresAtMs = 200)
        legacy.put(active)
        legacy.put(expired)
        val store = AndroidDeliveryStore(context, databaseName)
        val migrator = LegacyPacketStoreMigrator(
            context = context,
            store = store,
            legacyPreferencesName = preferencesName,
        )

        val report = migrator.migrate(nowMs = 300)

        assertEquals(LegacyMigrationState.LEGACY_RETAINED, report.status.state)
        assertEquals(2, report.status.expectedCount)
        assertEquals(2, report.status.importedCount)
        assertEquals(report.status.expectedManifestHash, report.status.importedManifestHash)
        assertTrue(report.legacyStoreRetained)
        assertEquals(2, legacy.list().size)

        val activeSnapshot = store.snapshot(DeliveryId(active.packetId))
        assertNotNull(activeSnapshot.deliveryObject)
        assertEquals(DeliveryState.WAITING, activeSnapshot.deliveryRecord?.state)
        assertTrue(
            activeSnapshot.deliveryRecord?.provenance?.any {
                it.kind == IngressProvenance.Kind.LEGACY_IMPORT
            } == true
        )
        assertTrue(activeSnapshot.transferAttempts.isEmpty())
        assertTrue(activeSnapshot.nextHopAcceptances.isEmpty())
        assertTrue(activeSnapshot.receipts.isEmpty())
        assertNull(activeSnapshot.inboxRecord)

        val expiredSnapshot = store.snapshot(DeliveryId(expired.packetId))
        assertNull(expiredSnapshot.deliveryObject)
        assertEquals(DeliveryState.EXPIRED, expiredSnapshot.deliveryRecord?.state)
        assertNotNull(expiredSnapshot.tombstone)
        assertTrue(expiredSnapshot.transferAttempts.isEmpty())
        assertTrue(expiredSnapshot.nextHopAcceptances.isEmpty())
        assertTrue(expiredSnapshot.receipts.isEmpty())
        assertNull(expiredSnapshot.inboxRecord)

        val outboxBeforeRetry = store.listOutbox()
        val retried = migrator.migrate(nowMs = 301)
        assertEquals(LegacyMigrationState.LEGACY_RETAINED, retried.status.state)
        assertEquals(outboxBeforeRetry, store.listOutbox())
        assertEquals(2, legacy.list().size)
        store.close()
    }

    @Test
    fun `process death after one imported entry resumes without duplicates`() = runBlocking {
        val legacy = SharedPreferencesPacketStore(context, preferencesName)
        legacy.put(envelope("legacy-a", 100, 10_000))
        legacy.put(envelope("legacy-b", 100, 10_000))
        var committedEntries = 0
        val firstStore = AndroidDeliveryStore(context, databaseName)
        val crashingMigrator = LegacyPacketStoreMigrator(
            store = firstStore,
            source = SharedPreferencesLegacyPacketSource(context, preferencesName),
            migrationId = LegacyPacketStoreMigrator.DEFAULT_MIGRATION_ID,
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
            context,
            recoveredStore,
            preferencesName,
        )
        val report = recoveredMigrator.migrate(nowMs = 201)

        assertEquals(LegacyMigrationState.LEGACY_RETAINED, report.status.state)
        assertEquals(2, report.status.importedCount)
        assertEquals(2, recoveredStore.listOutbox().size)
        assertEquals(2, legacy.list().size)
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
            context,
            recoveredStore,
            preferencesName,
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
            context,
            store,
            preferencesName,
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
            context,
            recoveredStore,
            preferencesName,
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
    ): MeshEnvelope = MeshEnvelope(
        packetId = packetId,
        sourceNodeId = "om1-00000000000000000000000000000000",
        destinationNodeId = "om1-11111111111111111111111111111111",
        createdAtMs = createdAtMs,
        expiresAtMs = expiresAtMs,
        priority = PacketPriority.NORMAL,
        contentType = "text/plain",
        payloadBase64 = "bGVnYWN5",
    )

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
}
