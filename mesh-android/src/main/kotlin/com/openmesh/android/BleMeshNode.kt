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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Android-first OpenMesh node.
 *
 * BLE advertising only announces presence. After discovery, the exact routable
 * node ID is resolved from GATT before any packet is forwarded. Public keys are
 * exposed to callers only after the peer proves possession of the matching
 * private key with a fresh signed challenge.
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
    private val lastPeerSyncAt = ConcurrentHashMap<String, Long>()
    private val peerKnownPacketIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val resolvedPeersByAddress = ConcurrentHashMap<String, ResolvedPeerIdentity>()
    private val peerPublicKeys = ConcurrentHashMap<String, String>()

    private val server = BleMeshGattServer(
        context = appContext,
        localNodeId = localNodeId,
        localIdentity = localIdentity,
    ) { envelope ->
        router.ingest(envelope)
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
        return MeshNodeStartResult.Started
    }

    fun stop() {
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

    suspend fun queue(envelope: MeshEnvelope): IngestResult = router.createLocal(envelope)

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
        return envelope
    }

    /** Returns a peer public key only after private-key possession was verified. */
    fun knownPeerPublicKey(nodeId: String): String? =
        peerPublicKeys[nodeId]
            ?: verifiedPeerKeyStore.getVerifiedPublicKey(nodeId)?.also { key ->
                peerPublicKeys[nodeId] = key
            }

    fun verifiedPeerMetadata(nodeId: String): VerifiedPeerMetadata? =
        verifiedPeerKeyStore.metadata(nodeId)

    /** Snapshot of peers that have passed OpenMesh private-key possession verification. */
    fun verifiedPeers(): List<VerifiedPeerMetadata> = verifiedPeerKeyStore.listVerified()

    /**
     * Queues an E2E encrypted unicast envelope. The caller must already possess
     * an authenticated recipient public key; nearby advertisements alone are not
     * treated as identity proof.
     */
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
        return envelope
    }

    private fun onPeerSeen(peer: PeerAdvertisement) {
        val address = peer.deviceAddress
        val now = System.currentTimeMillis()
        val previous = lastPeerSyncAt[address] ?: 0L
        if (now - previous < PEER_SYNC_COOLDOWN_MS) return
        lastPeerSyncAt[address] = now

        activePeerJobs.compute(address) { _, existing ->
            if (existing?.isActive == true) return@compute existing
            scope.launch {
                try {
                    val resolved = resolvedPeersByAddress[address]
                        ?: identityClient.resolve(address)?.also { identity ->
                            resolvedPeersByAddress[address] = identity
                            if (identity.possessionVerified) {
                                identity.publicKeyBase64?.let { key ->
                                    val persisted = runCatching {
                                        verifiedPeerKeyStore.putVerified(
                                            nodeId = identity.nodeId,
                                            publicKeyBase64 = key,
                                        )
                                    }.isSuccess
                                    if (persisted) {
                                        peerPublicKeys[identity.nodeId] = key
                                    }
                                }
                            }
                        }
                        ?: return@launch

                    val peerNodeId = resolved.nodeId
                    if (peerNodeId == localNodeId) return@launch

                    val known = peerKnownPacketIds.computeIfAbsent(peerNodeId) {
                        ConcurrentHashMap.newKeySet<String>()
                    }
                    if (known.size > MAX_KNOWN_PACKETS_PER_PEER) known.clear()

                    val batch = router.nextBatchForPeer(
                        peerNodeId = peerNodeId,
                        peerKnownPacketIds = known,
                        limit = MAX_PACKETS_PER_CONTACT,
                    )
                    for (envelope in batch) {
                        val deliveredToPeer = client.send(address, envelope)
                        if (!deliveredToPeer) {
                            resolvedPeersByAddress.remove(address)
                            break
                        }
                        known.add(envelope.packetId)
                    }
                } finally {
                    activePeerJobs.remove(address)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 72L * 60L * 60L * 1000L
        private const val MAX_PACKETS_PER_CONTACT = 8
        private const val MAX_KNOWN_PACKETS_PER_PEER = 2_048
        private const val PEER_SYNC_COOLDOWN_MS = 15_000L
    }
}

sealed interface MeshNodeStartResult {
    data object Started : MeshNodeStartResult
    data object BluetoothUnavailable : MeshNodeStartResult
    data object BluetoothDisabled : MeshNodeStartResult
    data object AdvertisingUnsupported : MeshNodeStartResult
    data class PermissionsRequired(val permissions: List<String>) : MeshNodeStartResult
    data class TransportFailure(val component: String) : MeshNodeStartResult
}
