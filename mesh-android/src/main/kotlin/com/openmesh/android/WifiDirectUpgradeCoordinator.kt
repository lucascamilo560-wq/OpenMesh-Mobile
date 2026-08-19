package com.openmesh.android

import com.openmesh.core.MeshKeyPair
import com.openmesh.core.PacketPriority
import com.openmesh.core.SecureMeshMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Negotiates an optional high-bandwidth Wi-Fi Direct session over OpenMesh BLE.
 *
 * Temporary group credentials are transported inside an end-to-end encrypted
 * envelope, so relay nodes can forward the upgrade offer without learning the
 * Wi-Fi passphrase.
 */
class WifiDirectUpgradeCoordinator(
    context: android.content.Context,
    private val meshNode: BleMeshNode,
    private val localIdentity: MeshKeyPair,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val groupController = WifiDirectGroupController(appContext)
    private val dataChannel = WifiDirectDataChannel(scope)
    private var deliveryJob: Job? = null

    private val _sessions = MutableSharedFlow<WifiDirectSocketSession>(extraBufferCapacity = 8)
    val sessions: SharedFlow<WifiDirectSocketSession> = _sessions.asSharedFlow()

    private val _events = MutableSharedFlow<WifiDirectUpgradeEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<WifiDirectUpgradeEvent> = _events.asSharedFlow()

    init {
        require(localIdentity.nodeId == meshNode.localNodeId) {
            "Upgrade coordinator identity must match the mesh node"
        }
    }

    fun start() {
        if (deliveryJob?.isActive == true) return
        deliveryJob = scope.launch {
            meshNode.deliveries.collect { envelope ->
                if (envelope.contentType != SecureMeshMessage.OUTER_CONTENT_TYPE) return@collect
                val opened = runCatching { SecureMeshMessage.open(envelope, localIdentity) }.getOrNull()
                    ?: return@collect
                if (opened.contentType != OFFER_CONTENT_TYPE) return@collect

                val offer = runCatching { WifiDirectUpgradeOfferCodec.decode(opened.payload) }.getOrNull()
                    ?: return@collect
                if (offer.expiresAtMs <= System.currentTimeMillis()) return@collect

                _events.emit(WifiDirectUpgradeEvent.OfferReceived(opened.senderNodeId, offer.sessionId))
                acceptOffer(opened.senderNodeId, offer)
            }
        }
    }

    suspend fun offer(
        peerNodeId: String,
        offerTtlMs: Long = DEFAULT_OFFER_TTL_MS,
    ): WifiDirectUpgradeResult {
        val peerKey = meshNode.knownPeerPublicKey(peerNodeId)
            ?: return WifiDirectUpgradeResult.PeerKeyUnavailable

        val credentials = WifiDirectCredentials.generate()
        val prepared = groupController.prepareGroup(credentials)
        if (prepared !is WifiDirectPrepareResult.Ready) {
            return WifiDirectUpgradeResult.PrepareFailed(prepared)
        }

        val hostStarted = dataChannel.host(credentials.port) { session ->
            scope.launch {
                _sessions.emit(session)
                _events.emit(WifiDirectUpgradeEvent.SessionOpened(peerNodeId, session.remoteAddress))
            }
        }
        if (!hostStarted) {
            groupController.removeGroup()
            return WifiDirectUpgradeResult.DataChannelFailed
        }

        val now = System.currentTimeMillis()
        val offer = WifiDirectUpgradeOffer(
            sessionId = UUID.randomUUID().toString(),
            credentials = credentials,
            expiresAtMs = now + offerTtlMs,
        )

        val secureEnvelope = runCatching {
            meshNode.sendSecure(
                payload = WifiDirectUpgradeOfferCodec.encode(offer),
                contentType = OFFER_CONTENT_TYPE,
                senderIdentity = localIdentity,
                recipientNodeId = peerNodeId,
                recipientPublicKeyBase64 = peerKey,
                ttlMs = offerTtlMs,
                priority = PacketPriority.HIGH,
            )
        }.getOrElse {
            groupController.removeGroup()
            return WifiDirectUpgradeResult.ControlMessageFailed
        }

        _events.emit(WifiDirectUpgradeEvent.OfferQueued(peerNodeId, offer.sessionId, secureEnvelope.packetId))

        return when (val connected = groupController.awaitConnection(credentials, offerTtlMs)) {
            is WifiDirectGroupResult.Connected -> {
                _events.emit(WifiDirectUpgradeEvent.GroupConnected(peerNodeId, offer.sessionId))
                WifiDirectUpgradeResult.Connected(offer.sessionId, connected)
            }
            else -> {
                groupController.removeGroup()
                WifiDirectUpgradeResult.ConnectionFailed(connected)
            }
        }
    }

    private suspend fun acceptOffer(senderNodeId: String, offer: WifiDirectUpgradeOffer) {
        when (val connected = groupController.joinGroup(offer.credentials, remainingMs(offer))) {
            is WifiDirectGroupResult.Connected -> {
                if (connected.isGroupOwner) {
                    _events.emit(WifiDirectUpgradeEvent.Failed(senderNodeId, "unexpected-group-owner-role"))
                    return
                }
                val session = dataChannel.join(
                    groupOwnerAddress = connected.groupOwnerAddress,
                    port = offer.credentials.port,
                )
                if (session == null) {
                    _events.emit(WifiDirectUpgradeEvent.Failed(senderNodeId, "tcp-connect"))
                    return
                }
                _sessions.emit(session)
                _events.emit(WifiDirectUpgradeEvent.SessionOpened(senderNodeId, session.remoteAddress))
            }
            else -> _events.emit(WifiDirectUpgradeEvent.Failed(senderNodeId, connected.toString()))
        }
    }

    private fun remainingMs(offer: WifiDirectUpgradeOffer): Long =
        (offer.expiresAtMs - System.currentTimeMillis()).coerceIn(1_000L, DEFAULT_OFFER_TTL_MS)

    override fun close() {
        deliveryJob?.cancel()
        deliveryJob = null
        dataChannel.close()
        groupController.removeGroup()
        scope.cancel()
    }

    companion object {
        const val OFFER_CONTENT_TYPE = "application/vnd.openmesh.wifi-direct-offer-v1"
        private const val DEFAULT_OFFER_TTL_MS = 90_000L
    }
}

