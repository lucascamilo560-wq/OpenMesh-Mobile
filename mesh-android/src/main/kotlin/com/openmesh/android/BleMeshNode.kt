package com.openmesh.android

import android.content.Context
import android.util.Base64
import com.openmesh.core.IngestResult
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshKeyPair
import com.openmesh.core.MeshNodeId
import com.openmesh.core.MeshRouter
import com.openmesh.core.PacketPriority
import com.openmesh.core.PacketStore
import com.openmesh.core.SecureMeshMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Android-first OpenMesh node.
 *
 * BLE advertising announces presence. Exact identity is resolved over GATT.
 * Packets are store-and-forward, but a newly queued packet also triggers an
 * immediate flush to every currently known nearby peer.
 */
class BleMeshNode(
    context: Context,
    val localNodeId: String,
    store: PacketStore = SharedPreferencesPacketStore(context),
    private val localIdentity: MeshKeyPair? = null,
) {
    private val appContext = context.applicationContext
    private val guard = MeshRadioGuard(appContext)
    private val router = MeshRouter(localNodeId, store)
    private val advertiser = BleMeshAdvertiser(appContext)
    private val scanner = BleMeshScanner(appContext)
    private val identityClient = BlePeerIdentityClient(appContext)
    private val client = BleMeshGattClient(appContext)
    private val verifiedPeerKeyStore = VerifiedPeerKeyStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val activePeerJobs = ConcurrentHashMap<String, Job>()
    private val lastIdentityAttemptAt = ConcurrentHashMap<String, Long>()
    private val peerKnownPacketIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val resolvedPeersByAddress = ConcurrentHashMap<String, ResolvedPeerIdentity>()
    private val addressByNodeId = ConcurrentHashMap<String, String>()
    private val peerPublicKeys = ConcurrentHashMap<String, String>()
    private var retryJob: Job? = null

    private val _transportEvents = MutableSharedFlow<MeshTransportEvent>(extraBufferCapacity = 128)
    val transportEvents: SharedFlow<MeshTransportEvent> = _transportEvents.asSharedFlow()

    private val server = BleMeshGattServer(
        context = appContext,
        localNodeId = localNodeId,
        localIdentity = localIdentity,
    ) { envelope ->
        val result = router.ingest(envelope)
        if (
            result != IngestResult.DUPLICATE &&
            result != IngestResult.REJECTED_EXPIRED &&
            result != IngestResult.REJECTED_PROTOCOL
        ) {
            scheduleFlushAllKnownPeers(force = true)
        }
    }

    init {
        require(localIdentity == null || localIdentity.nodeId == localNodeId) {
            "Local identity does not match localNodeId"
        }
    }

    val deliveries: SharedFlow<MeshEnvelope> = router.deliveries

    fun start(): MeshNodeStartResult {
        if (!MeshNodeId.isValid(localNodeId)) {
            return MeshNodeStartResult.TransportFailure("invalid-node-id")
        }

        val snapshot = guard.snapshot()
        if (!snapshot.bluetoothAvailable) return MeshNodeStartResult.BluetoothUnavailable
        if (snapshot.missingBlePermissions.isNotEmpty()) {
            return MeshNodeStartResult.PermissionsRequired(snapshot.missingBlePermissions)
        }
        if (!snapshot.bluetoothEnabled) return MeshNodeStartResult.BluetoothDisabled
        if (!snapshot.bleAdvertisingSupported) return MeshNodeStartResult.AdvertisingUnsupported

        if (!server.start()) return MeshNodeStartResult.TransportFailure("gatt-server")
        if (!advertiser.start(localNodeId)) {
            server.stop()
            return MeshNodeStartResult.TransportFailure("ble-advertiser")
        }
        if (!scanner.start(::onPeerSeen)) {
            advertiser.stop()
            server.stop()
            return MeshNodeStartResult.TransportFailure("ble-scanner")
        }

        startRetryLoop()
        return MeshNodeStartResult.Started
    }

    fun stop() {
        retryJob?.cancel()
        retryJob = null
        scanner.stop()
        advertiser.stop()
        server.stop()
        activePeerJobs.values.forEach(Job::cancel)
        activePeerJobs.clear()
    }

    fun close() {
        stop()
        server.close()
        scope.cancel()
    }

    suspend fun queue(envelope: MeshEnvelope): IngestResult {
        val result = router.createLocal(envelope)
        scheduleFlushAllKnownPeers(force = true)
        return result
    }

    suspend fun send(
        payload: ByteArray,
        destinationNodeId: String? = null,
        ttlMs: Long = DEFAULT_TTL_MS,
        priority: PacketPriority = PacketPriority.NORMAL,
        contentType: String = "application/octet-stream",
    ): MeshEnvelope {
        val envelope = MeshEnvelope.expiresIn(
            sourceNodeId = localNodeId,
            destinationNodeId = destinationNodeId,
            ttlMs = ttlMs,
            priority = priority,
            contentType = contentType,
            payloadBase64 = Base64.encodeToString(payload, Base64.NO_WRAP),
        )
        router.createLocal(envelope)
        scheduleFlushAllKnownPeers(force = true)
        return envelope
    }

    fun knownPeerPublicKey(nodeId: String): String? =
        peerPublicKeys[nodeId]
            ?: verifiedPeerKeyStore.getVerifiedPublicKey(nodeId)?.also { key ->
                peerPublicKeys[nodeId] = key
            }

    fun verifiedPeerMetadata(nodeId: String): VerifiedPeerMetadata? =
        verifiedPeerKeyStore.metadata(nodeId)

    fun verifiedPeers(): List<VerifiedPeerMetadata> = verifiedPeerKeyStore.listVerified()

    suspend fun sendSecure(
        payload: ByteArray,
        contentType: String,
        senderIdentity: MeshKeyPair,
        recipientNodeId: String,
        recipientPublicKeyBase64: String,
        ttlMs: Long = DEFAULT_TTL_MS,
        priority: PacketPriority = PacketPriority.NORMAL,
    ): MeshEnvelope {
        require(senderIdentity.nodeId == localNodeId) {
            "Sender identity does not match this OpenMesh node"
        }
        val envelope = SecureMeshMessage.create(
            plaintext = payload,
            innerContentType = contentType,
            sender = senderIdentity,
            recipientNodeId = recipientNodeId,
            recipientPublicKeyBase64 = recipientPublicKeyBase64,
            ttlMs = ttlMs,
            priority = priority,
        )
        router.createLocal(envelope)

        val directAddress = addressByNodeId[recipientNodeId]
        if (directAddress != null) {
            scheduleFlushAddress(directAddress, force = true)
        } else {
            scheduleFlushAllKnownPeers(force = true)
        }
        return envelope
    }

    private fun onPeerSeen(peer: PeerAdvertisement) {
        val address = peer.deviceAddress
        val cached = resolvedPeersByAddress[address]
        if (cached != null) {
            addressByNodeId[cached.nodeId] = address
            scheduleFlushAddress(address, force = false)
            return
        }

        val now = System.currentTimeMillis()
        val previous = lastIdentityAttemptAt[address] ?: 0L
        if (now - previous < IDENTITY_RETRY_COOLDOWN_MS) return
        lastIdentityAttemptAt[address] = now
        scheduleResolveAndFlush(address)
    }

    private fun scheduleResolveAndFlush(address: String) {
        activePeerJobs.compute(address) { _, existing ->
            if (existing?.isActive == true) return@compute existing
            scope.launch {
                try {
                    val identity = identityClient.resolve(address) ?: return@launch
                    resolvedPeersByAddress[address] = identity
                    addressByNodeId[identity.nodeId] = address

                    if (identity.possessionVerified) {
                        identity.publicKeyBase64?.let { key ->
                            val persisted = runCatching {
                                verifiedPeerKeyStore.putVerified(
                                    nodeId = identity.nodeId,
                                    publicKeyBase64 = key,
                                )
                            }.isSuccess
                            if (persisted) peerPublicKeys[identity.nodeId] = key
                        }
                    }

                    if (identity.nodeId != localNodeId) {
                        // Some Android BLE stacks need a short gap after the
                        // identity connection closes before a second GATT client
                        // connection to the same peripheral is opened.
                        delay(POST_IDENTITY_GATT_SETTLE_MS)
                        flushResolvedPeer(address, identity)
                    }
                } finally {
                    activePeerJobs.remove(address)
                }
            }
        }
    }

    private fun scheduleFlushAddress(address: String, force: Boolean) {
        val resolved = resolvedPeersByAddress[address] ?: run {
            scheduleResolveAndFlush(address)
            return
        }
        if (resolved.nodeId == localNodeId) return

        activePeerJobs.compute(address) { _, existing ->
            if (existing?.isActive == true) return@compute existing
            scope.launch {
                try {
                    if (!force) delay(PASSIVE_FLUSH_DEBOUNCE_MS)
                    flushResolvedPeer(address, resolved)
                } finally {
                    activePeerJobs.remove(address)
                }
            }
        }
    }

    private suspend fun flushResolvedPeer(
        address: String,
        resolved: ResolvedPeerIdentity,
    ) {
        val peerNodeId = resolved.nodeId
        val known = peerKnownPacketIds.computeIfAbsent(peerNodeId) {
            ConcurrentHashMap.newKeySet<String>()
        }
        if (known.size > MAX_KNOWN_PACKETS_PER_PEER) known.clear()

        val batch = router.nextBatchForPeer(
            peerNodeId = peerNodeId,
            peerKnownPacketIds = known,
            limit = MAX_PACKETS_PER_CONTACT,
        )
        if (batch.isEmpty()) return

        for (envelope in batch) {
            when (val result = client.send(address, envelope)) {
                is BleGattSendResult.Failed -> {
                    _transportEvents.emit(
                        MeshTransportEvent.SendFailed(
                            packetId = envelope.packetId,
                            peerNodeId = peerNodeId,
                            stage = result.stage,
                            status = result.status,
                            frameIndex = result.frameIndex,
                            frameCount = result.frameCount,
                            detail = result.detail,
                        )
                    )
                    resolvedPeersByAddress.remove(address)
                    addressByNodeId.remove(peerNodeId, address)
                    break
                }

                is BleGattSendResult.Success -> {
                    known.add(envelope.packetId)
                    _transportEvents.emit(
                        MeshTransportEvent.Forwarded(
                            packetId = envelope.packetId,
                            peerNodeId = peerNodeId,
                            finalDestination = envelope.destinationNodeId == peerNodeId,
                            frameCount = result.frameCount,
                        )
                    )
                }
            }
        }
    }

    private fun scheduleFlushAllKnownPeers(force: Boolean) {
        resolvedPeersByAddress.keys.forEach { address ->
            scheduleFlushAddress(address, force)
        }
    }

    private fun startRetryLoop() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MS)
                scheduleFlushAllKnownPeers(force = true)
            }
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 72L * 60L * 60L * 1000L
        private const val MAX_PACKETS_PER_CONTACT = 8
        private const val MAX_KNOWN_PACKETS_PER_PEER = 2_048
        private const val IDENTITY_RETRY_COOLDOWN_MS = 5_000L
        private const val POST_IDENTITY_GATT_SETTLE_MS = 750L
        private const val PASSIVE_FLUSH_DEBOUNCE_MS = 350L
        private const val RETRY_INTERVAL_MS = 4_000L
    }
}

sealed interface MeshTransportEvent {
    data class Forwarded(
        val packetId: String,
        val peerNodeId: String,
        val finalDestination: Boolean,
        val frameCount: Int,
    ) : MeshTransportEvent

    data class SendFailed(
        val packetId: String,
        val peerNodeId: String,
        val stage: BleGattStage,
        val status: Int? = null,
        val frameIndex: Int? = null,
        val frameCount: Int? = null,
        val detail: String? = null,
    ) : MeshTransportEvent
}

sealed interface MeshNodeStartResult {
    data object Started : MeshNodeStartResult
    data object BluetoothUnavailable : MeshNodeStartResult
    data object BluetoothDisabled : MeshNodeStartResult
    data object AdvertisingUnsupported : MeshNodeStartResult
    data class PermissionsRequired(val permissions: List<String>) : MeshNodeStartResult
    data class TransportFailure(val component: String) : MeshNodeStartResult
}
