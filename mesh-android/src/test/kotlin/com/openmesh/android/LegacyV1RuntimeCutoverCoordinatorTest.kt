package com.openmesh.android

import android.content.Context
import com.openmesh.core.DeliveryId
import com.openmesh.core.DeliveryIngest
import com.openmesh.core.DeliveryObject
import com.openmesh.core.DeliveryState
import com.openmesh.core.EndpointId
import com.openmesh.core.IngestResult
import com.openmesh.core.IngressProvenance
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshRouter
import com.openmesh.core.NodeId
import com.openmesh.core.PacketPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LegacyV1RuntimeCutoverCoordinatorTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var preferencesName: String

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "cutover-${UUID.randomUUID()}.db"
        preferencesName = "cutover-legacy-${UUID.randomUUID()}"
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
    fun `prepare migrates marks owner and supports inbound outbound and restart without legacy writes`() =
        runBlocking {
            val legacy = SharedPreferencesPacketStore(context, preferencesName)
            val remote = envelope("legacy-remote", REMOTE_NODE_ID, OTHER_NODE_ID)
            val local = envelope("legacy-local", REMOTE_NODE_ID, LOCAL_NODE_ID)
            val broadcast = envelope("legacy-broadcast", REMOTE_NODE_ID, null)
            legacy.put(remote)
            legacy.put(local)
            legacy.put(broadcast)
            val sourceBefore = legacyContents()
            val coordinator = coordinator()

            val prepared = coordinator.prepare(nowMs = 100)

            assertEquals(LegacyV1RuntimeOwner.SQLITE, prepared.owner)
            assertEquals(LegacyMigrationState.LEGACY_RETAINED, prepared.migrationStatus.state)
            assertEquals(LOCAL_NODE_ID, prepared.migrationStatus.localNodeId)
            assertEquals(sourceBefore, legacyContents())
            assertEquals(
                setOf(remote.packetId, broadcast.packetId),
                prepared.packetStore.list().mapTo(mutableSetOf(), MeshEnvelope::packetId),
            )
            assertTrue(prepared.packetStore.contains(local.packetId))
            assertEquals(
                DeliveryState.QUARANTINED,
                prepared.deliveryStore.snapshot(DeliveryId(local.packetId)).deliveryRecord?.state,
            )

            val router = MeshRouter(LOCAL_NODE_ID.value, prepared.packetStore)
            val outbound = envelope("sqlite-outbound", LOCAL_NODE_ID, REMOTE_NODE_ID)
            val inbound = envelope("sqlite-inbound-local", REMOTE_NODE_ID, LOCAL_NODE_ID)
            assertEquals(IngestResult.STORED_FOR_FORWARDING, router.createLocal(outbound, 200))
            assertEquals(IngestResult.DELIVERED_LOCAL, router.ingest(inbound, 200))
            assertEquals(sourceBefore, legacyContents())
            coordinator.close()

            val restarted = coordinator()
            val recovered = restarted.prepare(nowMs = 300)
            assertEquals(LegacyV1RuntimeOwner.SQLITE, recovered.owner)
            assertTrue(recovered.packetStore.contains(outbound.packetId))
            assertTrue(recovered.packetStore.contains(inbound.packetId))
            assertFalse(recovered.packetStore.list().any { it.packetId == inbound.packetId })
            assertEquals(sourceBefore, legacyContents())
            restarted.close()
            expectSuspendThrows(LegacyV1CutoverException::class.java) {
                restarted.prepare(nowMs = 301)
            }
        }

    @Test
    fun `close during preparation prevents publication and closes the unpublished store`() =
        runBlocking {
            val legacy = SharedPreferencesPacketStore(context, preferencesName)
            legacy.put(envelope("legacy-close-race", REMOTE_NODE_ID, OTHER_NODE_ID))
            val sourceBefore = legacyContents()
            val ownerCommitted = CountDownLatch(1)
            val resumePreparation = CountDownLatch(1)
            val unpublishedStoreClosed = CountDownLatch(1)
            val coordinator = coordinator(
                cutoverHooks = LegacyV1CutoverHooks(
                    afterOwnerMarkerCommitted = {
                        ownerCommitted.countDown()
                        check(resumePreparation.await(5, TimeUnit.SECONDS)) {
                            "Timed out waiting to resume preparation"
                        }
                    },
                    afterUnpublishedStoreClosed = {
                        unpublishedStoreClosed.countDown()
                    },
                )
            )
            val preparing = async(Dispatchers.Default) {
                runCatching { coordinator.prepare(nowMs = 100) }
            }

            assertTrue(ownerCommitted.await(5, TimeUnit.SECONDS))
            coordinator.close()
            resumePreparation.countDown()
            val outcome = preparing.await()

            assertNull(outcome.getOrNull())
            assertTrue(outcome.exceptionOrNull() is LegacyV1CutoverException)
            assertTrue(unpublishedStoreClosed.await(5, TimeUnit.SECONDS))
            expectSuspendThrows(LegacyV1CutoverException::class.java) {
                coordinator.prepare(nowMs = 101)
            }
            assertEquals(sourceBefore, legacyContents())
            val checkpoint = AndroidDeliveryStore(context, databaseName)
            assertEquals(LegacyV1RuntimeOwner.SQLITE, checkpoint.legacyV1RuntimeOwner())
            checkpoint.close()
        }

    @Test
    fun `crash during import resumes idempotently and never writes legacy source`() = runBlocking {
        val legacy = SharedPreferencesPacketStore(context, preferencesName)
        legacy.put(envelope("legacy-a", REMOTE_NODE_ID, OTHER_NODE_ID))
        legacy.put(envelope("legacy-b", REMOTE_NODE_ID, null))
        val sourceBefore = legacyContents()
        var imported = 0
        val crashing = coordinator(
            migrationHooks = LegacyMigrationHooks(
                afterEntryCommitted = {
                    imported += 1
                    if (imported == 1) throw SimulatedProcessDeath()
                }
            )
        )

        expectSuspendThrows(SimulatedProcessDeath::class.java) {
            crashing.prepare(nowMs = 100)
        }
        assertEquals(sourceBefore, legacyContents())
        val checkpoint = AndroidDeliveryStore(context, databaseName)
        assertNull(checkpoint.legacyV1RuntimeOwner())
        assertEquals(
            LegacyMigrationState.IMPORTING,
            checkpoint.legacyMigrationStatus(
                LegacyPacketStoreMigrator.DEFAULT_MIGRATION_ID
            )?.state,
        )
        checkpoint.close()

        val retry = coordinator()
        val prepared = retry.prepare(nowMs = 101)
        assertEquals(LegacyV1RuntimeOwner.SQLITE, prepared.owner)
        assertEquals(2, prepared.migrationStatus.importedCount)
        assertEquals(2, prepared.deliveryStore.listOutbox().size)
        assertEquals(sourceBefore, legacyContents())
        retry.close()
        crashing.close()
    }

    @Test
    fun `crash after retained before marker retries migration verification then commits owner`() =
        runBlocking {
            val legacy = SharedPreferencesPacketStore(context, preferencesName)
            legacy.put(envelope("legacy-before-marker", REMOTE_NODE_ID, OTHER_NODE_ID))
            val sourceBefore = legacyContents()
            val crashing = coordinator(
                cutoverHooks = LegacyV1CutoverHooks(
                    beforeOwnerMarker = { throw SimulatedProcessDeath() }
                )
            )

            expectSuspendThrows(SimulatedProcessDeath::class.java) {
                crashing.prepare(nowMs = 100)
            }
            val checkpoint = AndroidDeliveryStore(context, databaseName)
            assertNull(checkpoint.legacyV1RuntimeOwner())
            assertEquals(
                LegacyMigrationState.LEGACY_RETAINED,
                checkpoint.legacyMigrationStatus(
                    LegacyPacketStoreMigrator.DEFAULT_MIGRATION_ID
                )?.state,
            )
            checkpoint.close()

            val retry = coordinator()
            assertEquals(LegacyV1RuntimeOwner.SQLITE, retry.prepare(nowMs = 101).owner)
            assertEquals(sourceBefore, legacyContents())
            retry.close()
            crashing.close()
        }

    @Test
    fun `crash after owner marker resumes from SQLite without a second migration`() = runBlocking {
        val legacy = SharedPreferencesPacketStore(context, preferencesName)
        legacy.put(envelope("legacy-after-marker", REMOTE_NODE_ID, OTHER_NODE_ID))
        val sourceBefore = legacyContents()
        val crashing = coordinator(
            cutoverHooks = LegacyV1CutoverHooks(
                afterOwnerMarkerCommitted = { throw SimulatedProcessDeath() }
            )
        )

        expectSuspendThrows(SimulatedProcessDeath::class.java) {
            crashing.prepare(nowMs = 100)
        }
        val checkpoint = AndroidDeliveryStore(context, databaseName)
        assertEquals(LegacyV1RuntimeOwner.SQLITE, checkpoint.legacyV1RuntimeOwner())
        checkpoint.close()

        val retry = coordinator(
            migrationHooks = LegacyMigrationHooks(
                afterStatePersisted = { throw AssertionError("migration reran after ownership") },
            )
        )
        val prepared = retry.prepare(nowMs = 101)
        assertEquals(LegacyV1RuntimeOwner.SQLITE, prepared.owner)
        assertTrue(prepared.packetStore.contains("legacy-after-marker"))
        assertEquals(sourceBefore, legacyContents())
        retry.close()
        crashing.close()
    }

    @Test
    fun `persisted owner fails closed for a different local NodeId`() = runBlocking {
        val legacy = SharedPreferencesPacketStore(context, preferencesName)
        legacy.put(envelope("legacy-node-bound", REMOTE_NODE_ID, LOCAL_NODE_ID))
        val sourceBefore = legacyContents()
        val first = coordinator()
        first.prepare(nowMs = 100)
        first.close()

        val wrongNode = coordinator(localNodeId = OTHER_NODE_ID)
        expectSuspendThrows(LegacyV1CutoverException::class.java) {
            wrongNode.prepare(nowMs = 101)
        }

        assertEquals(sourceBefore, legacyContents())
        val checkpoint = AndroidDeliveryStore(context, databaseName)
        assertEquals(LegacyV1RuntimeOwner.SQLITE, checkpoint.legacyV1RuntimeOwner())
        assertEquals(
            LOCAL_NODE_ID,
            checkpoint.legacyMigrationStatus(
                LegacyPacketStoreMigrator.DEFAULT_MIGRATION_ID
            )?.localNodeId,
        )
        checkpoint.close()
        wrongNode.close()
    }

    @Test
    fun `two concurrent preparations serialize migration and claim one write-once owner`() =
        runBlocking {
            val legacy = SharedPreferencesPacketStore(context, preferencesName)
            legacy.put(envelope("legacy-concurrent", REMOTE_NODE_ID, OTHER_NODE_ID))
            val sourceBefore = legacyContents()
            val first = coordinator()
            val second = coordinator()
            val start = CompletableDeferred<Unit>()

            val results = listOf(first, second).map { candidate ->
                async(Dispatchers.Default) {
                    start.await()
                    candidate.prepare(nowMs = 100)
                }
            }
            start.complete(Unit)
            val prepared = results.awaitAll()

            assertEquals(
                setOf(LegacyV1RuntimeOwner.SQLITE),
                prepared.mapTo(mutableSetOf(), LegacyV1RuntimePreparation::owner),
            )
            assertTrue(prepared.all { it.packetStore.contains("legacy-concurrent") })
            assertEquals(sourceBefore, legacyContents())
            val checkpoint = AndroidDeliveryStore(context, databaseName)
            assertEquals(LegacyV1RuntimeOwner.SQLITE, checkpoint.legacyV1RuntimeOwner())
            assertEquals(1, checkpoint.listOutbox().size)
            checkpoint.close()
            first.close()
            second.close()
        }

    @Test
    fun `two concurrent calls on one coordinator return the same prepared authority`() =
        runBlocking {
            val legacy = SharedPreferencesPacketStore(context, preferencesName)
            legacy.put(envelope("legacy-same-coordinator", REMOTE_NODE_ID, OTHER_NODE_ID))
            val coordinator = coordinator()

            val results = listOf(
                async(Dispatchers.Default) { coordinator.prepare(nowMs = 100) },
                async(Dispatchers.Default) { coordinator.prepare(nowMs = 100) },
            ).awaitAll()

            assertSame(results[0], results[1])
            coordinator.close()
        }

    @Test
    fun `stale import manifest cannot reinterpret a future generic object after tombstone pruning`() =
        runBlocking {
            val packetId = "legacy-pruned-identity"
            val legacy = SharedPreferencesPacketStore(context, preferencesName)
            legacy.put(
                envelope(packetId, REMOTE_NODE_ID, OTHER_NODE_ID).copy(expiresAtMs = 200)
            )
            val coordinator = coordinator()
            val prepared = coordinator.prepare(nowMs = 100)

            assertEquals(1, prepared.packetStore.purgeExpired(nowMs = 200))
            assertEquals(
                1,
                prepared.deliveryStore.pruneExpiredTombstones(nowMs = 1_200, limit = 10),
            )
            val generic = DeliveryObject.copyOf(
                deliveryId = DeliveryId(packetId),
                destination = EndpointId("dtn://future.example/reused-id"),
                source = null,
                createdAtMs = 1_200,
                expiresAtMs = 5_000,
                canonicalBytes = byteArrayOf(9, 8, 7),
            )
            prepared.deliveryStore.ingest(
                DeliveryIngest(
                    deliveryObject = generic,
                    destinationIsLocal = false,
                    provenance = IngressProvenance(
                        IngressProvenance.Kind.REMOTE_PROTOCOL,
                        "future-profile",
                    ),
                ),
                nowMs = 1_201,
            )

            assertFalse(prepared.packetStore.contains(packetId))
            assertFalse(prepared.packetStore.list().any { it.packetId == packetId })
            assertEquals(
                DeliveryState.WAITING,
                prepared.deliveryStore.snapshot(DeliveryId(packetId)).deliveryRecord?.state,
            )
            coordinator.close()
        }

    @Test
    fun `malformed migration fails before ownership and leaves SharedPreferences byte exact`() =
        runBlocking {
            val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            assertTrue(preferences.edit().putString("packet.bad", "%%%not-base64%%%").commit())
            val sourceBefore = legacyContents()
            val coordinator = coordinator()

            expectSuspendThrows(LegacyMigrationValidationException::class.java) {
                coordinator.prepare(nowMs = 100)
            }

            assertEquals(sourceBefore, legacyContents())
            val checkpoint = AndroidDeliveryStore(context, databaseName)
            assertNull(checkpoint.legacyV1RuntimeOwner())
            assertNull(
                checkpoint.legacyMigrationStatus(
                    LegacyPacketStoreMigrator.DEFAULT_MIGRATION_ID
                )
            )
            checkpoint.close()
            coordinator.close()
        }

    private fun coordinator(
        localNodeId: NodeId = LOCAL_NODE_ID,
        migrationHooks: LegacyMigrationHooks = LegacyMigrationHooks(),
        cutoverHooks: LegacyV1CutoverHooks = LegacyV1CutoverHooks(),
    ): LegacyV1RuntimeCutoverCoordinator = LegacyV1RuntimeCutoverCoordinator(
        context = context,
        localNodeId = localNodeId,
        databaseName = databaseName,
        legacyPreferencesName = preferencesName,
        tombstoneRetentionMs = 1_000,
        clock = { 100L },
        migrationHooks = migrationHooks,
        cutoverHooks = cutoverHooks,
    )

    private fun legacyContents(): Map<String, *> =
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).all.toMap()

    private fun envelope(
        id: String,
        source: NodeId,
        destination: NodeId?,
    ): MeshEnvelope = MeshEnvelope(
        packetId = id,
        sourceNodeId = source.value,
        destinationNodeId = destination?.value,
        createdAtMs = 10,
        expiresAtMs = 10_000,
        priority = PacketPriority.NORMAL,
        contentType = "text/plain",
        payloadBase64 = "bGVnYWN5LWN1dG92ZXI=",
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

    private companion object {
        val LOCAL_NODE_ID = NodeId("om1-11111111111111111111111111111111")
        val REMOTE_NODE_ID = NodeId("om1-22222222222222222222222222222222")
        val OTHER_NODE_ID = NodeId("om1-33333333333333333333333333333333")
    }
}
