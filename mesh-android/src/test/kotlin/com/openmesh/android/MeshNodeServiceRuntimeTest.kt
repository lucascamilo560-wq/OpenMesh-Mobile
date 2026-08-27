package com.openmesh.android

import android.content.Context
import com.openmesh.core.DeliveryId
import com.openmesh.core.DeliveryState
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.NodeId
import com.openmesh.core.PacketPriority
import com.openmesh.core.PacketStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MeshNodeServiceRuntimeTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var preferencesName: String

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "service-cutover-${UUID.randomUUID()}.db"
        preferencesName = "service-cutover-legacy-${UUID.randomUUID()}"
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
    fun `preparation completes before identity persistence node construction and radio start`() {
        val events = mutableListOf<String>()
        val statuses = mutableListOf<MeshNodeRuntimeStatus>()
        val packetStore = FakePacketStore()
        val deferred = CompletableDeferred<PreparedLegacyV1PacketStore>()
        val coordinator = FakeCoordinator(deferred, events)
        val node = FakeNode(events)
        var coordinatorCreations = 0
        var injectedStore: PacketStore? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = runtime(
            scope = scope,
            coordinatorFactory = {
                coordinatorCreations += 1
                coordinator
            },
            nodeFactory = { _, store ->
                events += "create-node"
                injectedStore = store
                node
            },
            persistNodeId = {
                events += "persist-node-id"
                true
            },
            publishStatus = statuses::add,
        )

        assertTrue(runtime.requestStart(LOCAL_NODE_ID))
        assertTrue(runtime.requestStart(LOCAL_NODE_ID))
        assertTrue(runtime.isPreparing)
        assertEquals(1, coordinatorCreations)
        assertEquals(listOf("prepare"), events)
        assertNull(injectedStore)

        deferred.complete(PreparedLegacyV1PacketStore(packetStore))

        assertFalse(runtime.isPreparing)
        assertSame(packetStore, injectedStore)
        assertEquals(
            listOf("prepare", "persist-node-id", "create-node", "start-node"),
            events,
        )
        assertEquals(
            listOf(MeshNodeRuntimeStatus.PreparingStorage, MeshNodeRuntimeStatus.Active),
            statuses,
        )
        runtime.shutdown()
        scope.cancel()
    }

    @Test
    fun `cutover failure is fail closed and never constructs a fallback node`() {
        val statuses = mutableListOf<MeshNodeRuntimeStatus>()
        val deferred = CompletableDeferred<PreparedLegacyV1PacketStore>()
        val coordinator = FakeCoordinator(deferred)
        var persisted = false
        var nodeCreated = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = runtime(
            scope = scope,
            coordinatorFactory = { coordinator },
            nodeFactory = { _, _ ->
                nodeCreated = true
                FakeNode()
            },
            persistNodeId = {
                persisted = true
                true
            },
            publishStatus = statuses::add,
        )

        runtime.requestStart(LOCAL_NODE_ID)
        deferred.completeExceptionally(LegacyV1CutoverException("migration failed"))

        assertFalse(nodeCreated)
        assertFalse(persisted)
        assertTrue(coordinator.closed)
        assertEquals(MeshNodeRuntimeStatus.CutoverFailed, statuses.last())
        runtime.shutdown()
        scope.cancel()
    }

    @Test
    fun `shutdown during preparation cannot publish a late node`() {
        val deferred = CompletableDeferred<PreparedLegacyV1PacketStore>()
        val coordinator = FakeCoordinator(deferred)
        var nodeCreated = false
        var persisted = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = runtime(
            scope = scope,
            coordinatorFactory = { coordinator },
            nodeFactory = { _, _ ->
                nodeCreated = true
                FakeNode()
            },
            persistNodeId = {
                persisted = true
                true
            },
        )

        runtime.requestStart(LOCAL_NODE_ID)
        runtime.shutdown()
        deferred.complete(PreparedLegacyV1PacketStore(FakePacketStore()))

        assertFalse(nodeCreated)
        assertFalse(persisted)
        assertTrue(coordinator.closed)
        assertNull(runtime.exposedNode)
        scope.cancel()
    }

    @Test
    fun `bluetooth off during preparation pauses publication and on reuses prepared runtime`() {
        val events = mutableListOf<String>()
        val deferred = CompletableDeferred<PreparedLegacyV1PacketStore>()
        val coordinator = FakeCoordinator(deferred, events)
        val node = FakeNode(events)
        var nodeCreations = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = runtime(
            scope = scope,
            coordinatorFactory = { coordinator },
            nodeFactory = { _, _ ->
                nodeCreations += 1
                events += "create-node"
                node
            },
            persistNodeId = {
                events += "persist-node-id"
                true
            },
        )

        runtime.requestStart(LOCAL_NODE_ID)
        runtime.onBluetoothOff()
        deferred.complete(PreparedLegacyV1PacketStore(FakePacketStore()))

        assertEquals(0, nodeCreations)
        assertEquals(LOCAL_NODE_ID, runtime.activeNodeId)
        runtime.onBluetoothOn()
        assertEquals(1, nodeCreations)
        assertEquals(1, coordinator.prepareCalls)
        assertEquals(1, node.startCalls)

        runtime.onBluetoothOff()
        assertEquals(1, node.stopCalls)
        runtime.onBluetoothOn()
        assertEquals(1, nodeCreations)
        assertEquals(1, coordinator.prepareCalls)
        assertEquals(2, node.startCalls)
        runtime.shutdown()
        scope.cancel()
    }

    @Test
    fun `different NodeId cannot replace active authority or contaminate persisted identity`() {
        val persisted = mutableListOf<NodeId>()
        val coordinator = FakeCoordinator(
            CompletableDeferred(PreparedLegacyV1PacketStore(FakePacketStore()))
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = runtime(
            scope = scope,
            coordinatorFactory = { coordinator },
            nodeFactory = { _, _ -> FakeNode() },
            persistNodeId = {
                persisted += it
                true
            },
        )

        assertTrue(runtime.requestStart(LOCAL_NODE_ID))
        assertFalse(runtime.requestStart(OTHER_NODE_ID))

        assertEquals(listOf(LOCAL_NODE_ID), persisted)
        assertEquals(LOCAL_NODE_ID, runtime.activeNodeId)
        assertEquals(1, coordinator.prepareCalls)
        runtime.shutdown()
        scope.cancel()
    }

    @Test
    fun `identity commit failure closes SQLite authority before any node starts`() {
        val statuses = mutableListOf<MeshNodeRuntimeStatus>()
        val coordinator = FakeCoordinator(
            CompletableDeferred(PreparedLegacyV1PacketStore(FakePacketStore()))
        )
        var nodeCreated = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = runtime(
            scope = scope,
            coordinatorFactory = { coordinator },
            nodeFactory = { _, _ ->
                nodeCreated = true
                FakeNode()
            },
            persistNodeId = { false },
            publishStatus = statuses::add,
        )

        runtime.requestStart(LOCAL_NODE_ID)

        assertFalse(nodeCreated)
        assertTrue(coordinator.closed)
        assertNull(runtime.activeNodeId)
        assertEquals(MeshNodeRuntimeStatus.IdentityPersistenceFailed, statuses.last())
        runtime.shutdown()
        scope.cancel()
    }

    @Test
    fun `shutdown closes node before coordinator`() {
        val events = mutableListOf<String>()
        val coordinator = FakeCoordinator(
            CompletableDeferred(PreparedLegacyV1PacketStore(FakePacketStore())),
            events,
        )
        val node = FakeNode(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = runtime(
            scope = scope,
            coordinatorFactory = { coordinator },
            nodeFactory = { _, _ -> node },
            persistNodeId = { true },
        )
        runtime.requestStart(LOCAL_NODE_ID)

        runtime.shutdown()

        assertTrue(events.indexOf("close-node") < events.indexOf("close-coordinator"))
        scope.cancel()
    }

    @Test
    fun `radio must be ready before preparation begins`() {
        var radio = READY_RADIO.copy(bluetoothEnabled = false)
        val coordinator = FakeCoordinator(
            CompletableDeferred(PreparedLegacyV1PacketStore(FakePacketStore()))
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = runtime(
            scope = scope,
            radioSnapshot = { radio },
            coordinatorFactory = { coordinator },
            nodeFactory = { _, _ -> FakeNode() },
            persistNodeId = { true },
        )

        runtime.requestStart(LOCAL_NODE_ID)
        assertEquals(0, coordinator.prepareCalls)

        radio = READY_RADIO
        runtime.onBluetoothOn()
        assertEquals(1, coordinator.prepareCalls)
        runtime.shutdown()
        scope.cancel()
    }

    @Test
    fun `production cutover store owns new outbound and restart never rewrites legacy source`() =
        runBlocking {
            val legacy = SharedPreferencesPacketStore(context, preferencesName)
            val legacyPacket = envelope(
                id = "service-legacy",
                source = REMOTE_NODE_ID,
                destination = OTHER_NODE_ID,
            )
            legacy.put(legacyPacket)
            val sourceBefore = legacyContents()
            var capturedStore: PacketStore? = null
            val firstCoordinator = coordinator()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val runtime = runtime(
                scope = scope,
                coordinatorFactory = { coordinatorHandle(firstCoordinator) },
                nodeFactory = { _, store ->
                    capturedStore = store
                    FakeNode()
                },
                persistNodeId = { true },
            )

            runtime.requestStart(LOCAL_NODE_ID)
            val sqlitePacketStore = checkNotNull(capturedStore)
            val outbound = envelope(
                id = "service-outbound",
                source = LOCAL_NODE_ID,
                destination = REMOTE_NODE_ID,
            )
            sqlitePacketStore.put(outbound)

            assertEquals(sourceBefore, legacyContents())
            val checkpoint = AndroidDeliveryStore(context, databaseName)
            assertEquals(
                DeliveryState.WAITING,
                checkpoint.snapshot(DeliveryId(outbound.packetId)).deliveryRecord?.state,
            )
            checkpoint.close()
            runtime.shutdown()
            scope.cancel()

            val restarted = coordinator(
                migrationHooks = LegacyMigrationHooks(
                    afterStatePersisted = {
                        throw AssertionError("migration reran after SQLite ownership")
                    }
                )
            )
            val recovered = restarted.prepare(nowMs = 200)
            assertTrue(recovered.packetStore.contains(outbound.packetId))
            assertEquals(sourceBefore, legacyContents())
            restarted.close()
        }

    private fun runtime(
        scope: CoroutineScope,
        radioSnapshot: () -> RadioSnapshot = { READY_RADIO },
        coordinatorFactory: (NodeId) -> LegacyV1CutoverHandle,
        nodeFactory: (NodeId, PacketStore) -> MeshNodeRuntimeHandle,
        persistNodeId: (NodeId) -> Boolean,
        publishStatus: (MeshNodeRuntimeStatus) -> Unit = {},
    ): MeshNodeServiceRuntime = MeshNodeServiceRuntime(
        scope = scope,
        preparationDispatcher = Dispatchers.Unconfined,
        radioSnapshot = radioSnapshot,
        coordinatorFactory = coordinatorFactory,
        nodeFactory = nodeFactory,
        persistNodeId = persistNodeId,
        publishStatus = publishStatus,
    )

    private fun coordinator(
        migrationHooks: LegacyMigrationHooks = LegacyMigrationHooks(),
    ): LegacyV1RuntimeCutoverCoordinator = LegacyV1RuntimeCutoverCoordinator(
        context = context,
        localNodeId = LOCAL_NODE_ID,
        databaseName = databaseName,
        legacyPreferencesName = preferencesName,
        tombstoneReplayGuardMs = 1_000,
        clock = { 100L },
        migrationHooks = migrationHooks,
        cutoverHooks = LegacyV1CutoverHooks(),
    )

    private fun coordinatorHandle(
        coordinator: LegacyV1RuntimeCutoverCoordinator,
    ): LegacyV1CutoverHandle = object : LegacyV1CutoverHandle {
        override suspend fun prepare(): PreparedLegacyV1PacketStore =
            PreparedLegacyV1PacketStore(coordinator.prepare().packetStore)

        override fun close() = coordinator.close()
    }

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
        payloadBase64 = "c2VydmljZS1jdXRvdmVy",
    )

    private class FakeCoordinator(
        private val result: CompletableDeferred<PreparedLegacyV1PacketStore>,
        private val events: MutableList<String>? = null,
    ) : LegacyV1CutoverHandle {
        var prepareCalls = 0
        var closed = false

        override suspend fun prepare(): PreparedLegacyV1PacketStore {
            prepareCalls += 1
            events?.add("prepare")
            return result.await()
        }

        override fun close() {
            closed = true
            events?.add("close-coordinator")
        }
    }

    private class FakeNode(
        private val events: MutableList<String>? = null,
        private val startResult: MeshNodeStartResult = MeshNodeStartResult.Started,
    ) : MeshNodeRuntimeHandle {
        override val exposedNode: BleMeshNode? = null
        var startCalls = 0
        var stopCalls = 0

        override fun start(): MeshNodeStartResult {
            startCalls += 1
            events?.add("start-node")
            return startResult
        }

        override fun stop() {
            stopCalls += 1
            events?.add("stop-node")
        }

        override fun close() {
            events?.add("close-node")
        }
    }

    private class FakePacketStore : PacketStore {
        private val packets = linkedMapOf<String, MeshEnvelope>()

        override suspend fun contains(packetId: String): Boolean = packetId in packets
        override suspend fun put(packet: MeshEnvelope) {
            packets[packet.packetId] = packet
        }
        override suspend fun remove(packetId: String) {
            packets.remove(packetId)
        }
        override suspend fun list(): List<MeshEnvelope> = packets.values.toList()
        override suspend fun purgeExpired(nowMs: Long): Int {
            val expired = packets.values.filter { it.expiresAtMs <= nowMs }
            expired.forEach { packets.remove(it.packetId) }
            return expired.size
        }
    }

    private companion object {
        val LOCAL_NODE_ID = NodeId("om1-11111111111111111111111111111111")
        val REMOTE_NODE_ID = NodeId("om1-22222222222222222222222222222222")
        val OTHER_NODE_ID = NodeId("om1-33333333333333333333333333333333")
        val READY_RADIO = RadioSnapshot(
            bluetoothAvailable = true,
            bluetoothEnabled = true,
            bleAdvertisingSupported = true,
            wifiEnabled = false,
            missingBlePermissions = emptyList(),
            missingWifiPermissions = emptyList(),
        )
    }
}
