package com.openmesh.android

import android.content.Context
import com.openmesh.core.NodeId
import com.openmesh.core.PacketStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

internal data class PreparedLegacyV1PacketStore(
    val packetStore: PacketStore,
)

internal interface LegacyV1CutoverHandle : Closeable {
    suspend fun prepare(): PreparedLegacyV1PacketStore
}

internal interface MeshNodeRuntimeHandle : Closeable {
    val exposedNode: BleMeshNode?
    fun start(): MeshNodeStartResult
    fun stop()
}

internal sealed interface MeshNodeRuntimeStatus {
    data object PreparingStorage : MeshNodeRuntimeStatus
    data object Active : MeshNodeRuntimeStatus
    data object BluetoothUnavailable : MeshNodeRuntimeStatus
    data object BluetoothDisabled : MeshNodeRuntimeStatus
    data object PermissionsRequired : MeshNodeRuntimeStatus
    data object AdvertisingUnsupported : MeshNodeRuntimeStatus
    data object CutoverFailed : MeshNodeRuntimeStatus
    data object IdentityPersistenceFailed : MeshNodeRuntimeStatus
    data object NodeIdentityMismatch : MeshNodeRuntimeStatus
    data class TransportFailure(val component: String) : MeshNodeRuntimeStatus
}

/**
 * Main-thread state machine for the production v1 runtime cutover.
 *
 * Only [LegacyV1CutoverHandle.prepare] runs on [preparationDispatcher]. All
 * authority publication, node creation and radio startup resume in [scope].
 */
