package com.openmesh.android

import android.content.Context
import com.openmesh.core.MeshKeyPair
import com.openmesh.core.NodeId
import com.openmesh.core.OpaqueBytes
import com.openmesh.core.TransportAdapter
import com.openmesh.core.TransportAdapterId
import com.openmesh.core.TransportAddress
import com.openmesh.core.TransportContractLimits
import com.openmesh.core.TransportDirection
import com.openmesh.core.TransportEvent
import com.openmesh.core.TransportLocalFailure
import com.openmesh.core.TransportLocalFailureCode
import com.openmesh.core.TransportOpportunity
import com.openmesh.core.TransportOpportunityId
import com.openmesh.core.TransportOpportunityKey
import com.openmesh.core.TransportOpportunityReference
import com.openmesh.core.TransportOpportunityRevision
import com.openmesh.core.TransportOpportunityUnavailableReason
import com.openmesh.core.TransportPeer
import com.openmesh.core.TransportTransferRequest
import com.openmesh.core.TransportTransferResult
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * Android BLE implementation of the constitutional transport SPI.
 *
 * This adapter observes short-lived BLE contacts and moves opaque bytes. It
 * deliberately owns no routing, delivery policy, retry, copy budget, or
 * DeliveryStore authority. The production [BleMeshNode] is not connected to
 * this adapter yet.
 */
