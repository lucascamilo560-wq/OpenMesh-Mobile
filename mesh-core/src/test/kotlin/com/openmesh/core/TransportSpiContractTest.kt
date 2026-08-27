package com.openmesh.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSpiContractTest {

    @Test
    fun `platform-free fake exposes opportunity lifecycle and idempotent adapter lifecycle`() =
        runBlocking {
            val adapter = FakeTransportAdapter(ADAPTER_ID)
            val opportunity = opportunity(peer = knownPeer())
            val events = async(start = CoroutineStart.UNDISPATCHED) {
                adapter.events.take(3).toList()
            }

            adapter.start()
            adapter.start()
            adapter.emit(TransportEvent.OpportunityAvailable(opportunity))
            adapter.emit(
                TransportEvent.OpportunityChanged(
                    opportunity.copy(validUntilMs = opportunity.validUntilMs + 1),
                ),
            )
            adapter.emit(
                TransportEvent.OpportunityUnavailable(
                    adapterId = ADAPTER_ID,
                    opportunityId = OPPORTUNITY_ID,
                    occurredAtMs = 200,
                    reason = TransportOpportunityUnavailableReason.LOST,
                ),
            )
            adapter.stop()
            adapter.stop()

            val observed = events.await()
            assertTrue(observed[0] is TransportEvent.OpportunityAvailable)
            assertTrue(observed[1] is TransportEvent.OpportunityChanged)
            assertEquals(
                TransportOpportunityUnavailableReason.LOST,
                (observed[2] as TransportEvent.OpportunityUnavailable).reason,
            )
            assertEquals(1, adapter.startTransitions)
            assertEquals(1, adapter.stopTransitions)
        }

    @Test
    fun `known and unknown peers are first-class without inventing identity`() {
        val known = opportunity(peer = knownPeer())
        val unknown = opportunity(
            opportunityId = TransportOpportunityId("unknown-radio-contact"),
            peer = TransportPeer.Unknown,
            address = null,
        )

        assertTrue(known.peer is TransportPeer.KnownNode)
        assertSame(TransportPeer.Unknown, unknown.peer)
        assertEquals(null, unknown.address)
    }

    @Test
    fun `send receive bidirectional and unknown direction remain distinct`() {
        assertTrue(TransportDirection.SEND_ONLY.isSendKnown)
        assertFalse(TransportDirection.SEND_ONLY.isReceiveKnown)
        assertFalse(TransportDirection.RECEIVE_ONLY.isSendKnown)
        assertTrue(TransportDirection.RECEIVE_ONLY.isReceiveKnown)
        assertTrue(TransportDirection.BIDIRECTIONAL.isSendKnown)
        assertTrue(TransportDirection.BIDIRECTIONAL.isReceiveKnown)
        assertFalse(TransportDirection.UNKNOWN.isSendKnown)
        assertFalse(TransportDirection.UNKNOWN.isReceiveKnown)

        assertEquals(
            TransportDirection.SEND_ONLY,
            opportunity(direction = TransportDirection.SEND_ONLY).direction,
        )
        assertEquals(
            TransportDirection.RECEIVE_ONLY,
            opportunity(direction = TransportDirection.RECEIVE_ONLY).direction,
        )
    }

    @Test
    fun `opportunity freshness is finite and stale observations never become routes`() {
        val opportunity = opportunity(observedAtMs = 1_000, validUntilMs = 2_000)

        assertFalse(opportunity.isFreshAt(999))
        assertTrue(opportunity.isFreshAt(1_000))
        assertTrue(opportunity.isFreshAt(1_999))
        assertFalse(opportunity.isFreshAt(2_000))
        expectThrows<IllegalArgumentException> {
            opportunity(
                observedAtMs = 0,
                validUntilMs = TransportOpportunity.MAX_VALIDITY_MS + 1,
            )
        }
    }

    @Test
    fun `inbound bytes and transport addresses are opaque defensive copies`() {
        val addressInput = byteArrayOf(0x01, 0x02, 0x03)
        val payloadInput = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val address = TransportAddress.copyOf(ADAPTER_ID, addressInput)
        val inbound = TransportEvent.InboundBytes.copyOf(
            adapterId = ADAPTER_ID,
            opportunityId = OPPORTUNITY_ID,
            sourceAddress = address,
            occurredAtMs = 300,
            bytes = payloadInput,
        )

        addressInput[0] = 0x7f
        payloadInput[0] = 0x7f
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), address.copyToByteArray())
        assertArrayEquals(
            byteArrayOf(0x10, 0x20, 0x30, 0x40),
            inbound.bytes.copyToByteArray(),
        )

        val leakedCopy = inbound.bytes.copyToByteArray()
        leakedCopy[1] = 0x7f
        assertArrayEquals(
            byteArrayOf(0x10, 0x20, 0x30, 0x40),
            inbound.bytes.copyToByteArray(),
        )
    }

    @Test
    fun `local transfer completion cannot promote durable or final evidence`() = runBlocking {
        val store = waitingStore("spi-local-completion")
        val deliveryId = DeliveryId("spi-local-completion")
        val before = store.snapshot(deliveryId)
        val outboxBefore = store.listOutbox()
        val adapter = FakeTransportAdapter(ADAPTER_ID) { request ->
            TransportTransferResult.CompletedLocally(
                transferId = request.transferId,
                occurredAtMs = 500,
            )
        }

        val result = adapter.transfer(transferRequest())

        assertTrue(result is TransportTransferResult.CompletedLocally)
        val after = store.snapshot(deliveryId)
        assertEquals(before, after)
        assertTrue(after.nextHopAcceptances.isEmpty())
        assertTrue(after.receipts.isEmpty())
        assertEquals(outboxBefore, store.listOutbox())
        assertFalse(
            store.listOutbox().any { outbox ->
                outbox.event is DeliveryStoreEvent.NextHopAcceptedDurably ||
                    outbox.event is DeliveryStoreEvent.ApplicationDelivered
            },
        )
    }

    @Test
    fun `adapter failure is local and another adapter remains usable`() = runBlocking {
        val failedAdapter = FakeTransportAdapter(TransportAdapterId("radio-a")) { request ->
            TransportTransferResult.FailedLocally(
                transferId = request.transferId,
                occurredAtMs = 600,
                failure = TransportLocalFailure(TransportLocalFailureCode.IO_ERROR),
            )
        }
        val healthyAdapter = FakeTransportAdapter(TransportAdapterId("radio-b")) { request ->
            TransportTransferResult.CompletedLocally(request.transferId, occurredAtMs = 601)
        }
        val adapters = TransportAdapterSet.copyOf(listOf(failedAdapter, healthyAdapter))

        val failed = failedAdapter.transfer(
            transferRequest(adapterId = failedAdapter.adapterId),
        )
        val completed = healthyAdapter.transfer(
            transferRequest(adapterId = healthyAdapter.adapterId),
        )

        assertTrue(failed is TransportTransferResult.FailedLocally)
        assertTrue(completed is TransportTransferResult.CompletedLocally)
        assertEquals(2, adapters.size)
        assertSame(healthyAdapter, adapters[healthyAdapter.adapterId])
    }

    @Test
    fun `transport identifiers addresses and opaque inputs are bounded`() {
        expectThrows<IllegalArgumentException> { TransportAdapterId(" ") }
        expectThrows<IllegalArgumentException> { TransportAdapterId("adapter\ninvalid") }
        expectThrows<IllegalArgumentException> { TransportAdapterId("a".repeat(129)) }
        expectThrows<IllegalArgumentException> {
            TransportOpportunityId("é".repeat(129))
        }
        expectThrows<IllegalArgumentException> { TransportTransferId("") }
        expectThrows<IllegalArgumentException> {
            TransportAddress.copyOf(ADAPTER_ID, ByteArray(TransportAddress.MAX_BYTES + 1))
        }
        expectThrows<IllegalArgumentException> {
            TransportTransferRequest.copyOf(
                transferId = TRANSFER_ID,
                opportunity = TransportOpportunityKey(ADAPTER_ID, OPPORTUNITY_ID),
                bytes = ByteArray(TransportContractLimits.MAX_OPAQUE_BYTES + 1),
            )
        }
        expectThrows<IllegalArgumentException> {
            TransportEvent.InboundBytes.copyOf(
                adapterId = ADAPTER_ID,
                occurredAtMs = 1,
                bytes = byteArrayOf(),
            )
        }
    }

    @Test
    fun `zero adapters is valid and duplicate adapter identity fails closed`() {
        val empty = TransportAdapterSet.copyOf(emptyList())
        val adapter = FakeTransportAdapter(ADAPTER_ID)

        assertSame(TransportAdapterSet.Empty, empty)
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.size)
        expectThrows<IllegalArgumentException> {
            TransportAdapterSet.copyOf(listOf(adapter, adapter))
        }
    }

    @Test
    fun `core SPI exposes no Android application policy or store authority`() {
        val contractTypes = listOf(
            TransportAdapter::class.java,
            TransportAdapterSet::class.java,
            TransportOpportunity::class.java,
            TransportEvent::class.java,
            TransportEvent.InboundBytes::class.java,
            TransportTransferRequest::class.java,
            TransportTransferResult::class.java,
            TransportTransferResult.CompletedLocally::class.java,
            TransportTransferResult.OpportunityUnavailable::class.java,
            TransportTransferResult.FailedLocally::class.java,
        )
        val exposedTypes = contractTypes.flatMap { type ->
            type.declaredMethods.flatMap { method ->
                method.parameterTypes.toList() + method.returnType
            } + type.declaredFields.map { it.type }
        }
        val forbiddenAuthorityTypes = setOf(
            DeliveryPolicy::class.java,
            DeliveryId::class.java,
            DeliveryState::class.java,
            TransferLease::class.java,
            ReceiptInput::class.java,
            VerifiedNextHopAcceptance::class.java,
        )

        assertFalse(
            exposedTypes.any { type ->
                type.name.startsWith("android.") ||
                    type.name.startsWith("androidx.") ||
                    type.name.startsWith("com.openmesh.android.")
            },
        )
        assertTrue(exposedTypes.none { it in forbiddenAuthorityTypes })
        assertTrue(
            TransportAdapter::class.java.declaredMethods.none { method ->
                listOf("route", "priority", "retry", "copyBudget", "deliveryPolicy")
                    .any { forbidden -> method.name.contains(forbidden, ignoreCase = true) }
            },
        )
    }

    private fun opportunity(
        opportunityId: TransportOpportunityId = OPPORTUNITY_ID,
        observedAtMs: Long = 100,
        validUntilMs: Long = 1_000,
        direction: TransportDirection = TransportDirection.BIDIRECTIONAL,
        peer: TransportPeer = TransportPeer.Unknown,
        address: TransportAddress? = TransportAddress.copyOf(
            ADAPTER_ID,
            "adapter-local-address".encodeToByteArray(),
        ),
    ): TransportOpportunity = TransportOpportunity(
        adapterId = ADAPTER_ID,
        opportunityId = opportunityId,
        observedAtMs = observedAtMs,
        validUntilMs = validUntilMs,
        direction = direction,
        peer = peer,
        address = address,
    )

    private fun knownPeer(): TransportPeer.KnownNode = TransportPeer.KnownNode(
        NodeId(MeshNodeId.fromDigest(ByteArray(MeshNodeId.DIGEST_BYTES) { it.toByte() })),
    )

    private fun transferRequest(
        adapterId: TransportAdapterId = ADAPTER_ID,
    ): TransportTransferRequest = TransportTransferRequest.copyOf(
        transferId = TRANSFER_ID,
        opportunity = TransportOpportunityKey(adapterId, OPPORTUNITY_ID),
        bytes = byteArrayOf(0x01, 0x02, 0x03),
    )

    private suspend fun waitingStore(id: String): InMemoryDeliveryStore {
        val store = InMemoryDeliveryStore()
        val delivery = DeliveryObject.copyOf(
            deliveryId = DeliveryId(id),
            destination = EndpointId("dtn://example.test/inbox/transport-spi"),
            createdAtMs = 100,
            expiresAtMs = 10_000,
            canonicalBytes = "opaque-$id".encodeToByteArray(),
        )
        store.ingest(
            DeliveryIngest(
                deliveryObject = delivery,
                destinationIsLocal = false,
                provenance = IngressProvenance(
                    kind = IngressProvenance.Kind.REMOTE_PROTOCOL,
                    reference = "transport-spi-test",
                ),
            ),
            nowMs = 100,
        )
        return store
    }

    private inline fun <reified T : Throwable> expectThrows(block: () -> Unit) {
        try {
            block()
        } catch (thrown: Throwable) {
            if (thrown is T) return
            throw AssertionError(
                "Expected ${T::class.java.name}, got ${thrown::class.java.name}",
                thrown,
            )
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }

    private class FakeTransportAdapter(
        override val adapterId: TransportAdapterId,
        private val transferBehavior: suspend (TransportTransferRequest) -> TransportTransferResult =
            { request ->
                TransportTransferResult.CompletedLocally(
                    transferId = request.transferId,
                    occurredAtMs = 400,
                )
            },
    ) : TransportAdapter {
        private val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 8)
        override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

        var startTransitions: Int = 0
            private set
        var stopTransitions: Int = 0
            private set
        private var started: Boolean = false

        override suspend fun start() {
            if (!started) {
                started = true
                startTransitions += 1
            }
        }

        override suspend fun stop() {
            if (started) {
                started = false
                stopTransitions += 1
            }
        }

        override suspend fun transfer(
            request: TransportTransferRequest,
        ): TransportTransferResult {
            require(request.opportunity.adapterId == adapterId) {
                "Transfer opportunity belongs to another adapter"
            }
            return transferBehavior(request)
        }

        suspend fun emit(event: TransportEvent) {
            require(event.adapterId == adapterId) { "Event belongs to another adapter" }
            mutableEvents.emit(event)
        }
    }

    private companion object {
        val ADAPTER_ID = TransportAdapterId("test-radio")
        val OPPORTUNITY_ID = TransportOpportunityId("temporary-contact-1")
        val TRANSFER_ID = TransportTransferId("transport-request-1")
    }
}
