package com.openmesh.core

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

fun interface PersistentTransportExecutionClock {
    fun nowMs(): Long
}

fun interface PersistentTransportTransferIdGenerator {
    fun nextId(): TransportTransferId
}

/** Stable, bounded reasons persisted on failed transfer attempts. */
enum class PersistentTransportFailureReason {
    OPPORTUNITY_UNAVAILABLE,
    TRANSPORT_LOCAL_ADAPTER_STOPPED,
    TRANSPORT_LOCAL_PERMISSION_DENIED,
    TRANSPORT_LOCAL_TIMED_OUT,
    TRANSPORT_LOCAL_IO_ERROR,
    TRANSPORT_LOCAL_RESOURCE_LIMIT,
    TRANSPORT_LOCAL_UNSUPPORTED,
    TRANSPORT_LOCAL_INVALID_REQUEST,
    TRANSPORT_LOCAL_UNKNOWN,
    ADAPTER_CONTRACT_TRANSFER_ID_MISMATCH,
    ADAPTER_CONTRACT_OPPORTUNITY_MISMATCH,
    ADAPTER_EXCEPTION,
    EXECUTION_TIMED_OUT,
    EXECUTION_CANCELLED,
}

sealed interface PersistentLocalTransportOutcome {
    data object CompletedLocally : PersistentLocalTransportOutcome
    data object OpportunityUnavailable : PersistentLocalTransportOutcome
    data class FailedLocally(
        val code: TransportLocalFailureCode,
    ) : PersistentLocalTransportOutcome

    data class AdapterContractViolation(
        val reason: PersistentTransportFailureReason,
    ) : PersistentLocalTransportOutcome

    data object AdapterThrew : PersistentLocalTransportOutcome
    data object ExecutionTimedOut : PersistentLocalTransportOutcome
    data object Cancelled : PersistentLocalTransportOutcome
}

/**
 * Local capability binding one policy/decision subject to one delivery object.
 *
 * The association is created before decision evaluation and is never inferred
 * by the executor from either side independently.
 */
class DeliveryTransportDecisionBinding private constructor(
    val deliveryId: DeliveryId,
    val subjectId: TransportDecisionSubjectId,
) {
    companion object {
        fun bind(
            deliveryId: DeliveryId,
            subjectId: TransportDecisionSubjectId,
        ): DeliveryTransportDecisionBinding = DeliveryTransportDecisionBinding(
            deliveryId = deliveryId,
            subjectId = subjectId,
        )
    }
}

/** Result of one decision execution. None of these variants is delivery evidence. */
sealed interface PersistentTransportExecutionResult {
    val registrySequence: OpportunityRegistrySequence

    data class Wait(
        val decision: TransportDecision.Wait,
    ) : PersistentTransportExecutionResult {
        override val registrySequence: OpportunityRegistrySequence
            get() = decision.registrySequence
    }

    data class SubjectMismatch(
        override val registrySequence: OpportunityRegistrySequence,
        val bindingSubjectId: TransportDecisionSubjectId,
        val decisionSubjectId: TransportDecisionSubjectId,
    ) : PersistentTransportExecutionResult

    data class AdapterUnavailable(
        override val registrySequence: OpportunityRegistrySequence,
        val adapterId: TransportAdapterId,
    ) : PersistentTransportExecutionResult

    data class DeliveryObjectUnavailable(
        override val registrySequence: OpportunityRegistrySequence,
        val deliveryId: DeliveryId,
    ) : PersistentTransportExecutionResult

    data class PayloadNotTransferable(
        override val registrySequence: OpportunityRegistrySequence,
        val byteCount: Int,
        val maximumByteCount: Int,
    ) : PersistentTransportExecutionResult

    data class NotEligible(
        override val registrySequence: OpportunityRegistrySequence,
    ) : PersistentTransportExecutionResult

    data class Busy(
        override val registrySequence: OpportunityRegistrySequence,
        val attempt: TransferAttempt,
    ) : PersistentTransportExecutionResult

    data class LinkWriteCompleted(
        override val registrySequence: OpportunityRegistrySequence,
        val attempt: TransferAttempt,
        val transferId: TransportTransferId,
    ) : PersistentTransportExecutionResult

    data class Failed(
        override val registrySequence: OpportunityRegistrySequence,
        val attempt: TransferAttempt,
        val failureReason: PersistentTransportFailureReason,
        val localOutcome: PersistentLocalTransportOutcome,
    ) : PersistentTransportExecutionResult
}

/** Adapter I/O threw after the attempt had already entered TRANSFERRING. */
class TransportAdapterExecutionException(
    val attemptId: TransferAttemptId,
    cause: Throwable,
) : IllegalStateException("Transport adapter execution failed for $attemptId", cause)