class BleTransportAdapter internal constructor(
    private val platform: BleTransportPlatform,
    private val clock: BleTransportClock = SystemBleTransportClock,
    private val opportunityIdGenerator: BleOpportunityIdGenerator =
        SecureRandomBleOpportunityIdGenerator(),
    private val contactValidityMs: Long = DEFAULT_CONTACT_VALIDITY_MS,
    private val expirationSweepIntervalMs: Long = DEFAULT_EXPIRATION_SWEEP_INTERVAL_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxActiveOpportunities: Int = DEFAULT_MAX_ACTIVE_OPPORTUNITIES,
    private val maxConcurrentIdentityResolutions: Int =
        DEFAULT_MAX_CONCURRENT_IDENTITY_RESOLUTIONS,
    private val beforeEventPublication: suspend (TransportEvent) -> Unit = {},
    private val beforeTransferSend: suspend () -> Unit = {},
) : TransportAdapter {

    constructor(
        context: Context,
        localNodeId: NodeId,
        localIdentity: MeshKeyPair? = null,
    ) : this(
        platform = AndroidBleTransportPlatform(
            context = context.applicationContext,
            localNodeId = localNodeId.value,
            localIdentity = localIdentity,
        ),
    )

    init {
        require(contactValidityMs > 0) { "BLE contact validity must be positive" }
        require(contactValidityMs <= TransportOpportunity.MAX_VALIDITY_MS) {
            "BLE contact validity exceeds the Transport SPI bound"
        }
        require(expirationSweepIntervalMs > 0) {
            "BLE expiration sweep interval must be positive"
        }
        require(maxActiveOpportunities > 0) {
            "BLE active opportunity limit must be positive"
        }
        require(maxConcurrentIdentityResolutions > 0) {
            "BLE identity concurrency limit must be positive"
        }
        require(maxConcurrentIdentityResolutions <= maxActiveOpportunities) {
            "BLE identity concurrency cannot exceed the active opportunity limit"
        }
    }

    override val adapterId: TransportAdapterId = ADAPTER_ID

    private val mutableEvents = MutableSharedFlow<TransportEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )
    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    /** Serializes complete physical start/stop operations, not only logical state changes. */
    private val lifecycleOperationMutex = Mutex()

    /** Serializes transfer authorization, the actual GATT send, and the stop barrier. */
    private val transferOperationMutex = Mutex()

    /** Serializes state transitions with their lossless, backpressured event publication. */
    private val eventTransitionMutex = Mutex()

    private val lifecycleMutex = Mutex()
    private val currentByAddress = linkedMapOf<String, CurrentBleOpportunity>()
    private val currentByKey = linkedMapOf<TransportOpportunityKey, CurrentBleOpportunity>()
    private val identityResolutionPermits = Semaphore(maxConcurrentIdentityResolutions)

    private var lifecycleState = LifecycleState.STOPPED

    @Volatile
    private var runScope: CoroutineScope? = null

    override suspend fun start() {
        lifecycleOperationMutex.withLock {
            lifecycleMutex.withLock {
                if (lifecycleState == LifecycleState.RUNNING) return

                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                runScope = scope
                var serverStarted = false
                var advertiserStarted = false
                var scannerStarted = false

                try {
                    if (
                        !platform.startServer(
                            onBytes = { deviceAddress, bytes ->
                                onOpaqueInbound(
                                    deviceAddress = deviceAddress,
                                    bytes = bytes,
                                    expectedScope = scope,
                                )
                            },
                        )
                    ) {
                        throw BleTransportStartException(BleTransportStartStage.GATT_SERVER)
                    }
                    serverStarted = true

                    if (!platform.startAdvertising()) {
                        throw BleTransportStartException(BleTransportStartStage.ADVERTISER)
                    }
                    advertiserStarted = true

                    lifecycleState = LifecycleState.RUNNING
                    if (
                        !platform.startScanning(
                            onAdvertisement = { advertisement ->
                                scope.launch {
                                    observeAdvertisement(
                                        advertisement = advertisement,
                                        expectedScope = scope,
                                    )
                                }
                            },
                        )
                    ) {
                        throw BleTransportStartException(BleTransportStartStage.SCANNER)
                    }
                    scannerStarted = true

                    scope.launch { expirationLoop() }
                } catch (error: Throwable) {
                    lifecycleState = LifecycleState.STOPPED
                    runScope = null
                    scope.cancel()
                    if (scannerStarted) runCatching { platform.stopScanning() }
                    if (advertiserStarted) runCatching { platform.stopAdvertising() }
                    if (serverStarted) runCatching { platform.stopServer() }
                    throw if (error is BleTransportStartException) {
                        error
                    } else {
                        BleTransportStartException(
                            stage = when {
                                !serverStarted -> BleTransportStartStage.GATT_SERVER
                                !advertiserStarted -> BleTransportStartStage.ADVERTISER
                                else -> BleTransportStartStage.SCANNER
                            },
                            cause = error,
                        )
                    }
                }
            }
        }
    }

    override suspend fun stop() {
        lifecycleOperationMutex.lock()
        try {
            transferOperationMutex.lock()
            try {
                val stoppedAtMs = nowMs()
                eventTransitionMutex.lock()
                val shutdown = try {
                    val value = lifecycleMutex.withLock {
                        if (lifecycleState == LifecycleState.STOPPED) return
                        lifecycleState = LifecycleState.STOPPED
                        val scope = runScope
                        runScope = null

                        val unavailableEvents = currentByAddress.values.map { current ->
                            TransportEvent.OpportunityUnavailable(
                                opportunity = current.opportunity.reference,
                                occurredAtMs = stoppedAtMs,
                                reason = TransportOpportunityUnavailableReason.ADAPTER_STOPPED,
                            )
                        }
                        currentByAddress.clear()
                        currentByKey.clear()
                        Shutdown(scope = scope, unavailableEvents = unavailableEvents)
                    }
                    publishEvents(value.unavailableEvents)
                    value
                } finally {
                    eventTransitionMutex.unlock()
                }

                shutdown.scope?.coroutineContext?.get(Job)?.cancelAndJoin()
                runCatching { platform.stopScanning() }
                runCatching { platform.stopAdvertising() }
                runCatching { platform.stopServer() }
            } finally {
                transferOperationMutex.unlock()
            }
        } finally {
            lifecycleOperationMutex.unlock()
        }
    }

    override suspend fun transfer(request: TransportTransferRequest): TransportTransferResult {
        transferOperationMutex.lock()
        try {
            val now = nowMs()
            var expirationEvent: TransportEvent.OpportunityUnavailable? = null

            eventTransitionMutex.lock()
            val authorization = try {
                val value = lifecycleMutex.withLock {
                    when {
                        request.opportunity.adapterId != adapterId ->
                            TransferAuthorization.Rejected(
                                TransportTransferResult.FailedLocally(
                                    transferId = request.transferId,
                                    occurredAtMs = now,
                                    failure = TransportLocalFailure(
                                        code = TransportLocalFailureCode.INVALID_REQUEST,
                                        detail = "Transfer opportunity belongs to another adapter",
                                    ),
                                ),
                            )

                        lifecycleState != LifecycleState.RUNNING ->
                            TransferAuthorization.Rejected(
                                TransportTransferResult.FailedLocally(
                                    transferId = request.transferId,
                                    occurredAtMs = now,
                                    failure = TransportLocalFailure(
                                        code = TransportLocalFailureCode.ADAPTER_STOPPED,
                                    ),
                                ),
                            )

                        else -> {
                            val current = currentByKey[request.opportunity.key]
                            when {
                                current == null ||
                                    current.opportunity.reference != request.opportunity ->
                                    TransferAuthorization.Rejected(
                                        unavailable(request, now),
                                    )

                                !current.opportunity.isFreshAt(now) -> {
                                    expirationEvent = terminalizeLocked(
                                        current = current,
                                        occurredAtMs = now,
                                        reason = TransportOpportunityUnavailableReason.EXPIRED,
                                    )
                                    TransferAuthorization.Rejected(unavailable(request, now))
                                }

                                !current.opportunity.direction.isSendKnown ->
                                    TransferAuthorization.Rejected(
                                        TransportTransferResult.FailedLocally(
                                            transferId = request.transferId,
                                            occurredAtMs = now,
                                            failure = TransportLocalFailure(
                                                code = TransportLocalFailureCode.UNSUPPORTED,
                                                detail = "Opportunity is not explicitly send-capable",
                                            ),
                                        ),
                                    )

                                else -> TransferAuthorization.Approved(
                                    deviceAddress = current.deviceAddress,
                                    bytes = request.bytes,
                                )
                            }
                        }
                    }
                }
                expirationEvent?.let { publishEvent(it) }
                value
            } finally {
                eventTransitionMutex.unlock()
            }

            if (authorization is TransferAuthorization.Rejected) return authorization.result
            authorization as TransferAuthorization.Approved

            beforeTransferSend()
            val result = try {
                platform.sendBytes(
                    deviceAddress = authorization.deviceAddress,
                    bytes = authorization.bytes.copyToByteArray(),
                )
            } catch (_: SecurityException) {
                return failedLocally(
                    request = request,
                    code = TransportLocalFailureCode.PERMISSION_DENIED,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return failedLocally(
                    request = request,
                    code = TransportLocalFailureCode.IO_ERROR,
                )
            }

            return when (result) {
                is BleGattSendResult.Success -> TransportTransferResult.CompletedLocally(
                    transferId = request.transferId,
                    occurredAtMs = nowMs(),
                )

                is BleGattSendResult.Failed -> failedLocally(
                    request = request,
                    code = result.kind.toTransportFailureCode(),
                    detail = buildGattFailureDetail(result),
                )
            }
        } finally {
            transferOperationMutex.unlock()
        }
    }

    /** Deterministic seam used by tests and by the production expiration loop. */
    internal suspend fun expireStaleOpportunities() {
        eventTransitionMutex.lock()
        try {
            val now = nowMs()
            val expired = lifecycleMutex.withLock {
                if (lifecycleState != LifecycleState.RUNNING) return@withLock emptyList()
                currentByAddress.values
                    .filter { !it.opportunity.isFreshAt(now) }
                    .toList()
                    .map { current ->
                        terminalizeLocked(
                            current = current,
                            occurredAtMs = now,
                            reason = TransportOpportunityUnavailableReason.EXPIRED,
                        )
                    }
            }
            publishEvents(expired)
        } finally {
            eventTransitionMutex.unlock()
        }
    }

    /** Deterministic scan seam; the Android scanner dispatches through this path. */
    internal suspend fun observeAdvertisement(
        advertisement: PeerAdvertisement,
        expectedScope: CoroutineScope? = null,
    ) {
        if (advertisement.seenAtMs < 0 || advertisement.deviceAddress.isBlank()) return
        val addressBytes = advertisement.deviceAddress.encodeToByteArray()
        if (addressBytes.size > TransportAddress.MAX_BYTES) return

        val emitted = mutableListOf<TransportEvent>()
        var identityRequest: IdentityResolutionRequest? = null
        eventTransitionMutex.lock()
        try {
            lifecycleMutex.withLock {
                if (lifecycleState != LifecycleState.RUNNING) return@withLock
                if (expectedScope != null && runScope !== expectedScope) return@withLock

                val existing = currentByAddress[advertisement.deviceAddress]
                val current = when {
                    existing == null -> {
                        createOpportunityLocked(advertisement)?.also { created ->
                            emitted += TransportEvent.OpportunityAvailable(created.opportunity)
                        }
                    }

                    advertisement.seenAtMs < existing.opportunity.observedAtMs -> existing

                    advertisement.seenAtMs >= existing.opportunity.validUntilMs -> {
                        emitted += terminalizeLocked(
                            current = existing,
                            occurredAtMs = advertisement.seenAtMs,
                            reason = TransportOpportunityUnavailableReason.EXPIRED,
                        )
                        createOpportunityLocked(advertisement)?.also { created ->
                            emitted += TransportEvent.OpportunityAvailable(created.opportunity)
                        }
                    }

                    existing.opportunity.revision.value == Long.MAX_VALUE -> {
                        emitted += terminalizeLocked(
                            current = existing,
                            occurredAtMs = advertisement.seenAtMs,
                            reason = TransportOpportunityUnavailableReason.REPLACED,
                        )
                        createOpportunityLocked(advertisement)?.also { created ->
                            emitted += TransportEvent.OpportunityAvailable(created.opportunity)
                        }
                    }

                    else -> {
                        val previous = existing.opportunity.reference
                        existing.opportunity = existing.opportunity.copy(
                            revision = TransportOpportunityRevision(
                                existing.opportunity.revision.value + 1,
                            ),
                            observedAtMs = advertisement.seenAtMs,
                            validUntilMs = validUntil(advertisement.seenAtMs),
                        )
                        emitted += TransportEvent.OpportunityChanged(
                            previous = previous,
                            opportunity = existing.opportunity,
                        )
                        existing
                    }
                }

                val activeScope = runScope
                if (
                    current != null &&
                    activeScope != null &&
                    current.opportunity.peer == TransportPeer.Unknown &&
                    !current.identityResolutionActive &&
                    identityResolutionPermits.tryAcquire()
                ) {
                    current.identityResolutionActive = true
                    identityRequest = IdentityResolutionRequest(
                        scope = activeScope,
                        deviceAddress = current.deviceAddress,
                        opportunityId = current.opportunity.opportunityId,
                    )
                }
            }
            publishEvents(emitted)
        } finally {
            eventTransitionMutex.unlock()
        }

        identityRequest?.let { request ->
            val job = request.scope.launch {
                resolveIdentity(
                    deviceAddress = request.deviceAddress,
                    opportunityId = request.opportunityId,
                    expectedScope = request.scope,
                )
            }
            job.invokeOnCompletion { identityResolutionPermits.release() }
        }
    }

    private suspend fun resolveIdentity(
        deviceAddress: String,
        opportunityId: TransportOpportunityId,
        expectedScope: CoroutineScope,
    ) {
        val identity = try {
            platform.resolveIdentity(deviceAddress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val now = nowMs()
        var event: TransportEvent? = null

        eventTransitionMutex.lock()
        try {
            lifecycleMutex.withLock {
                val current = currentByAddress[deviceAddress]
                if (current == null || current.opportunity.opportunityId != opportunityId) {
                    return@withLock
                }
                current.identityResolutionActive = false
                if (
                    lifecycleState != LifecycleState.RUNNING ||
                    runScope !== expectedScope ||
                    identity == null
                ) {
                    return@withLock
                }

                if (!current.opportunity.isFreshAt(now)) {
                    event = terminalizeLocked(
                        current = current,
                        occurredAtMs = now,
                        reason = TransportOpportunityUnavailableReason.EXPIRED,
                    )
                    return@withLock
                }

                val knownNode = runCatching { TransportPeer.KnownNode(NodeId(identity.nodeId)) }
                    .getOrNull() ?: return@withLock
                if (current.opportunity.peer == knownNode) return@withLock
                if (current.opportunity.revision.value == Long.MAX_VALUE) {
                    event = terminalizeLocked(
                        current = current,
                        occurredAtMs = now,
                        reason = TransportOpportunityUnavailableReason.REPLACED,
                    )
                    return@withLock
                }

                val previous = current.opportunity.reference
                val resolvedAtMs = maxOf(now, current.opportunity.observedAtMs)
                current.opportunity = current.opportunity.copy(
                    revision = TransportOpportunityRevision(current.opportunity.revision.value + 1),
                    observedAtMs = resolvedAtMs,
                    peer = knownNode,
                )
                event = TransportEvent.OpportunityChanged(
                    previous = previous,
                    opportunity = current.opportunity,
                )
            }
            event?.let { publishEvent(it) }
        } finally {
            eventTransitionMutex.unlock()
        }
    }

    private suspend fun onOpaqueInbound(
        deviceAddress: String,
        bytes: ByteArray,
        expectedScope: CoroutineScope? = null,
    ) {
        if (deviceAddress.isBlank() || bytes.isEmpty()) return
        if (bytes.size > TransportContractLimits.MAX_OPAQUE_BYTES) return
        val addressBytes = deviceAddress.encodeToByteArray()
        if (addressBytes.size > TransportAddress.MAX_BYTES) return

        val now = nowMs()
        var expired: TransportEvent.OpportunityUnavailable? = null
        eventTransitionMutex.lock()
        try {
            val inbound = lifecycleMutex.withLock {
                if (lifecycleState != LifecycleState.RUNNING) return@withLock null
                if (expectedScope != null && runScope !== expectedScope) return@withLock null
                var current = currentByAddress[deviceAddress]
                if (current != null && !current.opportunity.isFreshAt(now)) {
                    expired = terminalizeLocked(
                        current = current,
                        occurredAtMs = now,
                        reason = TransportOpportunityUnavailableReason.EXPIRED,
                    )
                    current = null
                }

                TransportEvent.InboundBytes.copyOf(
                    adapterId = adapterId,
                    opportunity = current?.opportunity?.reference,
                    peer = current?.opportunity?.peer ?: TransportPeer.Unknown,
                    sourceAddress = TransportAddress.copyOf(adapterId, addressBytes),
                    occurredAtMs = now,
                    bytes = bytes,
                )
            }
            expired?.let { publishEvent(it) }
            inbound?.let { publishEvent(it) }
        } finally {
            eventTransitionMutex.unlock()
        }
    }

    private fun createOpportunityLocked(
        advertisement: PeerAdvertisement,
    ): CurrentBleOpportunity? {
        if (currentByAddress.size >= maxActiveOpportunities) return null
        val opportunityId = nextOpportunityIdLocked()
        val opportunity = TransportOpportunity(
            adapterId = adapterId,
            opportunityId = opportunityId,
            revision = TransportOpportunityRevision(1),
            observedAtMs = advertisement.seenAtMs,
            validUntilMs = validUntil(advertisement.seenAtMs),
            direction = TransportDirection.SEND_ONLY,
            peer = TransportPeer.Unknown,
            address = TransportAddress.copyOf(
                adapterId,
                advertisement.deviceAddress.encodeToByteArray(),
            ),
        )
        return CurrentBleOpportunity(
            deviceAddress = advertisement.deviceAddress,
            opportunity = opportunity,
        ).also { current ->
            currentByAddress[current.deviceAddress] = current
            currentByKey[opportunity.key] = current
        }
    }

    private fun terminalizeLocked(
        current: CurrentBleOpportunity,
        occurredAtMs: Long,
        reason: TransportOpportunityUnavailableReason,
    ): TransportEvent.OpportunityUnavailable {
        currentByAddress.remove(current.deviceAddress, current)
        currentByKey.remove(current.opportunity.key, current)
        return TransportEvent.OpportunityUnavailable(
            opportunity = current.opportunity.reference,
            occurredAtMs = occurredAtMs,
            reason = reason,
        )
    }

    private fun nextOpportunityIdLocked(): TransportOpportunityId {
        val candidate = opportunityIdGenerator.nextId()
        check(currentByKey.keys.none { it.opportunityId == candidate }) {
            "BLE opportunity ID generator reused an active lifecycle ID"
        }
        return candidate
    }

    private fun validUntil(observedAtMs: Long): Long {
        require(observedAtMs >= 0) { "BLE observation time must not be negative" }
        require(observedAtMs <= Long.MAX_VALUE - contactValidityMs) {
            "BLE observation validity overflows time range"
        }
        return observedAtMs + contactValidityMs
    }

    private suspend fun expirationLoop() {
        while (runScope?.isActive == true) {
            delay(expirationSweepIntervalMs)
            expireStaleOpportunities()
        }
    }

    /** Called only while [eventTransitionMutex] is held. */
    private suspend fun publishEvent(event: TransportEvent) {
        beforeEventPublication(event)
        mutableEvents.emit(event)
    }

    /** Called only while [eventTransitionMutex] is held. */
    private suspend fun publishEvents(events: Iterable<TransportEvent>) {
        events.forEach { publishEvent(it) }
    }

    private fun unavailable(
        request: TransportTransferRequest,
        occurredAtMs: Long,
    ): TransportTransferResult.OpportunityUnavailable =
        TransportTransferResult.OpportunityUnavailable(
            transferId = request.transferId,
            occurredAtMs = occurredAtMs,
            opportunity = request.opportunity,
        )

    private fun failedLocally(
        request: TransportTransferRequest,
        code: TransportLocalFailureCode,
        detail: String? = null,
    ): TransportTransferResult.FailedLocally = TransportTransferResult.FailedLocally(
        transferId = request.transferId,
        occurredAtMs = nowMs(),
        failure = TransportLocalFailure(code = code, detail = detail),
    )

    private fun nowMs(): Long = clock.nowMs().coerceAtLeast(0)

    private data class CurrentBleOpportunity(
        val deviceAddress: String,
        var opportunity: TransportOpportunity,
        var identityResolutionActive: Boolean = false,
    )

    private data class IdentityResolutionRequest(
        val scope: CoroutineScope,
        val deviceAddress: String,
        val opportunityId: TransportOpportunityId,
    )

    private sealed interface TransferAuthorization {
        data class Approved(
            val deviceAddress: String,
            val bytes: OpaqueBytes,
        ) : TransferAuthorization

        data class Rejected(
            val result: TransportTransferResult,
        ) : TransferAuthorization
    }

    private data class Shutdown(
        val scope: CoroutineScope?,
        val unavailableEvents: List<TransportEvent.OpportunityUnavailable>,
    )

    private enum class LifecycleState {
        STOPPED,
        RUNNING,
    }

    companion object {
        val ADAPTER_ID = TransportAdapterId("android-ble-gatt-v1")
        const val DEFAULT_CONTACT_VALIDITY_MS = 15_000L
        const val DEFAULT_EXPIRATION_SWEEP_INTERVAL_MS = 1_000L
        const val DEFAULT_MAX_ACTIVE_OPPORTUNITIES = 128
        const val DEFAULT_MAX_CONCURRENT_IDENTITY_RESOLUTIONS = 8
        private const val EVENT_BUFFER_CAPACITY = 256
    }
}

internal interface BleTransportPlatform {
    fun startServer(onBytes: suspend (deviceAddress: String, bytes: ByteArray) -> Unit): Boolean

    fun startAdvertising(): Boolean

    fun startScanning(onAdvertisement: (PeerAdvertisement) -> Unit): Boolean

    fun stopScanning()

    fun stopAdvertising()

    fun stopServer()

    suspend fun resolveIdentity(deviceAddress: String): ResolvedPeerIdentity?

    suspend fun sendBytes(deviceAddress: String, bytes: ByteArray): BleGattSendResult
}

internal class AndroidBleTransportPlatform(
    context: Context,
    private val localNodeId: String,
    private val localIdentity: MeshKeyPair?,
) : BleTransportPlatform {
    private val appContext = context.applicationContext
    private val advertiser = BleMeshAdvertiser(appContext)
    private val scanner = BleMeshScanner(appContext)
    private val identityClient = BlePeerIdentityClient(appContext)
    private val gattClient = BleMeshGattClient(appContext)
    private var rawServer: BleMeshGattServer? = null

    override fun startServer(
        onBytes: suspend (deviceAddress: String, bytes: ByteArray) -> Unit,
    ): Boolean {
        if (rawServer != null) return true
        val server = BleMeshGattServer.forOpaqueBytes(
            context = appContext,
            localNodeId = localNodeId,
            localIdentity = localIdentity,
            onBytes = onBytes,
        )
        if (!server.start()) {
            server.close()
            return false
        }
        rawServer = server
        return true
    }

    override fun startAdvertising(): Boolean = advertiser.start(localNodeId)

    override fun startScanning(onAdvertisement: (PeerAdvertisement) -> Unit): Boolean =
        scanner.start(onAdvertisement)

    override fun stopScanning() = scanner.stop()

    override fun stopAdvertising() = advertiser.stop()

    override fun stopServer() {
        rawServer?.close()
        rawServer = null
    }

    override suspend fun resolveIdentity(deviceAddress: String): ResolvedPeerIdentity? =
        identityClient.resolve(deviceAddress)

    override suspend fun sendBytes(deviceAddress: String, bytes: ByteArray): BleGattSendResult =
        gattClient.sendBytes(deviceAddress, bytes)
}

internal fun interface BleTransportClock {
    fun nowMs(): Long
}

private object SystemBleTransportClock : BleTransportClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

internal fun interface BleOpportunityIdGenerator {
    fun nextId(): TransportOpportunityId
}

internal class SecureRandomBleOpportunityIdGenerator(
    private val random: SecureRandom = SecureRandom(),
    initialCounter: Long = 1,
) : BleOpportunityIdGenerator {
    private val lock = Any()
    private val instanceNonce = ByteArray(ENTROPY_BYTES).also(random::nextBytes)
    private var nextCounter = initialCounter

    init {
        require(initialCounter > 0) { "BLE opportunity counter must be positive" }
    }

    override fun nextId(): TransportOpportunityId = synchronized(lock) {
        check(nextCounter > 0) { "BLE opportunity ID counter exhausted" }
        val counter = nextCounter
        nextCounter = if (counter == Long.MAX_VALUE) 0 else counter + 1

        val nonceHex = CharArray(instanceNonce.size * 2)
        instanceNonce.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            nonceHex[index * 2] = HEX[value ushr 4]
            nonceHex[index * 2 + 1] = HEX[value and 0x0f]
        }
        val counterHex = counter.toString(radix = 16).padStart(COUNTER_HEX_CHARS, '0')
        TransportOpportunityId("ble-${nonceHex.concatToString()}-$counterHex")
    }

    private companion object {
        const val ENTROPY_BYTES = 32
        const val COUNTER_HEX_CHARS = 16
        val HEX = "0123456789abcdef".toCharArray()
    }
}

enum class BleTransportStartStage {
    GATT_SERVER,
    ADVERTISER,
    SCANNER,
}

class BleTransportStartException(
    val stage: BleTransportStartStage,
    cause: Throwable? = null,
) : IllegalStateException("BLE transport failed to start at $stage", cause)

private fun BleGattFailureKind.toTransportFailureCode(): TransportLocalFailureCode = when (this) {
    BleGattFailureKind.PERMISSION_DENIED -> TransportLocalFailureCode.PERMISSION_DENIED
    BleGattFailureKind.TIMED_OUT -> TransportLocalFailureCode.TIMED_OUT
    BleGattFailureKind.IO_ERROR -> TransportLocalFailureCode.IO_ERROR
    BleGattFailureKind.RESOURCE_LIMIT -> TransportLocalFailureCode.RESOURCE_LIMIT
    BleGattFailureKind.UNSUPPORTED -> TransportLocalFailureCode.UNSUPPORTED
    BleGattFailureKind.INVALID_REQUEST -> TransportLocalFailureCode.INVALID_REQUEST
    BleGattFailureKind.UNKNOWN -> TransportLocalFailureCode.UNKNOWN
}

private fun buildGattFailureDetail(result: BleGattSendResult.Failed): String {
    val status = result.status?.let { ", status=$it" }.orEmpty()
    val frame = result.frameIndex?.let { ", frame=$it/${result.frameCount ?: "?"}" }.orEmpty()
    return "BLE GATT ${result.stage}$status$frame"
}
