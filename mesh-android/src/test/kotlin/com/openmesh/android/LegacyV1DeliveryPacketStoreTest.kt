package com.openmesh.android

import android.content.Context
import com.openmesh.core.CanonicalDeliveryConflictException
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
import com.openmesh.core.TombstoneReason
import com.openmesh.core.TransferContext
import com.openmesh.core.TransferReservation
import com.openmesh.core.TransferReservationResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
class LegacyV1DeliveryPacketStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var nowMs = 100L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "v1-bridge-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `router preserves remote broadcast and local v1 semantics without invented facts`() =
        runBlocking {
            val store = AndroidDeliveryStore(context, databaseName)
            val bridge = bridge(store)
            val router = MeshRouter(LOCAL_NODE_ID.value, bridge)
            val outbound = envelope(
                id = "v1-outbound",
                source = LOCAL_NODE_ID,
                destination = REMOTE_NODE_ID,
            )
            val broadcast = envelope(
                id = "v1-broadcast",
                source = REMOTE_NODE_ID,
                destination = null,
            )
            val inboundLocal = envelope(
                id = "v1-local",
                source = REMOTE_NODE_ID,
                destination = LOCAL_NODE_ID,
            )

            assertEquals(IngestResult.STORED_FOR_FORWARDING, router.createLocal(outbound, nowMs))
            assertEquals(IngestResult.DELIVERED_BROADCAST, router.ingest(broadcast, nowMs))
            assertEquals(IngestResult.DELIVERED_LOCAL, router.ingest(inboundLocal, nowMs))

            assertTrue(bridge.contains(outbound.packetId))
            assertTrue(bridge.contains(broadcast.packetId))
            assertTrue(bridge.contains(inboundLocal.packetId))
            assertEquals(
                setOf(outbound.packetId, broadcast.packetId),
                bridge.list().mapTo(mutableSetOf(), MeshEnvelope::packetId),
            )
            assertFalse(
                router.nextBatchForPeer(OTHER_NODE_ID.value, nowMs = nowMs)
                    .any { it.packetId == inboundLocal.packetId }
            )

            assertEquals(
                DeliveryState.WAITING,
                store.snapshot(DeliveryId(outbound.packetId)).deliveryRecord?.state,
            )
            assertEquals(
                DeliveryState.WAITING,
                store.snapshot(DeliveryId(broadcast.packetId)).deliveryRecord?.state,
            )
            val localSnapshot = store.snapshot(DeliveryId(inboundLocal.packetId))
            assertEquals(DeliveryState.QUARANTINED, localSnapshot.deliveryRecord?.state)
            assertNull(localSnapshot.inboxRecord)
            assertTrue(localSnapshot.transferAttempts.isEmpty())
            assertTrue(localSnapshot.nextHopAcceptances.isEmpty())
            assertTrue(localSnapshot.receipts.isEmpty())
            assertTrue(
                store.reserveTransfer(
                    TransferReservation(
                        deliveryId = DeliveryId(inboundLocal.packetId),
                        context = TransferContext("test", "local-not-forwardable"),
                        leaseDurationMs = 1_000,
                    ),
                    nowMs = nowMs,
                ) is TransferReservationResult.NotEligible
            )
            assertEquals(3, store.listOutbox().size)
            store.close()
        }

    @Test
    fun `duplicate is idempotent canonical conflict fails closed and generic objects stay hidden`() =
        runBlocking {
            val store = AndroidDeliveryStore(context, databaseName)
            val bridge = bridge(store)
            val packet = envelope("v1-dedup", REMOTE_NODE_ID, OTHER_NODE_ID)

            bridge.put(packet)
            bridge.put(packet)
            assertEquals(1, store.listOutbox().size)

            expectSuspendThrows(CanonicalDeliveryConflictException::class.java) {
                bridge.put(packet.copy(payloadBase64 = "Y29uZmxpY3Q="))
            }
            assertEquals(packet, bridge.list().single())

            val generic = DeliveryObject.copyOf(
                deliveryId = DeliveryId("future-generic-object"),
                destination = EndpointId("dtn://future.example/object"),
                source = null,
                createdAtMs = 10,
                expiresAtMs = 10_000,
                canonicalBytes = byteArrayOf(9, 8, 7),
            )
            store.ingest(
                DeliveryIngest(
                    deliveryObject = generic,
                    destinationIsLocal = false,
                    provenance = IngressProvenance(
                        IngressProvenance.Kind.REMOTE_PROTOCOL,
                        "future-profile",
                    ),
                ),
                nowMs = nowMs,
            )

            assertFalse(bridge.contains(generic.deliveryId.value))
            assertFalse(bridge.list().any { it.packetId == generic.deliveryId.value })
            expectSuspendThrows(CanonicalDeliveryConflictException::class.java) {
                bridge.put(
                    envelope(
                        generic.deliveryId.value,
                        REMOTE_NODE_ID,
                        OTHER_NODE_ID,
                    )
                )
            }
            assertArrayEquals(
                byteArrayOf(9, 8, 7),
                store.snapshot(generic.deliveryId).deliveryObject
                    ?.canonicalBytes?.copyToByteArray(),
            )
            store.close()
        }

    @Test
    fun `expiry removal duplicate suppression and active packets survive restart`() = runBlocking {
        var store = AndroidDeliveryStore(context, databaseName)
        var bridge = bridge(store)
        val expiring = envelope(
            id = "v1-expiring",
            source = REMOTE_NODE_ID,
            destination = OTHER_NODE_ID,
            expiresAtMs = 200,
        )
        val retained = envelope(
            id = "v1-restart",
            source = LOCAL_NODE_ID,
            destination = REMOTE_NODE_ID,
        )
        val removed = envelope(
            id = "v1-removed",
            source = REMOTE_NODE_ID,
            destination = OTHER_NODE_ID,
        )
        bridge.put(expiring)
        bridge.put(retained)
        bridge.put(removed)

        nowMs = 200
        assertEquals(1, bridge.purgeExpired(nowMs))
        bridge.remove(removed.packetId)
        assertTrue(bridge.contains(expiring.packetId))
        assertTrue(bridge.contains(removed.packetId))
        assertEquals(listOf(retained), bridge.list())
        store.close()

        store = AndroidDeliveryStore(context, databaseName)
        bridge = bridge(store)
        assertEquals(listOf(retained), bridge.list())
        assertTrue(bridge.contains(expiring.packetId))
        assertTrue(bridge.contains(removed.packetId))
        assertEquals(
            TombstoneReason.EXPIRED,
            store.snapshot(DeliveryId(expiring.packetId)).tombstone?.reason,
        )
        assertEquals(
            TombstoneReason.EXPLICITLY_REMOVED,
            store.snapshot(DeliveryId(removed.packetId)).tombstone?.reason,
        )

        val replay = expiring.copy(createdAtMs = 200, expiresAtMs = 2_000)
        val router = MeshRouter(LOCAL_NODE_ID.value, bridge)
        assertEquals(IngestResult.DUPLICATE, router.ingest(replay, nowMs = 201))
        assertNotNull(store.snapshot(DeliveryId(expiring.packetId)).tombstone)
        store.close()
    }

    @Test
    fun `already expired direct put creates only expired lifecycle and tombstone`() = runBlocking {
        nowMs = 500
        val store = AndroidDeliveryStore(context, databaseName)
        val bridge = bridge(store)
        val expired = envelope(
            id = "v1-direct-expired",
            source = REMOTE_NODE_ID,
            destination = OTHER_NODE_ID,
            expiresAtMs = 499,
        )

        bridge.put(expired)

        val snapshot = store.snapshot(DeliveryId(expired.packetId))
        assertNull(snapshot.deliveryObject)
        assertEquals(DeliveryState.EXPIRED, snapshot.deliveryRecord?.state)
        assertEquals(TombstoneReason.EXPIRED, snapshot.tombstone?.reason)
        assertNull(snapshot.inboxRecord)
        assertTrue(bridge.contains(expired.packetId))
        assertTrue(bridge.list().isEmpty())
        store.close()
    }

    private fun bridge(store: AndroidDeliveryStore): LegacyV1DeliveryPacketStore =
        LegacyV1DeliveryPacketStore(
            store = store,
            localNodeId = LOCAL_NODE_ID,
            clock = { nowMs },
            tombstoneRetentionMs = 1_000,
        )

    private fun envelope(
        id: String,
        source: NodeId,
        destination: NodeId?,
        expiresAtMs: Long = 10_000,
    ): MeshEnvelope = MeshEnvelope(
        packetId = id,
        sourceNodeId = source.value,
        destinationNodeId = destination?.value,
        createdAtMs = 10,
        expiresAtMs = expiresAtMs,
        priority = PacketPriority.NORMAL,
        contentType = "text/plain",
        payloadBase64 = "djEtcGF5bG9hZA==",
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

    private companion object {
        val LOCAL_NODE_ID = NodeId("om1-11111111111111111111111111111111")
        val REMOTE_NODE_ID = NodeId("om1-22222222222222222222222222222222")
        val OTHER_NODE_ID = NodeId("om1-33333333333333333333333333333333")
    }
}
