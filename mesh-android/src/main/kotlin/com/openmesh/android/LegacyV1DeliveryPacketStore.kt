package com.openmesh.android

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.openmesh.core.CanonicalDeliveryConflictException
import com.openmesh.core.DeliveryId
import com.openmesh.core.DeliveryIngest
import com.openmesh.core.DeliveryIngestResult
import com.openmesh.core.DeliveryState
import com.openmesh.core.IngressProvenance
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshEnvelopeCodec
import com.openmesh.core.NodeId
import com.openmesh.core.PacketStore
import com.openmesh.core.TombstoneReason

/**
 * Narrow v1 [PacketStore] view backed by the transactional delivery database.
 *
 * Construction is internal so production callers cannot bypass the cutover
 * coordinator. The view recognizes only objects carrying migration manifest
 * membership or the reserved runtime-v1 provenance. Future DeliveryObjects in
 * the same database are never decoded as [MeshEnvelope].
 */
class LegacyV1DeliveryPacketStore internal constructor(
    private val store: AndroidDeliveryStore,
    val localNodeId: NodeId,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tombstoneReplayGuardMs: Long = DEFAULT_REPLAY_GUARD_MS,
    private val outboxSettlementHooks: LegacyV1OutboxSettlementHooks =
        LegacyV1OutboxSettlementHooks(),
) : PacketStore {

    init {
        requireLegacyV1ReplayGuard(tombstoneReplayGuardMs)
    }

    override suspend fun contains(packetId: String): Boolean {
        val deliveryId = runCatching { DeliveryId(packetId) }.getOrNull() ?: return false
        return store.readForLegacyV1Runtime { database ->
            hasRecognizedV1Record(database, deliveryId)
        }
    }

    override suspend fun put(packet: MeshEnvelope) {
        require(packet.protocolVersion == MeshEnvelope.CURRENT_PROTOCOL_VERSION) {
            "Legacy v1 packet store accepts only protocol v1"
        }
        val canonicalBytes = MeshEnvelopeCodec.encode(packet)
        val deliveryObject = legacyV1DeliveryObject(packet, canonicalBytes)
        val provenance = runtimeV1Provenance(packet, localNodeId)
        val nowMs = clock()
        settleRecognizedV1Outbox(nowMs)
        pruneExpiredTombstones(nowMs)

        store.writeForLegacyV1Runtime { transaction ->
            val database = transaction.database
            if (
                hasDeliveryRecord(database, deliveryObject.deliveryId) &&
                !hasRecognizedV1Record(database, deliveryObject.deliveryId)
            ) {
                throw CanonicalDeliveryConflictException(deliveryObject.deliveryId)
            }

            if (packet.expiresAtMs <= nowMs) {
                store.storeExpiredLegacyV1Delivery(
                    transaction = transaction,
                    deliveryObject = deliveryObject,
                    provenance = provenance,
                    nowMs = nowMs,
                    tombstoneExpiresAtMs = legacyV1TombstoneExpiresAt(
                        packetExpiresAtMs = packet.expiresAtMs,
                        nowMs = nowMs,
                        replayGuardMs = tombstoneReplayGuardMs,
                    ),
                )
                return@writeForLegacyV1Runtime
            }

            when (legacyV1Disposition(packet, localNodeId)) {
                LegacyDeliveryDisposition.LOCAL_QUARANTINED ->
                    store.storeQuarantinedLegacyV1Delivery(
                        transaction = transaction,
                        deliveryObject = deliveryObject,
                        provenance = provenance,
                        nowMs = nowMs,
                    )

                LegacyDeliveryDisposition.REMOTE_FORWARDABLE,
                LegacyDeliveryDisposition.BROADCAST_FORWARDABLE -> when (
                    store.ingestForLegacyV1Runtime(
                        transaction = transaction,
                        ingest = DeliveryIngest(
                            deliveryObject = deliveryObject,
                            destinationIsLocal = false,
                            provenance = provenance,
                        ),
                        nowMs = nowMs,
                    )
                ) {
                    is DeliveryIngestResult.RejectedByTombstone ->
                        throw LegacyV1PacketStoreException(
                            "Retained tombstone blocks v1 packet ${packet.packetId}"
                        )

                    else -> Unit
                }
            }
        }
        outboxSettlementHooks.afterMutationCommittedBeforeSettlement()
        settleRecognizedV1Outbox(nowMs)
    }

    override suspend fun remove(packetId: String) {
        val deliveryId = runCatching { DeliveryId(packetId) }.getOrNull() ?: return
        val nowMs = clock()
        settleRecognizedV1Outbox(nowMs)
        pruneExpiredTombstones(nowMs)
        store.writeForLegacyV1Runtime { transaction ->
            val row = loadRecognizedV1Object(transaction.database, deliveryId) ?: return@writeForLegacyV1Runtime
            val envelope = row.decodeAndValidate(localNodeId, permitLocal = true)
            store.storeExpiredLegacyV1Delivery(
                transaction = transaction,
                deliveryObject = legacyV1DeliveryObject(envelope, row.canonicalBytes),
                provenance = runtimeV1Provenance(envelope, localNodeId),
                nowMs = nowMs,
                tombstoneReason = TombstoneReason.EXPLICITLY_REMOVED,
                tombstoneExpiresAtMs = legacyV1TombstoneExpiresAt(
                    packetExpiresAtMs = envelope.expiresAtMs,
                    nowMs = nowMs,
                    replayGuardMs = tombstoneReplayGuardMs,
                ),
                emitExpiredEvent = false,
            )
        }
        outboxSettlementHooks.afterMutationCommittedBeforeSettlement()
        settleRecognizedV1Outbox(nowMs)
    }

    /**
     * Only WAITING v1 objects are exposed to the forwarding router. A local
     * legacy unicast remains visible to contains() for deduplication but is
     * hidden here because QUARANTINED is never forwarding authority.
     */
    override suspend fun list(): List<MeshEnvelope> = store.readForLegacyV1Runtime { database ->
        loadRecognizedV1Objects(database, state = DeliveryState.WAITING)
            .map { row -> row.decodeAndValidate(localNodeId, permitLocal = false) }
    }

    override suspend fun purgeExpired(nowMs: Long): Int {
        settleRecognizedV1Outbox(nowMs)
        pruneExpiredTombstones(nowMs)
        val expiredCount = store.writeForLegacyV1Runtime { transaction ->
            val expired = loadExpiredRecognizedV1Objects(transaction.database, nowMs)
            expired.forEach { row ->
                val envelope = row.decodeAndValidate(localNodeId, permitLocal = true)
                store.storeExpiredLegacyV1Delivery(
                    transaction = transaction,
                    deliveryObject = legacyV1DeliveryObject(envelope, row.canonicalBytes),
                    provenance = runtimeV1Provenance(envelope, localNodeId),
                    nowMs = nowMs,
                    tombstoneExpiresAtMs = legacyV1TombstoneExpiresAt(
                        packetExpiresAtMs = envelope.expiresAtMs,
                        nowMs = nowMs,
                        replayGuardMs = tombstoneReplayGuardMs,
                    ),
                )
            }
            expired.size
        }
        outboxSettlementHooks.afterMutationCommittedBeforeSettlement()
        settleRecognizedV1Outbox(nowMs)
        pruneExpiredTombstones(nowMs)
        return expiredCount
    }

    /**
     * Settles only compatibility-runtime events while their persisted v1
     * provenance is still available. Acknowledgement and pruning are separate
     * commits, so a crash between them is recovered by the next invocation.
     */
    internal suspend fun settleRecognizedV1Outbox(nowMs: Long): Int {
        val batchSize = store.configuredLimits.maxOutboxReadBatch
        var totalPruned = 0
        do {
            val acknowledged = store.acknowledgeRecognizedLegacyV1Outbox(
                nowMs = nowMs,
                limit = batchSize,
            )
            if (acknowledged > 0) {
                outboxSettlementHooks.afterAcknowledgementCommittedBeforePrune()
            }
            val pruned = store.pruneAcknowledgedRecognizedLegacyV1Outbox(
                beforeOrAtMs = nowMs,
                limit = batchSize,
            )
            totalPruned += pruned
        } while (acknowledged == batchSize || pruned == batchSize)
        return totalPruned
    }

    internal suspend fun pruneExpiredTombstones(nowMs: Long): Int {
        val batchSize = store.configuredLimits.maxOutboxReadBatch
        var total = 0
        do {
            val pruned = store.pruneExpiredTombstones(nowMs, batchSize)
            total += pruned
        } while (pruned == batchSize)
        return total
    }

    companion object {
        const val DEFAULT_REPLAY_GUARD_MS = BleMeshNode.DEFAULT_TTL_MS
        const val MAX_REPLAY_GUARD_MS = 30L * 24L * 60L * 60L * 1000L
        internal const val RUNTIME_V1_PROVENANCE = "legacy-v1-runtime:mesh-envelope-v1"
    }
}

