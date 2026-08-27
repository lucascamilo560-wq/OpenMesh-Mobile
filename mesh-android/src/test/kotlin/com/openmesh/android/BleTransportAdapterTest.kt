package com.openmesh.android

import com.openmesh.core.DeliveryStore
import com.openmesh.core.MeshNodeId
import com.openmesh.core.TransportDirection
import com.openmesh.core.TransportEvent
import com.openmesh.core.TransportLocalFailureCode
import com.openmesh.core.TransportOpportunityId
import com.openmesh.core.TransportOpportunityReference
import com.openmesh.core.TransportOpportunityRevision
import com.openmesh.core.TransportOpportunityUnavailableReason
import com.openmesh.core.TransportPeer
import com.openmesh.core.TransportTransferId
import com.openmesh.core.TransportTransferRequest
import com.openmesh.core.TransportTransferResult
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTransportAdapterTest {

    @Test
    fun `startup and stop are idempotent and partial startup rolls back in reverse order`() =
        runBlocking {
            val platform = FakeBleTransportPlatform()
            val adapter = adapter(platform)

            adapter.start()
            adapter.start()
            assertEquals(
                listOf("server:start", "advertiser:start", "scanner:start"),
                platform.calls,
            )

            adapter.stop()
            adapter.stop()
            assertEquals(
                listOf(
                    "server:start",
                    "advertiser:start",
                    "scanner:start",
                    "scanner:stop",
                    "advertiser:stop",
                    "server:stop",
                ),
                platform.calls,
            )

            val advertiserFailure = FakeBleTransportPlatform(advertiserStartSucceeds = false)
            val failedAdvertiserAdapter = adapter(advertiserFailure)
            val advertiserError = expectSuspendThrows<BleTransportStartException> {
                failedAdvertiserAdapter.start()
            }
            assertEquals(BleTransportStartStage.ADVERTISER, advertiserError.stage)
            assertEquals(
                listOf("server:start", "advertiser:start", "server:stop"),
                advertiserFailure.calls,
            )

            val scannerFailure = FakeBleTransportPlatform(scannerStartSucceeds = false)
            val failedScannerAdapter = adapter(scannerFailure)
            val scannerError = expectSuspendThrows<BleTransportStartException> {
                failedScannerAdapter.start()
            }
            assertEquals(BleTransportStartStage.SCANNER, scannerError.stage)
            assertEquals(
                listOf(
                    "server:start",
                    "advertiser:start",
                    "scanner:start",
                    "advertiser:stop",
                    "server:stop",
                ),
                scannerFailure.calls,
            )
        }

    @Test
    fun `scan refresh expiry reencounter stop and restart preserve terminal lifecycle IDs`() =
        runBlocking {
            val clock = FakeBleTransportClock(100)
            val platform = FakeBleTransportPlatform()
            val adapter = adapter(
                platform = platform,
                clock = clock,
                ids = listOf("lifecycle-x", "lifecycle-y", "lifecycle-z"),
            )
            val events = mutableListOf<TransportEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                adapter.events.collect(events::add)
            }

            adapter.start()
            observe(adapter, ADDRESS_A, seenAtMs = 100)
            awaitEvents(events, 1)

            val availableX = events.single() as TransportEvent.OpportunityAvailable
            assertEquals(TransportDirection.SEND_ONLY, availableX.opportunity.direction)
            assertEquals(TransportPeer.Unknown, availableX.opportunity.peer)
            assertEquals(TransportOpportunityRevision(1), availableX.opportunity.revision)
            assertEquals(200L, availableX.opportunity.validUntilMs)
            assertArrayEquals(
                ADDRESS_A.encodeToByteArray(),
                availableX.opportunity.address?.copyToByteArray(),
            )

            observe(adapter, ADDRESS_A, seenAtMs = 120)
            awaitEvents(events, 2)
            val refreshed = events[1] as TransportEvent.OpportunityChanged
            assertEquals(availableX.opportunity.reference, refreshed.previous)
            assertEquals(TransportOpportunityRevision(2), refreshed.opportunity.revision)
            assertEquals(220L, refreshed.opportunity.validUntilMs)

            clock.now = 220
            adapter.expireStaleOpportunities()
            adapter.expireStaleOpportunities()
            awaitEvents(events, 3)
            val expiryEvents = events.filterIsInstance<TransportEvent.OpportunityUnavailable>()
            assertEquals(1, expiryEvents.size)
            assertEquals(TransportOpportunityUnavailableReason.EXPIRED, expiryEvents.single().reason)
            assertEquals(refreshed.opportunity.reference, expiryEvents.single().opportunity)

            observe(adapter, ADDRESS_A, seenAtMs = 230)
            awaitEvents(events, 4)
            val availableY = events.filterIsInstance<TransportEvent.OpportunityAvailable>().last()
            assertNotEquals(
                availableX.opportunity.opportunityId,
                availableY.opportunity.opportunityId,
            )
            assertEquals(TransportOpportunityRevision(1), availableY.opportunity.revision)

            clock.now = 240
            adapter.stop()
            awaitEvents(events, 5)
            val stopped = events.filterIsInstance<TransportEvent.OpportunityUnavailable>().last()
            assertEquals(TransportOpportunityUnavailableReason.ADAPTER_STOPPED, stopped.reason)
            assertEquals(availableY.opportunity.reference, stopped.opportunity)

            adapter.start()
            observe(adapter, ADDRESS_A, seenAtMs = 250)
            awaitEvents(events, 6)
            val availableZ = events.filterIsInstance<TransportEvent.OpportunityAvailable>().last()
            assertNotEquals(
                availableY.opportunity.opportunityId,
                availableZ.opportunity.opportunityId,
            )

            adapter.stop()
            collector.cancelAndJoin()
        }

    @Test
    fun `current identity refines opportunity and stale identity cannot mutate a new lifecycle`() =
        runBlocking {
            val oldIdentity = CompletableDeferred<ResolvedPeerIdentity?>()
            val newIdentity = CompletableDeferred<ResolvedPeerIdentity?>()
            val identityQueue = ArrayDeque<CompletableDeferred<ResolvedPeerIdentity?>>().apply {
                add(oldIdentity)
                add(newIdentity)
            }
            val platform = FakeBleTransportPlatform().apply {
                identityBehavior = { identityQueue.removeFirst().await() }
            }
            val clock = FakeBleTransportClock(100)
            val adapter = adapter(
                platform = platform,
                clock = clock,
                ids = listOf("identity-x", "identity-y"),
            )
            val events = mutableListOf<TransportEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                adapter.events.collect(events::add)
            }

            adapter.start()
            observe(adapter, ADDRESS_A, 100)
            awaitEvents(events, 1)
            val x = (events.single() as TransportEvent.OpportunityAvailable).opportunity

            clock.now = 200
            adapter.expireStaleOpportunities()
            observe(adapter, ADDRESS_A, 210)
            awaitEvents(events, 3)
            val y = events.filterIsInstance<TransportEvent.OpportunityAvailable>().last().opportunity
            assertNotEquals(x.opportunityId, y.opportunityId)

            oldIdentity.complete(resolvedIdentity(seed = 1))
            yield()
            assertEquals(0, events.filterIsInstance<TransportEvent.OpportunityChanged>().size)

            clock.now = 220
            val expectedIdentity = resolvedIdentity(seed = 2)
            newIdentity.complete(expectedIdentity)
            awaitEvent(events) { it is TransportEvent.OpportunityChanged }

            val identified = events.filterIsInstance<TransportEvent.OpportunityChanged>().single()
            assertEquals(y.reference, identified.previous)
            assertEquals(y.opportunityId, identified.opportunity.opportunityId)
            assertEquals(TransportOpportunityRevision(2), identified.opportunity.revision)
            val knownPeer = identified.opportunity.peer as TransportPeer.KnownNode
            assertEquals(expectedIdentity.nodeId, knownPeer.nodeId.value)

            adapter.stop()
            collector.cancelAndJoin()
        }

    @Test
    fun `exact current request moves arbitrary bytes and stale or failed work stays local`() =
        runBlocking {
            val platform = FakeBleTransportPlatform()
            val clock = FakeBleTransportClock(100)
            val adapter = adapter(
                platform = platform,
                clock = clock,
                ids = listOf("transfer-x", "transfer-b", "transfer-y"),
            )
            val events = mutableListOf<TransportEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                adapter.events.collect(events::add)
            }
            adapter.start()
            observe(adapter, ADDRESS_A, 100)
            awaitEvents(events, 1)
            val revisionOne =
                (events.single() as TransportEvent.OpportunityAvailable).opportunity.reference
            val arbitraryBytes = byteArrayOf(0x00, 0x7f, 0x55, 0x01, 0x02)

            val completed = adapter.transfer(request(revisionOne, arbitraryBytes, "first"))
            assertTrue(completed is TransportTransferResult.CompletedLocally)
            assertEquals(1, platform.sent.size)
            assertEquals(ADDRESS_A, platform.sent.single().first)
            assertArrayEquals(arbitraryBytes, platform.sent.single().second)

            observe(adapter, ADDRESS_A, 120)
            awaitEvents(events, 2)
            val revisionTwo =
                events.filterIsInstance<TransportEvent.OpportunityChanged>().last().opportunity.reference
            val stale = adapter.transfer(request(revisionOne, arbitraryBytes, "stale"))
            assertTrue(stale is TransportTransferResult.OpportunityUnavailable)
            assertEquals(1, platform.sent.size)

            val sendStarted = CompletableDeferred<Unit>()
            val releaseSend = CompletableDeferred<Unit>()
            platform.sendBehavior = { _, _ ->
                sendStarted.complete(Unit)
                releaseSend.await()
                BleGattSendResult.Success(frameCount = 1)
            }
            val inFlight = async(start = CoroutineStart.UNDISPATCHED) {
                adapter.transfer(request(revisionTwo, arbitraryBytes, "in-flight"))
            }
            sendStarted.await()

            // A second contact becomes current while the write is blocked. The
            // already authorized transfer must remain pinned to address A.
            observe(adapter, ADDRESS_B, 130)

            clock.now = 220
            adapter.expireStaleOpportunities()
            observe(adapter, ADDRESS_A, 230)
            awaitEvents(events, 5)
            releaseSend.complete(Unit)
            assertTrue(inFlight.await() is TransportTransferResult.CompletedLocally)
            assertEquals(listOf(ADDRESS_A, ADDRESS_A), platform.sent.map { it.first })

            val current = events.filterIsInstance<TransportEvent.OpportunityAvailable>().last()
                .opportunity.reference
            platform.sendBehavior = { _, _ ->
                BleGattSendResult.Failed(
                    stage = BleGattStage.WRITE_FRAME,
                    kind = BleGattFailureKind.TIMED_OUT,
                )
            }
            clock.now = 240
            val failed = adapter.transfer(request(current, arbitraryBytes, "timeout"))
                as TransportTransferResult.FailedLocally
            assertEquals(TransportLocalFailureCode.TIMED_OUT, failed.failure.code)

            assertFalse(
                BleTransportAdapter::class.java.declaredFields.any { field ->
                    DeliveryStore::class.java.isAssignableFrom(field.type)
                },
            )

            adapter.stop()
            val afterStop = adapter.transfer(request(current, arbitraryBytes, "stopped"))
                as TransportTransferResult.FailedLocally
            assertEquals(TransportLocalFailureCode.ADAPTER_STOPPED, afterStop.failure.code)
            collector.cancelAndJoin()
        }

    @Test
    fun `opaque inbound is byte exact with known or unknown opportunity and never decodes envelope`() =
        runBlocking {
            val knownIdentity = resolvedIdentity(seed = 4)
            val platform = FakeBleTransportPlatform().apply {
                identityBehavior = { knownIdentity }
            }
            val clock = FakeBleTransportClock(100)
            val adapter = adapter(platform = platform, clock = clock)
            val events = mutableListOf<TransportEvent>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                adapter.events.collect(events::add)
            }

            adapter.start()
            val invalidEnvelopeBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            platform.emitInbound(ADDRESS_UNKNOWN, invalidEnvelopeBytes)
            awaitEvents(events, 1)

            val unknownInbound = events.single() as TransportEvent.InboundBytes
            assertNull(unknownInbound.opportunity)
            assertEquals(TransportPeer.Unknown, unknownInbound.peer)
            assertArrayEquals(
                ADDRESS_UNKNOWN.encodeToByteArray(),
                unknownInbound.sourceAddress?.copyToByteArray(),
            )
            assertArrayEquals(invalidEnvelopeBytes, unknownInbound.bytes.copyToByteArray())

            observe(adapter, ADDRESS_A, 110)
            awaitEvent(events) { it is TransportEvent.OpportunityChanged }
            val knownOpportunity = events.filterIsInstance<TransportEvent.OpportunityChanged>()
                .last().opportunity
            val inboundBytes = byteArrayOf(0x7f, 0x00, 0x55)
            clock.now = 120
            platform.emitInbound(ADDRESS_A, inboundBytes)
            awaitEventCount<TransportEvent.InboundBytes>(events, 2)

            val knownInbound = events.filterIsInstance<TransportEvent.InboundBytes>().last()
            assertEquals(knownOpportunity.reference, knownInbound.opportunity)
            assertEquals(knownOpportunity.peer, knownInbound.peer)
            assertArrayEquals(inboundBytes, knownInbound.bytes.copyToByteArray())

            adapter.stop()
            collector.cancelAndJoin()
        }

    @Test
    fun `default lifecycle generator uses 256 bits and does not encode BLE or node identity`() {
        val generator = SecureRandomBleOpportunityIdGenerator()
        val ids = List(64) { generator.nextId().value }

        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("ble-[0-9a-f]{64}")) })
        assertTrue(ids.none { it.contains(ADDRESS_A, ignoreCase = true) })
        assertTrue(ids.none { it.contains("om1-", ignoreCase = true) })
    }

    private fun adapter(
        platform: FakeBleTransportPlatform,
        clock: FakeBleTransportClock = FakeBleTransportClock(100),
        ids: List<String> = listOf("default-x", "default-y", "default-z"),
    ): BleTransportAdapter = BleTransportAdapter(
        platform = platform,
        clock = clock,
        opportunityIdGenerator = SequenceOpportunityIdGenerator(ids),
        contactValidityMs = 100,
        expirationSweepIntervalMs = Long.MAX_VALUE,
        dispatcher = Dispatchers.Unconfined,
    )

    private fun request(
        opportunity: TransportOpportunityReference,
        bytes: ByteArray,
        id: String,
    ): TransportTransferRequest = TransportTransferRequest.copyOf(
        transferId = TransportTransferId(id),
        opportunity = opportunity,
        bytes = bytes,
    )

    private suspend fun observe(
        adapter: BleTransportAdapter,
        deviceAddress: String,
        seenAtMs: Long,
    ) {
        adapter.observeAdvertisement(
            PeerAdvertisement(
                deviceAddress = deviceAddress,
                rssi = -55,
                seenAtMs = seenAtMs,
            ),
        )
    }

    private suspend fun awaitEvents(events: List<TransportEvent>, expectedSize: Int) {
        withTimeout(1_000) {
            while (events.size < expectedSize) delay(1)
        }
    }

    private suspend fun awaitEvent(
        events: List<TransportEvent>,
        predicate: (TransportEvent) -> Boolean,
    ) {
        withTimeout(1_000) {
            while (events.none(predicate)) delay(1)
        }
    }

    private suspend inline fun <reified T : TransportEvent> awaitEventCount(
        events: List<TransportEvent>,
        expectedCount: Int,
    ) {
        withTimeout(1_000) {
            while (events.count { it is T } < expectedCount) delay(1)
        }
    }

    private fun resolvedIdentity(seed: Int): ResolvedPeerIdentity = ResolvedPeerIdentity(
        nodeId = MeshNodeId.fromDigest(
            ByteArray(MeshNodeId.DIGEST_BYTES) { index -> (index + seed).toByte() },
        ),
        publicKeyBase64 = "ignored-by-transport-adapter",
        possessionVerified = true,
    )

    private suspend inline fun <reified T : Throwable> expectSuspendThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (thrown: Throwable) {
            if (thrown is T) return thrown
            throw AssertionError(
                "Expected ${T::class.java.name}, got ${thrown::class.java.name}",
                thrown,
            )
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }

    private class FakeBleTransportClock(var now: Long) : BleTransportClock {
        override fun nowMs(): Long = now
    }

    private class SequenceOpportunityIdGenerator(ids: List<String>) : BleOpportunityIdGenerator {
        private val remaining = ArrayDeque(ids.map(::TransportOpportunityId))

        override fun nextId(): TransportOpportunityId = remaining.removeFirst()
    }

    private class FakeBleTransportPlatform(
        private val serverStartSucceeds: Boolean = true,
        private val advertiserStartSucceeds: Boolean = true,
        private val scannerStartSucceeds: Boolean = true,
    ) : BleTransportPlatform {
        val calls = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, ByteArray>>()

        var identityBehavior: suspend (String) -> ResolvedPeerIdentity? = { null }
        var sendBehavior: suspend (String, ByteArray) -> BleGattSendResult = { _, _ ->
            BleGattSendResult.Success(frameCount = 1)
        }

        private var inboundCallback: (suspend (String, ByteArray) -> Unit)? = null
        private var scanCallback: ((PeerAdvertisement) -> Unit)? = null

        override fun startServer(
            onBytes: suspend (deviceAddress: String, bytes: ByteArray) -> Unit,
        ): Boolean {
            calls += "server:start"
            if (serverStartSucceeds) inboundCallback = onBytes
            return serverStartSucceeds
        }

        override fun startAdvertising(): Boolean {
            calls += "advertiser:start"
            return advertiserStartSucceeds
        }

        override fun startScanning(onAdvertisement: (PeerAdvertisement) -> Unit): Boolean {
            calls += "scanner:start"
            if (scannerStartSucceeds) scanCallback = onAdvertisement
            return scannerStartSucceeds
        }

        override fun stopScanning() {
            calls += "scanner:stop"
            scanCallback = null
        }

        override fun stopAdvertising() {
            calls += "advertiser:stop"
        }

        override fun stopServer() {
            calls += "server:stop"
            inboundCallback = null
        }

        override suspend fun resolveIdentity(deviceAddress: String): ResolvedPeerIdentity? =
            identityBehavior(deviceAddress)

        override suspend fun sendBytes(
            deviceAddress: String,
            bytes: ByteArray,
        ): BleGattSendResult {
            val immutable = bytes.copyOf()
            sent += deviceAddress to immutable
            return sendBehavior(deviceAddress, immutable.copyOf())
        }

        fun emitScan(deviceAddress: String, seenAtMs: Long) {
            checkNotNull(scanCallback)(
                PeerAdvertisement(
                    deviceAddress = deviceAddress,
                    rssi = -55,
                    seenAtMs = seenAtMs,
                ),
            )
        }

        suspend fun emitInbound(deviceAddress: String, bytes: ByteArray) {
            checkNotNull(inboundCallback)(deviceAddress, bytes.copyOf())
        }
    }

    private companion object {
        const val ADDRESS_A = "02:00:00:00:00:0A"
        const val ADDRESS_B = "02:00:00:00:00:0B"
        const val ADDRESS_UNKNOWN = "02:00:00:00:00:FF"
    }
}