data class WifiDirectUpgradeOffer(
    val sessionId: String,
    val credentials: WifiDirectCredentials,
    val expiresAtMs: Long,
)

object WifiDirectUpgradeOfferCodec {
    fun encode(offer: WifiDirectUpgradeOffer): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeUTF(offer.sessionId)
            out.writeUTF(offer.credentials.networkName)
            out.writeUTF(offer.credentials.passphrase)
            out.writeInt(offer.credentials.port)
            out.writeLong(offer.expiresAtMs)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): WifiDirectUpgradeOffer =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported Wi-Fi upgrade offer" }
            val sessionId = input.readUTF()
            require(runCatching { UUID.fromString(sessionId) }.isSuccess) { "Invalid session ID" }
            val credentials = WifiDirectCredentials(
                networkName = input.readUTF(),
                passphrase = input.readUTF(),
                port = input.readInt(),
            )
            val expiresAtMs = input.readLong()
            require(expiresAtMs > 0) { "Invalid offer expiration" }
            WifiDirectUpgradeOffer(sessionId, credentials, expiresAtMs)
        }

    private const val VERSION = 1
}

sealed interface WifiDirectUpgradeResult {
    data object PeerKeyUnavailable : WifiDirectUpgradeResult
    data class PrepareFailed(val result: WifiDirectPrepareResult) : WifiDirectUpgradeResult
    data object DataChannelFailed : WifiDirectUpgradeResult
    data object ControlMessageFailed : WifiDirectUpgradeResult
    data class ConnectionFailed(val result: WifiDirectGroupResult) : WifiDirectUpgradeResult
    data class Connected(
        val sessionId: String,
        val group: WifiDirectGroupResult.Connected,
    ) : WifiDirectUpgradeResult
}

sealed interface WifiDirectUpgradeEvent {
    data class OfferQueued(val peerNodeId: String, val sessionId: String, val packetId: String) : WifiDirectUpgradeEvent
    data class OfferReceived(val peerNodeId: String, val sessionId: String) : WifiDirectUpgradeEvent
    data class GroupConnected(val peerNodeId: String, val sessionId: String) : WifiDirectUpgradeEvent
    data class SessionOpened(val peerNodeId: String, val remoteAddress: String) : WifiDirectUpgradeEvent
    data class Failed(val peerNodeId: String, val reason: String) : WifiDirectUpgradeEvent
}
