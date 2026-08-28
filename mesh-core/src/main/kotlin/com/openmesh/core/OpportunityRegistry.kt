package com.openmesh.core

import java.util.Collections
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Monotonic local version of one [OpportunityRegistry] snapshot. */
@JvmInline
value class OpportunityRegistrySequence(val value: Long) {
    init {
        require(value >= 0) { "Opportunity registry sequence must not be negative" }
    }

    override fun toString(): String = value.toString()

    companion object {
        val Initial = OpportunityRegistrySequence(0)
    }
}

/**
 * Immutable, internally consistent view of currently observed opportunities.
 *
 * The registry sequence is local state versioning and is deliberately distinct
 * from [TransportOpportunityRevision], which versions one adapter observation.
 */
class OpportunityRegistrySnapshot internal constructor(
    val sequence: OpportunityRegistrySequence,
    current: Map<TransportOpportunityKey, TransportOpportunity>,
) : Iterable<TransportOpportunity> {
    private val currentByKey: Map<TransportOpportunityKey, TransportOpportunity> =
        Collections.unmodifiableMap(LinkedHashMap(current))

    val opportunities: List<TransportOpportunity> =
        Collections.unmodifiableList(ArrayList(currentByKey.values))

    val size: Int
        get() = currentByKey.size

    val isEmpty: Boolean
        get() = currentByKey.isEmpty()

    operator fun get(key: TransportOpportunityKey): TransportOpportunity? = currentByKey[key]

    operator fun get(reference: TransportOpportunityReference): TransportOpportunity? =
        currentByKey[reference.key]?.takeIf { it.reference == reference }

    override fun iterator(): Iterator<TransportOpportunity> = opportunities.iterator()
}

/** Explicit outcome of applying one transport fact to the registry. */
sealed interface OpportunityRegistryApplyResult {
    val sequence: OpportunityRegistrySequence

    data class Applied(
        override val sequence: OpportunityRegistrySequence,
    ) : OpportunityRegistryApplyResult

    data class Rejected(
        override val sequence: OpportunityRegistrySequence,
        val reason: OpportunityRegistryRejectionReason,
    ) : OpportunityRegistryApplyResult

    /** Inbound bytes are protocol input, never opportunity-state mutations. */
    data class IgnoredInbound(
        override val sequence: OpportunityRegistrySequence,
    ) : OpportunityRegistryApplyResult
}

enum class OpportunityRegistryRejectionReason {
    ALREADY_CURRENT,
    NO_CURRENT_OPPORTUNITY,
    STALE_REFERENCE,
    CAPACITY_REACHED,
    SEQUENCE_EXHAUSTED,
}

/**
 * Linearizable, transport-neutral registry of temporary local observations.
 *
 * It contains only the current revision of each active opportunity. It is not
 * a route table, delivery history, protocol parser, or authority over adapter
 * lifecycle. In particular, stale observations remain present until their
 * adapter emits [TransportEvent.OpportunityUnavailable].
 */
class OpportunityRegistry(
    val maxCurrentOpportunities: Int = DEFAULT_MAX_CURRENT_OPPORTUNITIES,
) {
    init {
        require(maxCurrentOpportunities > 0) {
            "Opportunity registry capacity must be positive"
        }
        require(maxCurrentOpportunities <= MAX_CONFIGURABLE_CURRENT_OPPORTUNITIES) {
            "Opportunity registry capacity exceeds $MAX_CONFIGURABLE_CURRENT_OPPORTUNITIES"
        }
    }

    private val mutex = Mutex()
    private val currentByKey = linkedMapOf<TransportOpportunityKey, TransportOpportunity>()
    private var sequence = OpportunityRegistrySequence.Initial

    /**
     * Applies one event atomically.
     *
     * Rejections never mutate state or advance [OpportunityRegistrySequence].
     * Capacity applies only to new lifecycles; current entries can always be
     * changed or removed so a full registry can recover.
     */
    suspend fun apply(event: TransportEvent): OpportunityRegistryApplyResult = mutex.withLock {
        when (event) {
            is TransportEvent.OpportunityAvailable -> applyAvailable(event.opportunity)
            is TransportEvent.OpportunityChanged -> applyChanged(event)
            is TransportEvent.OpportunityUnavailable -> applyUnavailable(event)
            is TransportEvent.InboundBytes ->
                OpportunityRegistryApplyResult.IgnoredInbound(sequence)
        }
    }

    suspend fun snapshot(): OpportunityRegistrySnapshot = mutex.withLock {
        OpportunityRegistrySnapshot(sequence, currentByKey)
    }

    private fun applyAvailable(
        opportunity: TransportOpportunity,
    ): OpportunityRegistryApplyResult {
        if (currentByKey.containsKey(opportunity.key)) {
            return rejected(OpportunityRegistryRejectionReason.ALREADY_CURRENT)
        }
        if (currentByKey.size >= maxCurrentOpportunities) {
            return rejected(OpportunityRegistryRejectionReason.CAPACITY_REACHED)
        }
        val next = nextSequenceOrReject() ?: return rejected(
            OpportunityRegistryRejectionReason.SEQUENCE_EXHAUSTED,
        )
        currentByKey[opportunity.key] = opportunity
        sequence = next
        return OpportunityRegistryApplyResult.Applied(sequence)
    }

    private fun applyChanged(
        event: TransportEvent.OpportunityChanged,
    ): OpportunityRegistryApplyResult {
        val current = currentByKey[event.previous.key]
            ?: return rejected(OpportunityRegistryRejectionReason.NO_CURRENT_OPPORTUNITY)
        if (current.reference != event.previous) {
            return rejected(OpportunityRegistryRejectionReason.STALE_REFERENCE)
        }
        val next = nextSequenceOrReject() ?: return rejected(
            OpportunityRegistryRejectionReason.SEQUENCE_EXHAUSTED,
        )
        currentByKey[event.opportunity.key] = event.opportunity
        sequence = next
        return OpportunityRegistryApplyResult.Applied(sequence)
    }

    private fun applyUnavailable(
        event: TransportEvent.OpportunityUnavailable,
    ): OpportunityRegistryApplyResult {
        val current = currentByKey[event.opportunity.key]
            ?: return rejected(OpportunityRegistryRejectionReason.NO_CURRENT_OPPORTUNITY)
        if (current.reference != event.opportunity) {
            return rejected(OpportunityRegistryRejectionReason.STALE_REFERENCE)
        }
        val next = nextSequenceOrReject() ?: return rejected(
            OpportunityRegistryRejectionReason.SEQUENCE_EXHAUSTED,
        )
        currentByKey.remove(event.opportunity.key)
        sequence = next
        return OpportunityRegistryApplyResult.Applied(sequence)
    }

    private fun nextSequenceOrReject(): OpportunityRegistrySequence? =
        if (sequence.value == Long.MAX_VALUE) {
            null
        } else {
            OpportunityRegistrySequence(sequence.value + 1)
        }

    private fun rejected(
        reason: OpportunityRegistryRejectionReason,
    ): OpportunityRegistryApplyResult.Rejected =
        OpportunityRegistryApplyResult.Rejected(sequence, reason)

    companion object {
        const val DEFAULT_MAX_CURRENT_OPPORTUNITIES = 1_024
        const val MAX_CONFIGURABLE_CURRENT_OPPORTUNITIES = 65_536
    }
}
