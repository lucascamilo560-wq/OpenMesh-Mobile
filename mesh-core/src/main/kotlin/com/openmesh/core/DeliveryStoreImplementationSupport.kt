package com.openmesh.core

/**
 * Narrow support surface for persistent [DeliveryStore] implementations.
 *
 * This object does not create delivery evidence. It only lets an implementation
 * issue an opaque lease, compare that lease with its own persisted secret, and
 * persist a receipt already carried by verified protocol/security authority.
 */
object DeliveryStoreImplementationSupport {
    fun issueTransferLease(
        attemptId: TransferAttemptId,
        expiresAtMs: Long,
        ownerToken: String,
    ): TransferLease {
        require(ownerToken.isNotBlank()) { "Transfer lease owner token must not be blank" }
        return TransferLease(attemptId, expiresAtMs, ownerToken)
    }

    fun leaseMatchesOwnerToken(lease: TransferLease, ownerToken: String): Boolean =
        ownerToken.isNotBlank() && lease.ownerToken == ownerToken

    fun verifiedReceipt(acceptance: VerifiedNextHopAcceptance): ReceiptInput? =
        acceptance.receipt
}
