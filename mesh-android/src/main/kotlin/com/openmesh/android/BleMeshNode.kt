package com.openmesh.android

import android.content.Context
import android.util.Base64
import com.openmesh.core.IngestResult
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshKeyPair
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Android-first OpenMesh node.
 *
 * A node advertises itself, scans nearby nodes, accepts incoming envelopes and
 * opportunistically forwards locally persisted envelopes whenever a peer appears.
 */
class BleMeshNode(
    context: Context,
    val localNodeId: String,
    store: PacketStore = SharedPreferencesPacketStore(context),
) {
    private val appContext = context.applicationContext
    private val guard = MeshRadioGuard(appContext)
    private val router = MeshRouter(localNodeId, store)
    private val advertiser = BleMeshAdvertiser(appContext)
    private val scanner = BleMeshScanner(appContext)
    private val client = BleMeshGattClient(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activePeerJobs = ConcurrentHashMap<String, Job>()
    private val lastPeerSyncAt = ConcurrentHashMap<String, Long>()
    private val peerKnownPacketIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val localFingerprint = fingerprint(localNodeId)

    private val server = BleMeshGattServer(appContext) { envelope ->
        router.ingest(envelope)
    }

    val deliveries: SharedFlow<MeshEnvelope> = router.deliveries

    fun start(): MeshNodeStartResult {
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

    /**
     * Queues an E2E encrypted unicast envelope. The caller must already possess
     * an authenticated recipient public key; nearby advertisements are not
     * automatically trusted as identity proof.
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
        if (peer.nodeFingerprint == localFingerprint) return
        val peerKey = peer.nodeFingerprint
        val now = System.currentTimeMillis()
        val previous = lastPeerSyncAt[peerKey] ?: 0L
        if (now - previous < PEER_SYNC_COOLDOWN_MS) return
        lastPeerSyncAt[peerKey] = now

        activePeerJobs.compute(peerKey) { _, existing ->
            if (existing?.isActive == true) return@compute existing
            scope.launch {
                try {
                    val known = peerKnownPacketIds.computeIfAbsent(peerKey) {
                        ConcurrentHashMap.newKeySet<String>()
                    }
                    if (known.size > MAX_KNOWN_PACKETS_PER_PEER) known.clear()

                    val batch = router.nextBatchForPeer(
                        peerNodeId = peerKey,
                        peerKnownPacketIds = known,
                        limit = MAX_PACKETS_PER_CONTACT,
                    )
                    for (envelope in batch) {
                        val deliveredToPeer = client.send(peer.deviceAddress, envelope)
                        if (!deliveredToPeer) break
                        known.add(envelope.packetId)
                    }
                } finally {
                    activePeerJobs.remove(peerKey)
                }
            }
        }
    }

    private fun fingerprint(nodeId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(nodeId.encodeToByteArray())
        .copyOfRange(0, 8)
        .joinToString(separator = "") { "%02x".format(it) }

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
