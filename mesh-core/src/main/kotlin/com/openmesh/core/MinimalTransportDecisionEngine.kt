package com.openmesh.core

import java.util.Collections

/** Opaque local subject used to correlate repeatable, non-durable decisions. */
@JvmInline
value class TransportDecisionSubjectId(val value: String) {
    init {
        require(value.isNotBlank()) { "Transport decision subject must not be blank" }
        require(value == value.trim()) {
            "Transport decision subject must not contain surrounding whitespace"
        }
        require(value.none(Char::isISOControl)) {
            "Transport decision subject must not contain control characters"
        }
        require(value.encodeToByteArray().size <= MAX_BYTES) {
            "Transport decision subject exceeds $MAX_BYTES bytes"
        }
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_BYTES = 256
    }
}

object TransportDecisionLimits {
    const val MAX_CANDIDATE_REFERENCES = 256
}

/**
 * Bounded input produced by a future forwarding policy.
 *
 * Candidate order is policy preference. The engine does not infer that every
 * observed contact is useful for this subject and never chooses a transport
 * outside this immutable list.
 */
class TransportDecisionRequest private constructor(
    val subjectId: TransportDecisionSubjectId,
    candidateReferences: List<TransportOpportunityReference>,
    val nowMs: Long,
) {
    val candidates: List<TransportOpportunityReference> =
        Collections.unmodifiableList(ArrayList(candidateReferences))

    init {
        require(nowMs >= 0) { "Transport decision time must not be negative" }
        require(candidates.size <= TransportDecisionLimits.MAX_CANDIDATE_REFERENCES) {
            "Transport decision candidates exceed " +
                TransportDecisionLimits.MAX_CANDIDATE_REFERENCES
        }
        require(candidates.distinct().size == candidates.size) {
            "Transport decision candidates must be unique"
        }
    }

    companion object {
        fun copyOf(
            subjectId: TransportDecisionSubjectId,
            candidateReferences: Iterable<TransportOpportunityReference>,
            nowMs: Long,
        ): TransportDecisionRequest = TransportDecisionRequest(
            subjectId = subjectId,
            candidateReferences = boundedCandidateCopy(candidateReferences),
            nowMs = nowMs,
        )

        private fun boundedCandidateCopy(
            source: Iterable<TransportOpportunityReference>,
        ): List<TransportOpportunityReference> {
            val copy = ArrayList<TransportOpportunityReference>()
            source.forEach { candidate ->
                require(copy.size < TransportDecisionLimits.MAX_CANDIDATE_REFERENCES) {
                    "Transport decision candidates exceed " +
                        TransportDecisionLimits.MAX_CANDIDATE_REFERENCES
                }
                copy += candidate
            }
            return copy
        }
    }
}

enum class TransportCandidateRejectionReason {
    NO_CURRENT_OPPORTUNITY,
    SUPERSEDED,
    STALE,
    NOT_SEND_CAPABLE,
}

data class TransportCandidateRejection(
    val candidate: TransportOpportunityReference,
    val reason: TransportCandidateRejectionReason,
)

/** Pure, explainable decision over one immutable registry snapshot. */
sealed interface TransportDecision {
    val subjectId: TransportDecisionSubjectId
    val registrySequence: OpportunityRegistrySequence

    class Wait(
        override val subjectId: TransportDecisionSubjectId,
        override val registrySequence: OpportunityRegistrySequence,
        val reason: TransportWaitReason,
        rejectedCandidates: List<TransportCandidateRejection>,
    ) : TransportDecision {
        val rejectedCandidates: List<TransportCandidateRejection> =
            Collections.unmodifiableList(ArrayList(rejectedCandidates))

        init {
            require(rejectedCandidates.size <= TransportDecisionLimits.MAX_CANDIDATE_REFERENCES) {
                "Transport decision explanations exceed the candidate limit"
            }
            require(
                (reason == TransportWaitReason.NO_CANDIDATES) == rejectedCandidates.isEmpty(),
            ) {
                "No-candidates reason must match an empty rejection list"
            }
        }
    }

    data class Transfer(
        override val subjectId: TransportDecisionSubjectId,
        override val registrySequence: OpportunityRegistrySequence,
        val opportunity: TransportOpportunityReference,
        val candidateIndex: Int,
    ) : TransportDecision {
        init {
            require(candidateIndex >= 0) { "Selected candidate index must not be negative" }
            require(candidateIndex < TransportDecisionLimits.MAX_CANDIDATE_REFERENCES) {
                "Selected candidate index exceeds the candidate limit"
            }
        }
    }
}

enum class TransportWaitReason {
    NO_CANDIDATES,
    NO_USABLE_CANDIDATE,
}

/**
 * Minimal, stateless selector. It never starts adapters, performs I/O, or owns
 * routing, delivery persistence, retry, evidence, or transfer execution.
 */
class MinimalTransportDecisionEngine {
    fun decide(
        snapshot: OpportunityRegistrySnapshot,
        request: TransportDecisionRequest,
    ): TransportDecision {
        if (request.candidates.isEmpty()) {
            return TransportDecision.Wait(
                subjectId = request.subjectId,
                registrySequence = snapshot.sequence,
                reason = TransportWaitReason.NO_CANDIDATES,
                rejectedCandidates = emptyList(),
            )
        }

        val rejections = ArrayList<TransportCandidateRejection>(request.candidates.size)
        request.candidates.forEachIndexed { index, candidate ->
            val current = snapshot[candidate.key]
            val rejection = when {
                current == null -> TransportCandidateRejectionReason.NO_CURRENT_OPPORTUNITY
                current.reference != candidate -> TransportCandidateRejectionReason.SUPERSEDED
                !current.isFreshAt(request.nowMs) -> TransportCandidateRejectionReason.STALE
                !current.direction.isSendKnown ->
                    TransportCandidateRejectionReason.NOT_SEND_CAPABLE

                else -> {
                    return TransportDecision.Transfer(
                        subjectId = request.subjectId,
                        registrySequence = snapshot.sequence,
                        opportunity = candidate,
                        candidateIndex = index,
                    )
                }
            }
            rejections += TransportCandidateRejection(candidate, rejection)
        }

        return TransportDecision.Wait(
            subjectId = request.subjectId,
            registrySequence = snapshot.sequence,
            reason = TransportWaitReason.NO_USABLE_CANDIDATE,
            rejectedCandidates = Collections.unmodifiableList(rejections),
        )
    }
}