/**
 * The adapter produced a local outcome, but the store could not confirm its
 * corresponding attempt transition. The local outcome is not durable evidence.
 */
class TransportExecutionSettlementException(
    val attemptId: TransferAttemptId,
    val localOutcome: PersistentLocalTransportOutcome,
    cause: Throwable,
) : IllegalStateException(
    "Persistent settlement failed for transfer attempt $attemptId after $localOutcome",
    cause,
)

/**
 * Executes exactly one already-made transport decision against persistent state.
 *
 * It does not decide, retry, select fallbacks, parse bytes, or promote any
 * next-hop/application evidence. Its strongest successful state is strictly
 * [TransferAttemptState.LINK_WRITE_COMPLETED].
 */
class PersistentTransportDecisionExecutor(
    private val deliveryStore: DeliveryStore,
    private val adapters: TransportAdapterSet,
    private val clock: PersistentTransportExecutionClock,
    private val transferIdGenerator: PersistentTransportTransferIdGenerator,
    private val leaseDurationMs: Long = DEFAULT_LEASE_DURATION_MS,
    private val executionTimeoutMs: Long = DEFAULT_EXECUTION_TIMEOUT_MS,
    private val settlementMarginMs: Long = DEFAULT_SETTLEMENT_MARGIN_MS,
) {
    init {
        require(leaseDurationMs > 0) { "Transport execution lease must be positive" }
        require(leaseDurationMs <= MAX_LEASE_DURATION_MS) {
            "Transport execution lease exceeds $MAX_LEASE_DURATION_MS ms"
        }
        require(executionTimeoutMs > 0) {
            "Transport execution timeout must be positive"
        }
        require(settlementMarginMs > 0) {
            "Transport execution settlement margin must be positive"
        }
        require(settlementMarginMs < leaseDurationMs) {
            "Transport execution settlement margin must be shorter than the lease"
        }
        require(executionTimeoutMs < leaseDurationMs - settlementMarginMs) {
            "Transport execution timeout plus settlement margin must be shorter than the lease"
        }
    }

    constructor(
        deliveryStore: DeliveryStore,
        adapters: TransportAdapterSet,
        leaseDurationMs: Long = DEFAULT_LEASE_DURATION_MS,
        executionTimeoutMs: Long = DEFAULT_EXECUTION_TIMEOUT_MS,
        settlementMarginMs: Long = DEFAULT_SETTLEMENT_MARGIN_MS,
    ) : this(
        deliveryStore = deliveryStore,
        adapters = adapters,
        clock = PersistentTransportExecutionClock(System::currentTimeMillis),
        transferIdGenerator = PersistentTransportTransferIdGenerator {
            TransportTransferId("transport-${UUID.randomUUID()}")
        },
        leaseDurationMs = leaseDurationMs,
        executionTimeoutMs = executionTimeoutMs,
        settlementMarginMs = settlementMarginMs,
    )

    suspend fun execute(
        binding: DeliveryTransportDecisionBinding,
        decision: TransportDecision,
    ): PersistentTransportExecutionResult {
        if (decision.subjectId != binding.subjectId) {
            return PersistentTransportExecutionResult.SubjectMismatch(
                registrySequence = decision.registrySequence,
                bindingSubjectId = binding.subjectId,
                decisionSubjectId = decision.subjectId,
            )
        }
        if (decision is TransportDecision.Wait) {
            return PersistentTransportExecutionResult.Wait(decision)
        }
        decision as TransportDecision.Transfer

        val adapter = adapters[decision.opportunity.adapterId]
            ?: return PersistentTransportExecutionResult.AdapterUnavailable(
                registrySequence = decision.registrySequence,
                adapterId = decision.opportunity.adapterId,
            )

        val deliveryObject = deliveryStore.snapshot(binding.deliveryId).deliveryObject
            ?: return PersistentTransportExecutionResult.DeliveryObjectUnavailable(
                registrySequence = decision.registrySequence,
                deliveryId = binding.deliveryId,
            )
        val canonicalBytes = deliveryObject.canonicalBytes.copyToByteArray()
        if (
            canonicalBytes.isEmpty() ||
            canonicalBytes.size > TransportContractLimits.MAX_OPAQUE_BYTES
        ) {
            return PersistentTransportExecutionResult.PayloadNotTransferable(
                registrySequence = decision.registrySequence,
                byteCount = canonicalBytes.size,
                maximumByteCount = TransportContractLimits.MAX_OPAQUE_BYTES,
            )
        }

        val request = TransportTransferRequest.copyOf(
            transferId = transferIdGenerator.nextId(),
            opportunity = decision.opportunity,
            bytes = canonicalBytes,
        )
        val reservation = deliveryStore.reserveTransfer(
            reservation = TransferReservation(
                deliveryId = binding.deliveryId,
                context = TransferContext.forOpportunity(decision.opportunity),
                leaseDurationMs = leaseDurationMs,
            ),
            nowMs = nowMs(),
        )
        when (reservation) {
            TransferReservationResult.NotEligible ->
                return PersistentTransportExecutionResult.NotEligible(
                    decision.registrySequence,
                )

            is TransferReservationResult.Busy ->
                return PersistentTransportExecutionResult.Busy(
                    registrySequence = decision.registrySequence,
                    attempt = reservation.attempt,
                )

            is TransferReservationResult.Acquired -> Unit
        }
        reservation as TransferReservationResult.Acquired

        deliveryStore.markTransferStarted(reservation.lease, nowMs())

        val beforeAdapterMs = nowMs()
        val latestAdapterCompletionMs = reservation.lease.expiresAtMs - settlementMarginMs
        val leaseBoundExecutionTimeoutMs = latestAdapterCompletionMs - beforeAdapterMs
        if (leaseBoundExecutionTimeoutMs <= 0) {
            val outcome = PersistentLocalTransportOutcome.ExecutionTimedOut
            val reason = PersistentTransportFailureReason.EXECUTION_TIMED_OUT
            return PersistentTransportExecutionResult.Failed(
                registrySequence = decision.registrySequence,
                attempt = settleFailure(reservation.lease, reason, outcome),
                failureReason = reason,
                localOutcome = outcome,
            )
        }
        val effectiveExecutionTimeoutMs = minOf(
            executionTimeoutMs,
            leaseBoundExecutionTimeoutMs,
        )

        val deadlineCall = try {
            executeBeforeDeadline(effectiveExecutionTimeoutMs) {
                adapter.transfer(request)
            }
        } catch (cancelled: CancellationException) {
            try {
                withContext(NonCancellable) {
                    settleFailure(
                        lease = reservation.lease,
                        reason = PersistentTransportFailureReason.EXECUTION_CANCELLED,
                        outcome = PersistentLocalTransportOutcome.Cancelled,
                    )
                }
            } catch (settlementFailure: Throwable) {
                cancelled.addSuppressed(settlementFailure)
            }
            throw cancelled
        } catch (failure: Throwable) {
            val outcome = PersistentLocalTransportOutcome.AdapterThrew
            try {
                settleFailure(
                    lease = reservation.lease,
                    reason = PersistentTransportFailureReason.ADAPTER_EXCEPTION,
                    outcome = outcome,
                )
            } catch (settlementFailure: Throwable) {
                settlementFailure.addSuppressed(failure)
                throw settlementFailure
            }
            throw TransportAdapterExecutionException(reservation.attempt.attemptId, failure)
        }
        if (deadlineCall is AdapterDeadlineCall.TimedOut) {
            val outcome = PersistentLocalTransportOutcome.ExecutionTimedOut
            val reason = PersistentTransportFailureReason.EXECUTION_TIMED_OUT
            return PersistentTransportExecutionResult.Failed(
                registrySequence = decision.registrySequence,
                attempt = settleFailure(reservation.lease, reason, outcome),
                failureReason = reason,
                localOutcome = outcome,
            )
        }
        deadlineCall as AdapterDeadlineCall.Completed
        val adapterResult = deadlineCall.result

        val transferIdMismatch = adapterResult.transferId != request.transferId
        if (transferIdMismatch) {
            return failContract(
                decision = decision,
                lease = reservation.lease,
                reason = PersistentTransportFailureReason.ADAPTER_CONTRACT_TRANSFER_ID_MISMATCH,
            )
        }
        if (
            adapterResult is TransportTransferResult.OpportunityUnavailable &&
            adapterResult.opportunity != request.opportunity
        ) {
            return failContract(
                decision = decision,
                lease = reservation.lease,
                reason = PersistentTransportFailureReason.ADAPTER_CONTRACT_OPPORTUNITY_MISMATCH,
            )
        }

        return when (adapterResult) {
            is TransportTransferResult.CompletedLocally -> {
                val outcome = PersistentLocalTransportOutcome.CompletedLocally
                val attempt = settleLinkWrite(reservation.lease, outcome)
                PersistentTransportExecutionResult.LinkWriteCompleted(
                    registrySequence = decision.registrySequence,
                    attempt = attempt,
                    transferId = request.transferId,
                )
            }

            is TransportTransferResult.OpportunityUnavailable -> {
                val outcome = PersistentLocalTransportOutcome.OpportunityUnavailable
                val reason = PersistentTransportFailureReason.OPPORTUNITY_UNAVAILABLE
                PersistentTransportExecutionResult.Failed(
                    registrySequence = decision.registrySequence,
                    attempt = settleFailure(reservation.lease, reason, outcome),
                    failureReason = reason,
                    localOutcome = outcome,
                )
            }

            is TransportTransferResult.FailedLocally -> {
                val outcome = PersistentLocalTransportOutcome.FailedLocally(
                    adapterResult.failure.code,
                )
                val reason = adapterResult.failure.code.toPersistentReason()
                PersistentTransportExecutionResult.Failed(
                    registrySequence = decision.registrySequence,
                    attempt = settleFailure(reservation.lease, reason, outcome),
                    failureReason = reason,
                    localOutcome = outcome,
                )
            }
        }
    }

    private suspend fun failContract(
        decision: TransportDecision.Transfer,
        lease: TransferLease,
        reason: PersistentTransportFailureReason,
    ): PersistentTransportExecutionResult.Failed {
        val outcome = PersistentLocalTransportOutcome.AdapterContractViolation(reason)
        return PersistentTransportExecutionResult.Failed(
            registrySequence = decision.registrySequence,
            attempt = settleFailure(lease, reason, outcome),
            failureReason = reason,
            localOutcome = outcome,
        )
    }

    private suspend fun settleLinkWrite(
        lease: TransferLease,
        outcome: PersistentLocalTransportOutcome,
    ): TransferAttempt = try {
        deliveryStore.recordLinkWriteCompleted(lease, nowMs())
    } catch (failure: Throwable) {
        throw TransportExecutionSettlementException(lease.attemptId, outcome, failure)
    }

    private suspend fun settleFailure(
        lease: TransferLease,
        reason: PersistentTransportFailureReason,
        outcome: PersistentLocalTransportOutcome,
    ): TransferAttempt = try {
        deliveryStore.recordTransferFailure(
            lease = lease,
            reason = reason.name,
            nowMs = nowMs(),
        )
    } catch (failure: Throwable) {
        throw TransportExecutionSettlementException(lease.attemptId, outcome, failure)
    }

    private fun nowMs(): Long = clock.nowMs().also {
        require(it >= 0) { "Transport execution clock returned a negative timestamp" }
    }

    private suspend fun executeBeforeDeadline(
        timeoutMs: Long,
        transfer: suspend () -> TransportTransferResult,
    ): AdapterDeadlineCall = coroutineScope {
        val operation = async(start = CoroutineStart.UNDISPATCHED) { transfer() }
        val timeout = async {
            delay(timeoutMs)
            Unit
        }
        try {
            select {
                operation.onAwait { AdapterDeadlineCall.Completed(it) }
                timeout.onAwait {
                    operation.cancel()
                    AdapterDeadlineCall.TimedOut
                }
            }
        } finally {
            timeout.cancel()
            if (!operation.isCompleted) operation.cancel()
        }
    }

    companion object {
        const val DEFAULT_LEASE_DURATION_MS = 40_000L
        const val DEFAULT_EXECUTION_TIMEOUT_MS = 30_000L
        const val DEFAULT_SETTLEMENT_MARGIN_MS = 5_000L
        const val MAX_LEASE_DURATION_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}

private sealed interface AdapterDeadlineCall {
    data class Completed(val result: TransportTransferResult) : AdapterDeadlineCall
    data object TimedOut : AdapterDeadlineCall
}

private fun TransportLocalFailureCode.toPersistentReason(): PersistentTransportFailureReason =
    when (this) {
        TransportLocalFailureCode.ADAPTER_STOPPED ->
            PersistentTransportFailureReason.TRANSPORT_LOCAL_ADAPTER_STOPPED

        TransportLocalFailureCode.PERMISSION_DENIED ->
            PersistentTransportFailureReason.TRANSPORT_LOCAL_PERMISSION_DENIED

        TransportLocalFailureCode.TIMED_OUT ->
            PersistentTransportFailureReason.TRANSPORT_LOCAL_TIMED_OUT

        TransportLocalFailureCode.IO_ERROR ->
            PersistentTransportFailureReason.TRANSPORT_LOCAL_IO_ERROR

        TransportLocalFailureCode.RESOURCE_LIMIT ->
            PersistentTransportFailureReason.TRANSPORT_LOCAL_RESOURCE_LIMIT

        TransportLocalFailureCode.UNSUPPORTED ->
            PersistentTransportFailureReason.TRANSPORT_LOCAL_UNSUPPORTED

        TransportLocalFailureCode.INVALID_REQUEST ->
            PersistentTransportFailureReason.TRANSPORT_LOCAL_INVALID_REQUEST

        TransportLocalFailureCode.UNKNOWN ->
            PersistentTransportFailureReason.TRANSPORT_LOCAL_UNKNOWN
    }
