package com.openmesh.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpportunityDecisionEngineTest {

    @Test
    fun `empty registry is valid and queries do not advance sequence`() = runBlocking {
        val registry = OpportunityRegistry()

        val first = registry.snapshot()
        val second = registry.snapshot()

        assertTrue(first.isEmpty)
        assertEquals(0, first.size)
        assertEquals(OpportunityRegistrySequence.Initial, first.sequence)
        assertEquals(first.sequence, second.sequence)
    }

    @Test
    fun `opportunities from multiple adapters coexist without becoming routes`() = runBlocking {
        val registry = OpportunityRegistry()
        val radio = opportunity(adapterId = ADAPTER_A, opportunityId = OPPORTUNITY_A)
        val internet = opportunity(adapterId = ADAPTER_B, opportunityId = OPPORTUNITY_B)

        assertApplied(registry.apply(TransportEvent.OpportunityAvailable(radio)), 1)
        assertApplied(registry.apply(TransportEvent.OpportunityAvailable(internet)), 2)

        val snapshot = registry.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(radio, snapshot[radio.key])
        assertEquals(internet, snapshot[internet.key])
    }

    @Test
    fun `available changed and unavailable require exact current references`() = runBlocking {
        val registry = OpportunityRegistry()
        val revisionOne = opportunity()
        val revisionTwo = revisionOne.copy(
            revision = REVISION_2,
            observedAtMs = 200,
            validUntilMs = 1_200,
        )

        assertApplied(registry.apply(TransportEvent.OpportunityAvailable(revisionOne)), 1)
        assertRejected(
            registry.apply(TransportEvent.OpportunityAvailable(revisionOne)),
            OpportunityRegistryRejectionReason.ALREADY_CURRENT,
            sequence = 1,
        )
        assertApplied(
            registry.apply(
                TransportEvent.OpportunityChanged(revisionOne.reference, revisionTwo),
            ),
            sequence = 2,
        )
        assertRejected(
            registry.apply(
                TransportEvent.OpportunityChanged(
                    previous = revisionOne.reference,
                    opportunity = revisionTwo.copy(revision = REVISION_3),
                ),
            ),
            OpportunityRegistryRejectionReason.STALE_REFERENCE,
            sequence = 2,
        )
        assertRejected(
            registry.apply(
                TransportEvent.OpportunityUnavailable(
                    opportunity = revisionOne.reference,
                    occurredAtMs = 300,
                    reason = TransportOpportunityUnavailableReason.LOST,
                ),
            ),
            OpportunityRegistryRejectionReason.STALE_REFERENCE,
            sequence = 2,
        )
        assertEquals(revisionTwo, registry.snapshot()[revisionTwo.key])

        assertApplied(
            registry.apply(
                TransportEvent.OpportunityUnavailable(
                    opportunity = revisionTwo.reference,
                    occurredAtMs = 301,
                    reason = TransportOpportunityUnavailableReason.LOST,
                ),
            ),
            sequence = 3,
        )
        assertTrue(registry.snapshot().isEmpty)
    }

    @Test
    fun `inbound bytes never mutate opportunity state or registry sequence`() = runBlocking {
        val registry = OpportunityRegistry()
        val current = opportunity()
        registry.apply(TransportEvent.OpportunityAvailable(current))
        val before = registry.snapshot()

        val result = registry.apply(
            TransportEvent.InboundBytes.copyOf(
                adapterId = current.adapterId,
                opportunity = current.reference,
                occurredAtMs = 300,
                bytes = byteArrayOf(0x01, 0x02),
            ),
        )

        assertTrue(result is OpportunityRegistryApplyResult.IgnoredInbound)
        assertEquals(before.sequence, result.sequence)
        val after = registry.snapshot()
        assertEquals(before.sequence, after.sequence)
        assertEquals(before.opportunities, after.opportunities)
    }

    @Test
    fun `capacity rejects new lifecycle but permits changed unavailable and recovery`() =
        runBlocking {
            val registry = OpportunityRegistry(maxCurrentOpportunities = 1)
            val first = opportunity(opportunityId = OPPORTUNITY_A)
            val second = opportunity(opportunityId = OPPORTUNITY_B)
            val changed = first.copy(
                revision = REVISION_2,
                observedAtMs = 200,
                validUntilMs = 1_200,
            )

            assertApplied(registry.apply(TransportEvent.OpportunityAvailable(first)), 1)
            assertRejected(
                registry.apply(TransportEvent.OpportunityAvailable(second)),
                OpportunityRegistryRejectionReason.CAPACITY_REACHED,
                sequence = 1,
            )
            assertApplied(
                registry.apply(TransportEvent.OpportunityChanged(first.reference, changed)),
                sequence = 2,
            )
            assertApplied(
                registry.apply(
                    TransportEvent.OpportunityUnavailable(
                        changed.reference,
                        occurredAtMs = 300,
                        reason = TransportOpportunityUnavailableReason.EXPIRED,
                    ),
                ),
                sequence = 3,
            )
            assertApplied(registry.apply(TransportEvent.OpportunityAvailable(second)), 4)
            assertEquals(second, registry.snapshot()[second.key])
        }

    @Test
    fun `snapshots are immutable consistent and retain their own sequence`() = runBlocking {
        val registry = OpportunityRegistry()
        val revisionOne = opportunity()
        val revisionTwo = revisionOne.copy(
            revision = REVISION_2,
            observedAtMs = 200,
            validUntilMs = 1_200,
        )
        registry.apply(TransportEvent.OpportunityAvailable(revisionOne))
        val first = registry.snapshot()
        registry.apply(TransportEvent.OpportunityChanged(revisionOne.reference, revisionTwo))
        val second = registry.snapshot()

        assertEquals(OpportunityRegistrySequence(1), first.sequence)
        assertEquals(revisionOne, first[revisionOne.key])
        assertEquals(OpportunityRegistrySequence(2), second.sequence)
        assertEquals(revisionTwo, second[revisionTwo.key])
        assertNotSame(first.opportunities, second.opportunities)
        expectThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (first.opportunities as MutableList<TransportOpportunity>).clear()
        }
        assertEquals(revisionOne, first[revisionOne.key])
    }

    @Test
    fun `decision uses candidate order exact revision freshness and send capability`() =
        runBlocking {
            val registry = OpportunityRegistry()
            val receiveOnly = opportunity(
                opportunityId = TransportOpportunityId("receive-only"),
                direction = TransportDirection.RECEIVE_ONLY,
            )
            val unknownDirection = opportunity(
                opportunityId = TransportOpportunityId("unknown-direction"),
                direction = TransportDirection.UNKNOWN,
            )
            val sendOnly = opportunity(
                opportunityId = TransportOpportunityId("send-only"),
                direction = TransportDirection.SEND_ONLY,
                peer = TransportPeer.Unknown,
            )
            val bidirectional = opportunity(
                opportunityId = TransportOpportunityId("bidirectional"),
                direction = TransportDirection.BIDIRECTIONAL,
            )
            listOf(receiveOnly, unknownDirection, sendOnly, bidirectional).forEach {
                registry.apply(TransportEvent.OpportunityAvailable(it))
            }

            val result = engine.decide(
                registry.snapshot(),
                request(
                    candidates = listOf(
                        receiveOnly.reference,
                        unknownDirection.reference,
                        sendOnly.reference,
                        bidirectional.reference,
                    ),
                    nowMs = 500,
                ),
            )

            val transfer = result as TransportDecision.Transfer
            assertEquals(sendOnly.reference, transfer.opportunity)
            assertEquals(2, transfer.candidateIndex)
            assertEquals(OpportunityRegistrySequence(4), transfer.registrySequence)
            assertSame(TransportPeer.Unknown, registry.snapshot()[sendOnly.key]?.peer)
        }

    @Test
    fun `only send-only and bidirectional opportunities are independently eligible`() =
        runBlocking {
            val opportunities = TransportDirection.entries.associateWith { direction ->
                opportunity(
                    opportunityId = TransportOpportunityId("direction-${direction.name}"),
                    direction = direction,
                )
            }
            val registry = OpportunityRegistry()
            opportunities.values.forEach { current ->
                registry.apply(TransportEvent.OpportunityAvailable(current))
            }
            val snapshot = registry.snapshot()

            opportunities.forEach { (direction, current) ->
                val decision = engine.decide(
                    snapshot,
                    request(listOf(current.reference), nowMs = 500),
                )
                if (direction == TransportDirection.SEND_ONLY ||
                    direction == TransportDirection.BIDIRECTIONAL
                ) {
                    assertEquals(
                        current.reference,
                        (decision as TransportDecision.Transfer).opportunity,
                    )
                } else {
                    assertEquals(
                        TransportCandidateRejectionReason.NOT_SEND_CAPABLE,
                        (decision as TransportDecision.Wait).rejectedCandidates.single().reason,
                    )
                }
            }
        }

    @Test
    fun `stale current opportunity remains visible but is not transfer eligible`() = runBlocking {
        val registry = OpportunityRegistry()
        val stale = opportunity(observedAtMs = 100, validUntilMs = 200)
        registry.apply(TransportEvent.OpportunityAvailable(stale))

        val snapshot = registry.snapshot()
        val result = engine.decide(snapshot, request(listOf(stale.reference), nowMs = 200))

        assertEquals(stale, snapshot[stale.key])
        val wait = result as TransportDecision.Wait
        assertEquals(TransportWaitReason.NO_USABLE_CANDIDATE, wait.reason)
        assertEquals(
            TransportCandidateRejectionReason.STALE,
            wait.rejectedCandidates.single().reason,
        )
    }

    @Test
    fun `no missing and superseded candidates wait without declaring delivery failure`() =
        runBlocking {
            val registry = OpportunityRegistry()
            val revisionOne = opportunity()
            val revisionTwo = revisionOne.copy(
                revision = REVISION_2,
                observedAtMs = 200,
                validUntilMs = 1_200,
            )
            registry.apply(TransportEvent.OpportunityAvailable(revisionOne))
            registry.apply(TransportEvent.OpportunityChanged(revisionOne.reference, revisionTwo))

            val noCandidates = engine.decide(registry.snapshot(), request(emptyList(), 300))
                as TransportDecision.Wait
            assertEquals(TransportWaitReason.NO_CANDIDATES, noCandidates.reason)
            assertTrue(noCandidates.rejectedCandidates.isEmpty())

            val missing = opportunity(opportunityId = TransportOpportunityId("missing"))
            val rejected = engine.decide(
                registry.snapshot(),
                request(listOf(missing.reference, revisionOne.reference), 300),
            ) as TransportDecision.Wait
            assertEquals(
                listOf(
                    TransportCandidateRejectionReason.NO_CURRENT_OPPORTUNITY,
                    TransportCandidateRejectionReason.SUPERSEDED,
                ),
                rejected.rejectedCandidates.map { it.reason },
            )
        }

    @Test
    fun `reevaluation can change wait to transfer and decisions bind registry sequence`() =
        runBlocking {
            val registry = OpportunityRegistry()
            val future = opportunity(direction = TransportDirection.SEND_ONLY)
            val request = request(listOf(future.reference), nowMs = 500)

            val before = engine.decide(registry.snapshot(), request) as TransportDecision.Wait
            registry.apply(TransportEvent.OpportunityAvailable(future))
            val after = engine.decide(registry.snapshot(), request) as TransportDecision.Transfer

            assertEquals(OpportunityRegistrySequence.Initial, before.registrySequence)
            assertEquals(OpportunityRegistrySequence(1), after.registrySequence)
            assertEquals(future.reference, after.opportunity)
        }

    @Test
    fun `two concurrent changes from the same previous revision cannot both win`() = runBlocking {
        repeat(100) { iteration ->
            val registry = OpportunityRegistry()
            val revisionOne = opportunity(opportunityId = TransportOpportunityId("change-$iteration"))
            val revisionTwo = revisionOne.copy(
                revision = REVISION_2,
                observedAtMs = 200,
                validUntilMs = 1_200,
            )
            val revisionThree = revisionOne.copy(
                revision = REVISION_3,
                observedAtMs = 201,
                validUntilMs = 1_201,
            )
            registry.apply(TransportEvent.OpportunityAvailable(revisionOne))
            val gate = CompletableDeferred<Unit>()

            val results = listOf(revisionTwo, revisionThree).map { next ->
                async(Dispatchers.Default) {
                    gate.await()
                    registry.apply(TransportEvent.OpportunityChanged(revisionOne.reference, next))
                }
            }
            gate.complete(Unit)
            val completed = results.awaitAll()

            assertEquals(1, completed.count { it is OpportunityRegistryApplyResult.Applied })
            assertEquals(1, completed.count { it is OpportunityRegistryApplyResult.Rejected })
            val current = registry.snapshot()[revisionOne.key]
            assertTrue(current == revisionTwo || current == revisionThree)
            assertEquals(OpportunityRegistrySequence(2), registry.snapshot().sequence)
        }
    }

    @Test
    fun `changed racing unavailable is linearizable in either winning order`() = runBlocking {
        repeat(100) { iteration ->
            val registry = OpportunityRegistry()
            val revisionOne = opportunity(opportunityId = TransportOpportunityId("race-$iteration"))
            val revisionTwo = revisionOne.copy(
                revision = REVISION_2,
                observedAtMs = 200,
                validUntilMs = 1_200,
            )
            registry.apply(TransportEvent.OpportunityAvailable(revisionOne))
            val gate = CountDownLatch(1)

            val change = async(Dispatchers.Default) {
                assertTrue(gate.await(1, TimeUnit.SECONDS))
                registry.apply(TransportEvent.OpportunityChanged(revisionOne.reference, revisionTwo))
            }
            val unavailable = async(Dispatchers.Default) {
                assertTrue(gate.await(1, TimeUnit.SECONDS))
                registry.apply(
                    TransportEvent.OpportunityUnavailable(
                        revisionOne.reference,
                        occurredAtMs = 210,
                        reason = TransportOpportunityUnavailableReason.LOST,
                    ),
                )
            }
            gate.countDown()
            val changeResult = change.await()
            val unavailableResult = unavailable.await()
            val snapshot = registry.snapshot()

            assertEquals(1, listOf(changeResult, unavailableResult).count {
                it is OpportunityRegistryApplyResult.Applied
            })
            if (changeResult is OpportunityRegistryApplyResult.Applied) {
                assertEquals(revisionTwo, snapshot[revisionOne.key])
                assertRejected(
                    unavailableResult,
                    OpportunityRegistryRejectionReason.STALE_REFERENCE,
                    sequence = 2,
                )
            } else {
                assertTrue(snapshot.isEmpty)
                assertRejected(
                    changeResult,
                    OpportunityRegistryRejectionReason.NO_CURRENT_OPPORTUNITY,
                    sequence = 2,
                )
            }
        }
    }

    @Test
    fun `decision request is bounded unique and defensively copied`() {
        val mutable = mutableListOf(opportunity().reference)
        val request = request(mutable, nowMs = 500)
        mutable.clear()
        assertEquals(1, request.candidates.size)
        expectThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (request.candidates as MutableList<TransportOpportunityReference>).clear()
        }
        expectThrows<IllegalArgumentException> {
            request(listOf(opportunity().reference, opportunity().reference), nowMs = 500)
        }
        expectThrows<IllegalArgumentException> {
            request(
                candidates = (0..TransportDecisionLimits.MAX_CANDIDATE_REFERENCES).map { index ->
                    opportunity(
                        opportunityId = TransportOpportunityId("candidate-$index"),
                    ).reference
                },
                nowMs = 500,
            )
        }
    }

    @Test
    fun `registry and engine APIs expose no platform store evidence or adapter execution authority`() {
        val contractTypes = listOf(
            OpportunityRegistry::class.java,
            OpportunityRegistrySnapshot::class.java,
            OpportunityRegistryApplyResult::class.java,
            TransportDecisionRequest::class.java,
            TransportDecision::class.java,
            TransportDecision.Wait::class.java,
            TransportDecision.Transfer::class.java,
            MinimalTransportDecisionEngine::class.java,
        )
        val exposedTypes = contractTypes.flatMap { type ->
            type.declaredMethods.flatMap { method ->
                method.parameterTypes.toList() + method.returnType
            } + type.declaredFields.map { it.type }
        }
        val forbidden = setOf(
            DeliveryStore::class.java,
            DeliveryPolicy::class.java,
            TransferLease::class.java,
            ReceiptInput::class.java,
            VerifiedNextHopAcceptance::class.java,
            TransportAdapter::class.java,
            TransportTransferRequest::class.java,
            TransportTransferResult::class.java,
        )

        assertFalse(exposedTypes.any { type ->
            type.name.startsWith("android.") ||
                type.name.startsWith("androidx.") ||
                type.name.startsWith("com.openmesh.android.")
        })
        assertTrue(exposedTypes.none { it in forbidden })
        assertTrue(
            MinimalTransportDecisionEngine::class.java.declaredMethods.none { method ->
                method.name.equals("transfer", ignoreCase = true)
            },
        )
    }

    private fun opportunity(
        adapterId: TransportAdapterId = ADAPTER_A,
        opportunityId: TransportOpportunityId = OPPORTUNITY_A,
        revision: TransportOpportunityRevision = REVISION_1,
        observedAtMs: Long = 100,
        validUntilMs: Long = 1_000,
        direction: TransportDirection = TransportDirection.SEND_ONLY,
        peer: TransportPeer = TransportPeer.Unknown,
    ): TransportOpportunity = TransportOpportunity(
        adapterId = adapterId,
        opportunityId = opportunityId,
        revision = revision,
        observedAtMs = observedAtMs,
        validUntilMs = validUntilMs,
        direction = direction,
        peer = peer,
    )

    private fun request(
        candidates: Iterable<TransportOpportunityReference>,
        nowMs: Long,
    ): TransportDecisionRequest = TransportDecisionRequest.copyOf(
        subjectId = SUBJECT,
        candidateReferences = candidates,
        nowMs = nowMs,
    )

    private fun assertApplied(result: OpportunityRegistryApplyResult, sequence: Long) {
        assertTrue(result is OpportunityRegistryApplyResult.Applied)
        assertEquals(OpportunityRegistrySequence(sequence), result.sequence)
    }

    private fun assertRejected(
        result: OpportunityRegistryApplyResult,
        reason: OpportunityRegistryRejectionReason,
        sequence: Long,
    ) {
        assertTrue(result is OpportunityRegistryApplyResult.Rejected)
        assertEquals(reason, (result as OpportunityRegistryApplyResult.Rejected).reason)
        assertEquals(OpportunityRegistrySequence(sequence), result.sequence)
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

    private companion object {
        val ADAPTER_A = TransportAdapterId("radio-a")
        val ADAPTER_B = TransportAdapterId("internet-a")
        val OPPORTUNITY_A = TransportOpportunityId("contact-a")
        val OPPORTUNITY_B = TransportOpportunityId("contact-b")
        val REVISION_1 = TransportOpportunityRevision(1)
        val REVISION_2 = TransportOpportunityRevision(2)
        val REVISION_3 = TransportOpportunityRevision(3)
        val SUBJECT = TransportDecisionSubjectId("local-decision-subject")
        val engine = MinimalTransportDecisionEngine()
    }
}