internal class MeshNodeServiceRuntime(
    private val scope: CoroutineScope,
    private val preparationDispatcher: CoroutineDispatcher,
    private val radioSnapshot: () -> RadioSnapshot,
    private val coordinatorFactory: (NodeId) -> LegacyV1CutoverHandle,
    private val nodeFactory: (NodeId, PacketStore) -> MeshNodeRuntimeHandle,
    private val persistNodeId: (NodeId) -> Boolean,
    private val publishStatus: (MeshNodeRuntimeStatus) -> Unit,
) {
    private var requestedNodeId: NodeId? = null
    private var preparedNodeId: NodeId? = null
    private var preparedStore: PacketStore? = null
    private var coordinator: LegacyV1CutoverHandle? = null
    private var preparationJob: Job? = null
    private var node: MeshNodeRuntimeHandle? = null
    private var requestMayBeReplaced = false
    private var bluetoothForcedOff = false
    private var generation = 0L
    private var closed = false

    val exposedNode: BleMeshNode?
        get() = node?.exposedNode

    internal val isPreparing: Boolean
        get() = preparationJob?.isActive == true

    internal val activeNodeId: NodeId?
        get() = preparedNodeId

    internal val hasRuntime: Boolean
        get() = requestedNodeId != null || preparedNodeId != null

    fun requestStart(nodeId: NodeId): Boolean {
        if (closed) return false
        val binding = preparedNodeId ?: requestedNodeId?.takeUnless { requestMayBeReplaced }
        if (binding != null && binding != nodeId) {
            publishStatus(MeshNodeRuntimeStatus.NodeIdentityMismatch)
            return false
        }
        requestedNodeId = nodeId
        requestMayBeReplaced = false
        drive()
        return true
    }

    fun onBluetoothOff() {
        if (closed) return
        bluetoothForcedOff = true
        node?.stop()
        publishStatus(MeshNodeRuntimeStatus.BluetoothDisabled)
    }

    fun onBluetoothOn() {
        if (closed) return
        bluetoothForcedOff = false
        drive()
    }

    fun retry() {
        if (closed) return
        drive()
    }

    fun shutdown() {
        if (closed) return
        closed = true
        generation += 1
        preparationJob?.cancel()
        preparationJob = null

        val nodeToClose = node
        node = null
        runCatching { nodeToClose?.close() }

        preparedStore = null
        preparedNodeId = null
        requestedNodeId = null
        val coordinatorToClose = coordinator
        coordinator = null
        runCatching { coordinatorToClose?.close() }
    }

    private fun drive() {
        if (closed) return
        val nodeId = requestedNodeId ?: return
        val radio = radioSnapshot()
        when {
            !radio.bluetoothAvailable -> pause(
                MeshNodeRuntimeStatus.BluetoothUnavailable
            )

            radio.missingBlePermissions.isNotEmpty() -> pause(
                MeshNodeRuntimeStatus.PermissionsRequired
            )

            bluetoothForcedOff || !radio.bluetoothEnabled -> pause(
                MeshNodeRuntimeStatus.BluetoothDisabled
            )

            !radio.bleAdvertisingSupported -> pause(
                MeshNodeRuntimeStatus.AdvertisingUnsupported
            )

            preparedStore != null -> createOrStartNode(nodeId, checkNotNull(preparedStore))
            preparationJob == null -> beginPreparation(nodeId)
        }
    }

    private fun pause(status: MeshNodeRuntimeStatus) {
        node?.stop()
        publishStatus(status)
    }

    private fun beginPreparation(nodeId: NodeId) {
        val selectedCoordinator = try {
            coordinator ?: coordinatorFactory(nodeId).also { coordinator = it }
        } catch (_: Throwable) {
            requestMayBeReplaced = true
            publishStatus(MeshNodeRuntimeStatus.CutoverFailed)
            return
        }
        requestMayBeReplaced = false
        val token = ++generation
        publishStatus(MeshNodeRuntimeStatus.PreparingStorage)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result: Result<PreparedLegacyV1PacketStore> = try {
                Result.success(
                    withContext(preparationDispatcher) {
                        selectedCoordinator.prepare()
                    }
                )
            } catch (cancelled: CancellationException) {
                if (isCurrent(token, nodeId, selectedCoordinator)) preparationJob = null
                return@launch
            } catch (failure: Throwable) {
                Result.failure(failure)
            }

            if (!isCurrent(token, nodeId, selectedCoordinator)) return@launch
            preparationJob = null
            result.fold(
                onSuccess = { prepared -> publishPreparation(nodeId, prepared) },
                onFailure = { failPreparation(selectedCoordinator) },
            )
        }
        preparationJob = job
        job.start()
    }

    private fun publishPreparation(
        nodeId: NodeId,
        preparation: PreparedLegacyV1PacketStore,
    ) {
        if (!persistNodeId(nodeId)) {
            failPreparation(checkNotNull(coordinator), identityPersistenceFailed = true)
            return
        }
        preparedNodeId = nodeId
        preparedStore = preparation.packetStore
        requestMayBeReplaced = false
        drive()
    }

    private fun failPreparation(
        failedCoordinator: LegacyV1CutoverHandle,
        identityPersistenceFailed: Boolean = false,
    ) {
        if (coordinator === failedCoordinator) coordinator = null
        runCatching { failedCoordinator.close() }
        requestMayBeReplaced = true
        publishStatus(
            if (identityPersistenceFailed) {
                MeshNodeRuntimeStatus.IdentityPersistenceFailed
            } else {
                MeshNodeRuntimeStatus.CutoverFailed
            }
        )
    }

    private fun createOrStartNode(nodeId: NodeId, store: PacketStore) {
        if (preparedNodeId != nodeId) {
            publishStatus(MeshNodeRuntimeStatus.NodeIdentityMismatch)
            return
        }
        val activeNode = try {
            node ?: nodeFactory(nodeId, store).also { node = it }
        } catch (_: Throwable) {
            publishStatus(MeshNodeRuntimeStatus.TransportFailure("node-construction"))
            return
        }
        when (val result = activeNode.start()) {
            MeshNodeStartResult.Started -> publishStatus(MeshNodeRuntimeStatus.Active)
            is MeshNodeStartResult.PermissionsRequired ->
                publishStatus(MeshNodeRuntimeStatus.PermissionsRequired)
            MeshNodeStartResult.BluetoothDisabled ->
                publishStatus(MeshNodeRuntimeStatus.BluetoothDisabled)
            MeshNodeStartResult.BluetoothUnavailable ->
                publishStatus(MeshNodeRuntimeStatus.BluetoothUnavailable)
            MeshNodeStartResult.AdvertisingUnsupported ->
                publishStatus(MeshNodeRuntimeStatus.AdvertisingUnsupported)
            is MeshNodeStartResult.TransportFailure -> publishStatus(
                MeshNodeRuntimeStatus.TransportFailure(result.component)
            )
        }
    }

    private fun isCurrent(
        token: Long,
        nodeId: NodeId,
        selectedCoordinator: LegacyV1CutoverHandle,
    ): Boolean = !closed &&
        generation == token &&
        requestedNodeId == nodeId &&
        coordinator === selectedCoordinator
}

internal fun productionLegacyV1CutoverHandle(
    context: Context,
    nodeId: NodeId,
): LegacyV1CutoverHandle {
    val coordinator = LegacyV1RuntimeCutoverCoordinator(
        context = context,
        localNodeId = nodeId,
        tombstoneReplayGuardMs = LegacyV1DeliveryPacketStore.DEFAULT_REPLAY_GUARD_MS,
    )
    return object : LegacyV1CutoverHandle {
        override suspend fun prepare(): PreparedLegacyV1PacketStore =
            PreparedLegacyV1PacketStore(coordinator.prepare().packetStore)

        override fun close() = coordinator.close()
    }
}