internal fun requireLegacyV1ReplayGuard(replayGuardMs: Long) {
    require(replayGuardMs in 1..LegacyV1DeliveryPacketStore.MAX_REPLAY_GUARD_MS) {
        "Legacy v1 replay guard must be between 1 and " +
            "${LegacyV1DeliveryPacketStore.MAX_REPLAY_GUARD_MS} ms"
    }
}

internal fun legacyV1TombstoneExpiresAt(
    packetExpiresAtMs: Long,
    nowMs: Long,
    replayGuardMs: Long,
): Long {
    requireLegacyV1ReplayGuard(replayGuardMs)
    val retentionAnchor = maxOf(packetExpiresAtMs, nowMs)
    if (retentionAnchor > Long.MAX_VALUE - replayGuardMs) {
        throw LegacyV1PacketStoreException(
            "Legacy v1 tombstone expiry exceeds the supported timestamp range"
        )
    }
    return retentionAnchor + replayGuardMs
}

class LegacyV1PacketStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal data class LegacyV1OutboxSettlementHooks(
    val afterMutationCommittedBeforeSettlement: () -> Unit = {},
    val afterAcknowledgementCommittedBeforePrune: () -> Unit = {},
)

private data class LegacyV1ObjectRow(
    val deliveryId: DeliveryId,
    val destination: String,
    val source: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val canonicalBytes: ByteArray,
) {
    fun decodeAndValidate(localNodeId: NodeId, permitLocal: Boolean): MeshEnvelope {
        val envelope = try {
            MeshEnvelopeCodec.decode(canonicalBytes)
        } catch (failure: Exception) {
            throw LegacyV1PacketStoreException(
                "Recognized v1 object $deliveryId cannot be decoded",
                failure,
            )
        }
        val canonical = MeshEnvelopeCodec.encode(envelope)
        val expected = legacyV1DeliveryObject(envelope, canonicalBytes)
        if (
            envelope.protocolVersion != MeshEnvelope.CURRENT_PROTOCOL_VERSION ||
            envelope.packetId != deliveryId.value ||
            !canonical.contentEquals(canonicalBytes) ||
            expected.destination.value != destination ||
            expected.source?.value != source ||
            envelope.createdAtMs != createdAtMs ||
            envelope.expiresAtMs != expiresAtMs
        ) {
            throw LegacyV1PacketStoreException(
                "Recognized v1 object $deliveryId fails canonical validation"
            )
        }
        if (
            !permitLocal &&
            legacyV1Disposition(envelope, localNodeId) ==
            LegacyDeliveryDisposition.LOCAL_QUARANTINED
        ) {
            throw LegacyV1PacketStoreException(
                "Local v1 object $deliveryId cannot be exposed for forwarding"
            )
        }
        return envelope
    }
}

private fun runtimeV1Provenance(
    envelope: MeshEnvelope,
    localNodeId: NodeId,
): IngressProvenance = IngressProvenance(
    kind = if (envelope.sourceNodeId == localNodeId.value) {
        IngressProvenance.Kind.LOCAL_APPLICATION
    } else {
        IngressProvenance.Kind.REMOTE_PROTOCOL
    },
    reference = LegacyV1DeliveryPacketStore.RUNTIME_V1_PROVENANCE,
)

private fun hasDeliveryRecord(database: SQLiteDatabase, deliveryId: DeliveryId): Boolean =
    database.rawQuery(
        "SELECT 1 FROM ${DeliverySchema.DELIVERY_RECORDS} WHERE delivery_id = ? LIMIT 1",
        arrayOf(deliveryId.value),
    ).use(Cursor::moveToFirst)

private fun hasRecognizedV1Record(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): Boolean = database.rawQuery(
    """
    SELECT 1
    FROM ${DeliverySchema.DELIVERY_RECORDS} records
    WHERE records.delivery_id = ?
      AND ${recognizedV1RecordPredicate("records.delivery_id")}
    LIMIT 1
    """.trimIndent(),
    arrayOf(deliveryId.value, LegacyV1DeliveryPacketStore.RUNTIME_V1_PROVENANCE),
).use(Cursor::moveToFirst)

private suspend fun AndroidDeliveryStore.acknowledgeRecognizedLegacyV1Outbox(
    nowMs: Long,
    limit: Int,
): Int = writeForLegacyV1Runtime { transaction ->
    require(limit in 1..configuredLimits.maxOutboxReadBatch)
    val eventIds = loadRecognizedLegacyV1OutboxEventIds(
        database = transaction.database,
        acknowledged = false,
        beforeOrAtMs = null,
        limit = limit,
    )
    if (eventIds.isEmpty()) return@writeForLegacyV1Runtime 0
    val values = ContentValues().apply { put("acknowledged_at_ms", nowMs) }
    eventIds.forEach { eventId ->
        check(
            transaction.database.update(
                DeliverySchema.OUTBOX,
                values,
                "event_id = ? AND acknowledged_at_ms IS NULL",
                arrayOf(eventId),
            ) == 1
        )
    }
    transaction.markChanged()
    eventIds.size
}

private suspend fun AndroidDeliveryStore.pruneAcknowledgedRecognizedLegacyV1Outbox(
    beforeOrAtMs: Long,
    limit: Int,
): Int = writeForLegacyV1Runtime { transaction ->
    require(limit in 1..configuredLimits.maxOutboxReadBatch)
    val eventIds = loadRecognizedLegacyV1OutboxEventIds(
        database = transaction.database,
        acknowledged = true,
        beforeOrAtMs = beforeOrAtMs,
        limit = limit,
    )
    eventIds.forEach { eventId ->
        check(
            transaction.database.delete(
                DeliverySchema.OUTBOX,
                "event_id = ?",
                arrayOf(eventId),
            ) == 1
        )
    }
    if (eventIds.isNotEmpty()) transaction.markChanged()
    eventIds.size
}

private fun loadRecognizedLegacyV1OutboxEventIds(
    database: SQLiteDatabase,
    acknowledged: Boolean,
    beforeOrAtMs: Long?,
    limit: Int,
): List<String> {
    check(acknowledged == (beforeOrAtMs != null))
    val acknowledgementClause = if (acknowledged) {
        "outbox.acknowledged_at_ms IS NOT NULL AND outbox.acknowledged_at_ms <= ?"
    } else {
        "outbox.acknowledged_at_ms IS NULL"
    }
    val arguments = buildList {
        if (beforeOrAtMs != null) add(beforeOrAtMs.toString())
        add(LEGACY_V1_OUTBOX_DURABLY_STORED)
        add(LEGACY_V1_OUTBOX_DELIVERY_EXPIRED)
        add(LegacyV1DeliveryPacketStore.RUNTIME_V1_PROVENANCE)
        add(limit.toString())
    }.toTypedArray()
    return database.rawQuery(
        """
        SELECT outbox.event_id
        FROM ${DeliverySchema.OUTBOX} outbox
        JOIN ${DeliverySchema.DELIVERY_RECORDS} records
          ON records.delivery_id = outbox.delivery_id
        WHERE $acknowledgementClause
          AND outbox.event_type IN (?, ?)
          AND ${recognizedV1RecordPredicate("records.delivery_id")}
        ORDER BY outbox.sequence
        LIMIT ?
        """.trimIndent(),
        arguments,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }
}

private fun loadRecognizedV1Object(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): LegacyV1ObjectRow? = database.rawQuery(
    """
    ${selectV1ObjectSql()}
    WHERE objects.delivery_id = ?
      AND ${recognizedV1ObjectPredicate("objects.delivery_id")}
    LIMIT 1
    """.trimIndent(),
    arrayOf(deliveryId.value, LegacyV1DeliveryPacketStore.RUNTIME_V1_PROVENANCE),
).use { cursor ->
    if (!cursor.moveToFirst()) null else cursor.toLegacyV1ObjectRow()
}

private fun loadRecognizedV1Objects(
    database: SQLiteDatabase,
    state: DeliveryState,
): List<LegacyV1ObjectRow> = database.rawQuery(
    """
    ${selectV1ObjectSql()}
    WHERE records.state = ?
      AND ${recognizedV1ObjectPredicate("objects.delivery_id")}
    ORDER BY objects.created_at_ms, objects.delivery_id
    """.trimIndent(),
    arrayOf(state.name, LegacyV1DeliveryPacketStore.RUNTIME_V1_PROVENANCE),
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(cursor.toLegacyV1ObjectRow())
    }
}

private fun loadExpiredRecognizedV1Objects(
    database: SQLiteDatabase,
    nowMs: Long,
): List<LegacyV1ObjectRow> = database.rawQuery(
    """
    ${selectV1ObjectSql()}
    WHERE objects.expires_at_ms <= ?
      AND records.state IN (?, ?)
      AND ${recognizedV1ObjectPredicate("objects.delivery_id")}
    ORDER BY objects.delivery_id
    """.trimIndent(),
    arrayOf(
        nowMs.toString(),
        DeliveryState.WAITING.name,
        DeliveryState.QUARANTINED.name,
        LegacyV1DeliveryPacketStore.RUNTIME_V1_PROVENANCE,
    ),
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(cursor.toLegacyV1ObjectRow())
    }
}

private fun selectV1ObjectSql(): String = """
    SELECT objects.delivery_id,
           objects.destination,
           objects.source,
           objects.created_at_ms,
           objects.expires_at_ms,
           objects.canonical_bytes
    FROM ${DeliverySchema.DELIVERY_OBJECTS} objects
    JOIN ${DeliverySchema.DELIVERY_RECORDS} records
      ON records.delivery_id = objects.delivery_id
""".trimIndent()

private fun recognizedV1ObjectPredicate(deliveryIdExpression: String): String = """
    (
        EXISTS (
            SELECT 1
            FROM ${DeliverySchema.LEGACY_IMPORT_ITEMS} imported
            WHERE imported.delivery_id = $deliveryIdExpression
              AND imported.canonical_hash = objects.canonical_hash
        )
        OR EXISTS (
            SELECT 1
            FROM ${DeliverySchema.DELIVERY_PROVENANCE} provenance
            WHERE provenance.delivery_id = $deliveryIdExpression
              AND provenance.reference_value = ?
        )
    )
""".trimIndent()

private fun recognizedV1RecordPredicate(deliveryIdExpression: String): String = """
    (
        EXISTS (
            SELECT 1
            FROM ${DeliverySchema.DELIVERY_PROVENANCE} provenance
            WHERE provenance.delivery_id = $deliveryIdExpression
              AND provenance.reference_value = ?
        )
        OR EXISTS (
            SELECT 1
            FROM ${DeliverySchema.LEGACY_IMPORT_ITEMS} imported
            JOIN ${DeliverySchema.DELIVERY_OBJECTS} imported_object
              ON imported_object.delivery_id = imported.delivery_id
             AND imported_object.canonical_hash = imported.canonical_hash
            WHERE imported.delivery_id = $deliveryIdExpression
        )
        OR EXISTS (
            SELECT 1
            FROM ${DeliverySchema.LEGACY_IMPORT_ITEMS} imported
            JOIN ${DeliverySchema.TOMBSTONES} retained_tombstone
              ON retained_tombstone.delivery_id = imported.delivery_id
            WHERE imported.delivery_id = $deliveryIdExpression
        )
    )
""".trimIndent()

private fun Cursor.toLegacyV1ObjectRow(): LegacyV1ObjectRow = LegacyV1ObjectRow(
    deliveryId = DeliveryId(getString(getColumnIndexOrThrow("delivery_id"))),
    destination = getString(getColumnIndexOrThrow("destination")),
    source = getColumnIndexOrThrow("source").let { if (isNull(it)) null else getString(it) },
    createdAtMs = getLong(getColumnIndexOrThrow("created_at_ms")),
    expiresAtMs = getLong(getColumnIndexOrThrow("expires_at_ms")),
    canonicalBytes = getBlob(getColumnIndexOrThrow("canonical_bytes")),
)

private const val LEGACY_V1_OUTBOX_DURABLY_STORED = "DURABLY_STORED"
private const val LEGACY_V1_OUTBOX_DELIVERY_EXPIRED = "DELIVERY_EXPIRED"
