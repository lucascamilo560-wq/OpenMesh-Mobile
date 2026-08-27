package com.openmesh.android

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import com.openmesh.core.CanonicalDeliveryConflictException
import com.openmesh.core.DeliveryId
import com.openmesh.core.DeliveryIngest
import com.openmesh.core.DeliveryIngestResult
import com.openmesh.core.DeliveryObject
import com.openmesh.core.DeliveryState
import com.openmesh.core.DeliveryStoreAdmissionException
import com.openmesh.core.DeliveryStoreEvent
import com.openmesh.core.EndpointId
import com.openmesh.core.IngressProvenance
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshEnvelopeCodec
import com.openmesh.core.NodeId
import com.openmesh.core.TombstoneReason
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class LegacyMigrationState {
    NOT_STARTED,
    IMPORTING,
    VERIFYING,
    VERIFIED,
    LEGACY_RETAINED,
}

data class LegacyMigrationStatus(
    val migrationId: String,
    val sourceName: String,
    val localNodeId: NodeId,
    val state: LegacyMigrationState,
    val expectedCount: Int,
    val expectedManifestHash: String?,
    val importedCount: Int,
    val importedManifestHash: String?,
    val verifiedAtMs: Long?,
    val updatedAtMs: Long,
)

data class LegacyMigrationReport(
    val status: LegacyMigrationStatus,
    val sourceEntryCount: Int,
    val legacyStoreRetained: Boolean,
)

class LegacyMigrationValidationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class LegacyMigrationVerificationException(message: String) : IllegalStateException(message)

/**
 * Idempotent importer for the v1 SharedPreferences packet store.
 *
 * The source is read-only. Successful completion means SQLite was re-read and
 * matched by count, canonical IDs and SHA-256 fingerprints; it never means the
 * legacy preferences were deleted or changed.
 */
class LegacyPacketStoreMigrator internal constructor(
    private val store: AndroidDeliveryStore,
    private val source: LegacyPacketSource,
    private val migrationId: String,
    private val localNodeId: NodeId,
    private val tombstoneReplayGuardMs: Long =
        LegacyV1DeliveryPacketStore.DEFAULT_REPLAY_GUARD_MS,
    private val hooks: LegacyMigrationHooks,
) {

    @JvmOverloads
    constructor(
        context: Context,
        store: AndroidDeliveryStore,
        localNodeId: NodeId,
        legacyPreferencesName: String = DEFAULT_LEGACY_PREFERENCES_NAME,
        migrationId: String = DEFAULT_MIGRATION_ID,
        tombstoneReplayGuardMs: Long =
            LegacyV1DeliveryPacketStore.DEFAULT_REPLAY_GUARD_MS,
    ) : this(
        store = store,
        source = SharedPreferencesLegacyPacketSource(context, legacyPreferencesName),
        migrationId = migrationId,
        localNodeId = localNodeId,
        tombstoneReplayGuardMs = tombstoneReplayGuardMs,
        hooks = LegacyMigrationHooks(),
    )

    init {
        require(migrationId.isNotBlank()) { "Legacy migration ID must not be blank" }
        require(migrationId.encodeToByteArray().size <= MAX_MIGRATION_ID_BYTES) {
            "Legacy migration ID exceeds $MAX_MIGRATION_ID_BYTES bytes"
        }
        requireLegacyV1ReplayGuard(tombstoneReplayGuardMs)
    }

    suspend fun status(): LegacyMigrationStatus? = store.legacyMigrationStatus(migrationId)

    suspend fun migrate(nowMs: Long = System.currentTimeMillis()): LegacyMigrationReport {
        val snapshot = source.snapshot()
        requireSnapshotWithinLimits(snapshot)
        var metadata = ensureMetadata(nowMs)
        hooks.afterStatePersisted(metadata.state)

        validateExpectedSnapshot(metadata, snapshot)
        if (metadata.state == LegacyMigrationState.NOT_STARTED) {
            metadata = transitionToImporting(snapshot, nowMs)
            hooks.afterStatePersisted(metadata.state)
        }

        if (metadata.state == LegacyMigrationState.IMPORTING) {
            snapshot.entries.forEach { entry ->
                store.importLegacyEntry(
                    migrationId = migrationId,
                    sourceName = source.name,
                    entry = entry,
                    localNodeId = localNodeId,
                    nowMs = nowMs,
                    tombstoneReplayGuardMs = tombstoneReplayGuardMs,
                )
                hooks.afterEntryCommitted(entry.legacyKey)
            }
            metadata = transitionState(
                expected = LegacyMigrationState.IMPORTING,
                next = LegacyMigrationState.VERIFYING,
                nowMs = nowMs,
            )
            hooks.afterStatePersisted(metadata.state)
        }

        if (metadata.state == LegacyMigrationState.VERIFYING) {
            val importedManifest = store.verifyLegacyImport(
                migrationId = migrationId,
                sourceName = source.name,
                snapshot = snapshot,
                localNodeId = localNodeId,
            )
            metadata = markVerified(snapshot, importedManifest, nowMs)
            hooks.afterStatePersisted(metadata.state)
        }

        if (metadata.state == LegacyMigrationState.VERIFIED) {
            val retainedSnapshot = source.snapshot()
            if (retainedSnapshot.manifestHash != snapshot.manifestHash) {
                throw LegacyMigrationVerificationException(
                    "Legacy source changed after verification; refusing to mark it retained"
                )
            }
            store.verifyLegacyImport(
                migrationId = migrationId,
                sourceName = source.name,
                snapshot = retainedSnapshot,
                localNodeId = localNodeId,
            )
            metadata = transitionState(
                expected = LegacyMigrationState.VERIFIED,
                next = LegacyMigrationState.LEGACY_RETAINED,
                nowMs = nowMs,
            )
            hooks.afterStatePersisted(metadata.state)
        }

        if (metadata.state != LegacyMigrationState.LEGACY_RETAINED) {
            throw LegacyMigrationVerificationException(
                "Migration stopped in unexpected state ${metadata.state}"
            )
        }
        val finalSnapshot = source.snapshot()
        validateExpectedSnapshot(metadata, finalSnapshot)
        store.verifyLegacyImport(
            migrationId = migrationId,
            sourceName = source.name,
            snapshot = finalSnapshot,
            localNodeId = localNodeId,
        )
        val finalStatus = checkNotNull(status())
        return LegacyMigrationReport(
            status = finalStatus,
            sourceEntryCount = finalSnapshot.entries.size,
            legacyStoreRetained = true,
        )
    }

    private fun requireSnapshotWithinLimits(snapshot: LegacyPacketSnapshot) {
        if (snapshot.entries.size > store.configuredLimits.maxDeliveryRecords) {
            throw DeliveryStoreAdmissionException(
                "Legacy source contains ${snapshot.entries.size} packets, exceeding the " +
                    "${store.configuredLimits.maxDeliveryRecords} delivery-record limit"
            )
        }
        snapshot.entries.forEach { entry ->
            if (entry.canonicalBytes.size > store.configuredLimits.maxObjectBytes) {
                throw DeliveryStoreAdmissionException(
                    "Legacy packet ${entry.deliveryId} exceeds " +
                        "${store.configuredLimits.maxObjectBytes} bytes"
                )
            }
        }
    }

    private suspend fun ensureMetadata(
        nowMs: Long,
    ): LegacyMigrationStatus = store.writeForLegacyMigration { transaction ->
        loadMigrationStatus(transaction.database, migrationId)?.let { return@writeForLegacyMigration it }
        val values = ContentValues().apply {
            put("migration_id", migrationId)
            put("source_name", source.name)
            put("local_node_id", localNodeId.value)
            put("state", LegacyMigrationState.NOT_STARTED.name)
            put("expected_count", 0)
            putNull("expected_manifest_hash")
            put("imported_count", 0)
            putNull("imported_manifest_hash")
            putNull("verified_at_ms")
            put("updated_at_ms", nowMs)
        }
        transaction.database.insertOrThrow(DeliverySchema.MIGRATION_METADATA, null, values)
        transaction.markChanged()
        checkNotNull(loadMigrationStatus(transaction.database, migrationId))
    }

    private suspend fun transitionToImporting(
        snapshot: LegacyPacketSnapshot,
        nowMs: Long,
    ): LegacyMigrationStatus = store.writeForLegacyMigration { transaction ->
        val current = requireMetadata(transaction.database)
        requireState(current, LegacyMigrationState.NOT_STARTED)
        val values = ContentValues().apply {
            put("state", LegacyMigrationState.IMPORTING.name)
            put("expected_count", snapshot.entries.size)
            put("expected_manifest_hash", snapshot.manifestHash)
            put("updated_at_ms", nowMs)
        }
        updateMetadata(transaction, values)
        checkNotNull(loadMigrationStatus(transaction.database, migrationId))
    }

    private suspend fun transitionState(
        expected: LegacyMigrationState,
        next: LegacyMigrationState,
        nowMs: Long,
    ): LegacyMigrationStatus = store.writeForLegacyMigration { transaction ->
        val current = requireMetadata(transaction.database)
        requireState(current, expected)
        val values = ContentValues().apply {
            put("state", next.name)
            put("updated_at_ms", nowMs)
        }
        updateMetadata(transaction, values)
        checkNotNull(loadMigrationStatus(transaction.database, migrationId))
    }

    private suspend fun markVerified(
        snapshot: LegacyPacketSnapshot,
        importedManifest: String,
        nowMs: Long,
    ): LegacyMigrationStatus = store.writeForLegacyMigration { transaction ->
        val current = requireMetadata(transaction.database)
        requireState(current, LegacyMigrationState.VERIFYING)
        val importedCount = countLegacyItems(transaction.database, migrationId)
        if (importedCount != snapshot.entries.size.toLong()) {
            throw LegacyMigrationVerificationException(
                "Imported count $importedCount differs from expected ${snapshot.entries.size}"
            )
        }
        val values = ContentValues().apply {
            put("state", LegacyMigrationState.VERIFIED.name)
            put("imported_count", importedCount)
            put("imported_manifest_hash", importedManifest)
            put("verified_at_ms", nowMs)
            put("updated_at_ms", nowMs)
        }
        updateMetadata(transaction, values)
        checkNotNull(loadMigrationStatus(transaction.database, migrationId))
    }

    private fun validateExpectedSnapshot(
        metadata: LegacyMigrationStatus,
        snapshot: LegacyPacketSnapshot,
    ) {
        if (metadata.sourceName != source.name) {
            throw LegacyMigrationVerificationException(
                "Migration source changed from ${metadata.sourceName} to ${source.name}"
            )
        }
        if (metadata.localNodeId != localNodeId) {
            throw LegacyMigrationVerificationException(
                "Migration local node changed from ${metadata.localNodeId} to $localNodeId"
            )
        }
        if (metadata.state == LegacyMigrationState.NOT_STARTED) return
        if (
            metadata.expectedCount != snapshot.entries.size ||
            metadata.expectedManifestHash != snapshot.manifestHash
        ) {
            throw LegacyMigrationVerificationException(
                "Legacy source changed after migration began; retry requires the original snapshot"
            )
        }
    }

    private fun requireMetadata(database: SQLiteDatabase): LegacyMigrationStatus =
        loadMigrationStatus(database, migrationId)
            ?: throw LegacyMigrationVerificationException("Missing migration metadata $migrationId")

    private fun requireState(
        metadata: LegacyMigrationStatus,
        expected: LegacyMigrationState,
    ) {
        if (metadata.state != expected) {
            throw LegacyMigrationVerificationException(
                "Migration ${metadata.migrationId} is ${metadata.state}, expected $expected"
            )
        }
    }

    private fun updateMetadata(
        transaction: AndroidDeliveryStore.Transaction,
        values: ContentValues,
    ) {
        check(
            transaction.database.update(
                DeliverySchema.MIGRATION_METADATA,
                values,
                "migration_id = ?",
                arrayOf(migrationId),
            ) == 1
        )
        transaction.markChanged()
    }

    companion object {
        const val DEFAULT_LEGACY_PREFERENCES_NAME = "openmesh_packet_store"
        const val DEFAULT_MIGRATION_ID = "shared-preferences-packet-store-v1"
        private const val MAX_MIGRATION_ID_BYTES = 256
    }
}

internal data class LegacyMigrationHooks(
    val afterStatePersisted: (LegacyMigrationState) -> Unit = {},
    val afterEntryCommitted: (String) -> Unit = {},
)

internal enum class LegacyDeliveryDisposition {
    REMOTE_FORWARDABLE,
    BROADCAST_FORWARDABLE,
    LOCAL_QUARANTINED,
}

internal interface LegacyPacketSource {
    val name: String
    fun snapshot(): LegacyPacketSnapshot
}

internal data class LegacyPacketEntry(
    val legacyKey: String,
    val envelope: MeshEnvelope,
    val canonicalBytes: ByteArray,
    val deliveryId: DeliveryId,
    val canonicalHash: String,
) {
    init {
        require(canonicalBytes.isNotEmpty())
    }
}

internal data class LegacyPacketSnapshot(
    val entries: List<LegacyPacketEntry>,
    val manifestHash: String,
)

internal suspend fun AndroidDeliveryStore.legacyMigrationStatus(
    migrationId: String,
): LegacyMigrationStatus? = readForLegacyMigration { database ->
    loadMigrationStatus(database, migrationId)
}

internal class SharedPreferencesLegacyPacketSource(
    context: Context,
    preferencesName: String,
) : LegacyPacketSource {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )
    override val name: String = "shared-preferences:$preferencesName"

    init {
        require(preferencesName.isNotBlank()) { "Legacy preferences name must not be blank" }
    }

    override fun snapshot(): LegacyPacketSnapshot {
        val entries = preferences.all.entries
            .asSequence()
            .filter { (key, _) -> key.startsWith(PACKET_PREFIX) }
            .sortedBy { it.key }
            .map { (key, rawValue) ->
                val encoded = rawValue as? String ?: throw LegacyMigrationValidationException(
                    "Legacy packet entry $key is not a string"
                )
                val canonicalBytes = try {
                    Base64.decode(encoded, Base64.NO_WRAP)
                } catch (failure: IllegalArgumentException) {
                    throw LegacyMigrationValidationException(
                        "Legacy packet entry $key is not valid Base64",
                        failure,
                    )
                }
                val envelope = try {
                    MeshEnvelopeCodec.decode(canonicalBytes)
                } catch (failure: Exception) {
                    throw LegacyMigrationValidationException(
                        "Legacy packet entry $key is not a valid v1 envelope",
                        failure,
                    )
                }
                if (key.removePrefix(PACKET_PREFIX) != envelope.packetId) {
                    throw LegacyMigrationValidationException(
                        "Legacy key $key does not match packet ID ${envelope.packetId}"
                    )
                }
                val reencoded = MeshEnvelopeCodec.encode(envelope)
                if (!canonicalBytes.contentEquals(reencoded)) {
                    throw LegacyMigrationValidationException(
                        "Legacy packet $key is not canonically encoded"
                    )
                }
                val deliveryId = try {
                    DeliveryId(envelope.packetId)
                } catch (failure: IllegalArgumentException) {
                    throw LegacyMigrationValidationException(
                        "Legacy packet $key has an invalid canonical ID",
                        failure,
                    )
                }
                LegacyPacketEntry(
                    legacyKey = key,
                    envelope = envelope,
                    canonicalBytes = canonicalBytes.copyOf(),
                    deliveryId = deliveryId,
                    canonicalHash = sha256Hex(canonicalBytes),
                )
            }
            .toList()
        val duplicateId = entries.groupBy(LegacyPacketEntry::deliveryId)
            .entries.firstOrNull { it.value.size > 1 }
        if (duplicateId != null) {
            throw LegacyMigrationValidationException(
                "Legacy source contains duplicate packet ID ${duplicateId.key}"
            )
        }
        return LegacyPacketSnapshot(entries, manifestHash(entries))
    }

    private companion object {
        const val PACKET_PREFIX = "packet."
    }
}

private suspend fun AndroidDeliveryStore.importLegacyEntry(
    migrationId: String,
    sourceName: String,
    entry: LegacyPacketEntry,
    localNodeId: NodeId,
    nowMs: Long,
    tombstoneReplayGuardMs: Long,
): Boolean = writeForLegacyMigration { transaction ->
    val database = transaction.database
    val metadata = loadMigrationStatus(database, migrationId)
        ?: throw LegacyMigrationVerificationException("Missing migration metadata $migrationId")
    if (metadata.state != LegacyMigrationState.IMPORTING) {
        throw LegacyMigrationVerificationException(
            "Cannot import while migration is ${metadata.state}"
        )
    }

    val disposition = entry.dispositionFor(localNodeId)
    loadLegacyItem(database, migrationId, entry.legacyKey)?.let { existing ->
        if (
            existing.deliveryId != entry.deliveryId ||
            existing.canonicalHash != entry.canonicalHash ||
            existing.disposition != disposition
        ) {
            throw LegacyMigrationVerificationException(
                "Legacy import identity conflict for ${entry.legacyKey}"
            )
        }
        return@writeForLegacyMigration false
    }

    val deliveryObject = entry.toDeliveryObject()
    val provenance = IngressProvenance(
        kind = IngressProvenance.Kind.LEGACY_IMPORT,
        reference = legacyProvenanceReference(sourceName, entry.legacyKey),
    )
    val expired = entry.envelope.expiresAtMs <= nowMs
    if (expired) {
        storeExpiredLegacyV1Delivery(
            transaction = transaction,
            deliveryObject = deliveryObject,
            provenance = provenance,
            nowMs = nowMs,
            tombstoneExpiresAtMs = legacyV1TombstoneExpiresAt(
                packetExpiresAtMs = entry.envelope.expiresAtMs,
                nowMs = nowMs,
                replayGuardMs = tombstoneReplayGuardMs,
            ),
        )
    } else {
        when (disposition) {
            LegacyDeliveryDisposition.LOCAL_QUARANTINED ->
                storeQuarantinedLegacyV1Delivery(
                    transaction = transaction,
                    deliveryObject = deliveryObject,
                    provenance = provenance,
                    nowMs = nowMs,
                )

            LegacyDeliveryDisposition.REMOTE_FORWARDABLE,
            LegacyDeliveryDisposition.BROADCAST_FORWARDABLE -> when (
                ingestForLegacyMigration(
                    transaction,
                    DeliveryIngest(
                        deliveryObject = deliveryObject,
                        destinationIsLocal = false,
                        provenance = provenance,
                    ),
                    nowMs,
                )
            ) {
                is DeliveryIngestResult.RejectedByTombstone ->
                    throw LegacyMigrationVerificationException(
                        "A retained tombstone blocks legacy packet ${entry.deliveryId}"
                    )

                else -> Unit
            }
        }
    }

    val values = ContentValues().apply {
        put("migration_id", migrationId)
        put("legacy_key", entry.legacyKey)
        put("delivery_id", entry.deliveryId.value)
        put("canonical_hash", entry.canonicalHash)
        put("disposition", disposition.name)
        put("expired_at_import", if (expired) 1 else 0)
    }
    database.insertOrThrow(DeliverySchema.LEGACY_IMPORT_ITEMS, null, values)
    val countValues = ContentValues().apply {
        put("imported_count", countLegacyItems(database, migrationId))
        put("updated_at_ms", nowMs)
    }
    check(
        database.update(
            DeliverySchema.MIGRATION_METADATA,
            countValues,
            "migration_id = ?",
            arrayOf(migrationId),
        ) == 1
    )
    transaction.markChanged()
    true
}

internal fun AndroidDeliveryStore.storeQuarantinedLegacyV1Delivery(
    transaction: AndroidDeliveryStore.Transaction,
    deliveryObject: DeliveryObject,
    provenance: IngressProvenance,
    nowMs: Long,
) {
    val database = transaction.database
    if (deliveryObject.canonicalBytes.size > configuredLimits.maxObjectBytes) {
        throw DeliveryStoreAdmissionException(
            "Delivery object exceeds ${configuredLimits.maxObjectBytes} bytes"
        )
    }
    val existingObject = loadMigrationObject(database, deliveryObject.deliveryId)
    val existingRecord = loadMigrationRecord(database, deliveryObject.deliveryId)
    if (existingObject != null && existingObject != deliveryObject) {
        throw CanonicalDeliveryConflictException(deliveryObject.deliveryId)
    }
    if (existingRecord != null && existingObject == null) {
        throw CanonicalDeliveryConflictException(deliveryObject.deliveryId)
    }
    if (
        existingRecord != null &&
        existingRecord.state != DeliveryState.QUARANTINED
    ) {
        throw LegacyMigrationVerificationException(
            "Legacy-local packet ${deliveryObject.deliveryId} conflicts with existing " +
                "state ${existingRecord.state}"
        )
    }

    if (existingRecord == null) {
        if (
            DatabaseUtils.queryNumEntries(database, DeliverySchema.DELIVERY_RECORDS) >=
            configuredLimits.maxDeliveryRecords
        ) {
            throw DeliveryStoreAdmissionException("Delivery record limit reached")
        }
        val recordValues = ContentValues().apply {
            put("delivery_id", deliveryObject.deliveryId.value)
            put("state", DeliveryState.QUARANTINED.name)
            put("stored_at_ms", nowMs)
            put("updated_at_ms", nowMs)
            put("version", 1)
        }
        database.insertOrThrow(DeliverySchema.DELIVERY_RECORDS, null, recordValues)
        val objectValues = ContentValues().apply {
            put("delivery_id", deliveryObject.deliveryId.value)
            put("destination", deliveryObject.destination.value)
            deliveryObject.source?.let { put("source", it.value) } ?: putNull("source")
            put("created_at_ms", deliveryObject.createdAtMs)
            put("expires_at_ms", deliveryObject.expiresAtMs)
            put("canonical_bytes", deliveryObject.canonicalBytes.copyToByteArray())
            put("canonical_hash", sha256Hex(deliveryObject.canonicalBytes.copyToByteArray()))
        }
        database.insertOrThrow(DeliverySchema.DELIVERY_OBJECTS, null, objectValues)
        transaction.markChanged()
    }

    if (!hasMigrationProvenance(database, deliveryObject.deliveryId, provenance)) {
        val provenanceCount = DatabaseUtils.queryNumEntries(
            database,
            DeliverySchema.DELIVERY_PROVENANCE,
            "delivery_id = ?",
            arrayOf(deliveryObject.deliveryId.value),
        )
        if (provenanceCount >= configuredLimits.maxProvenanceEntriesPerDelivery) {
            throw DeliveryStoreAdmissionException(
                "Provenance limit reached for ${deliveryObject.deliveryId}"
            )
        }
        val provenanceValues = ContentValues().apply {
            put("delivery_id", deliveryObject.deliveryId.value)
            put("kind", provenance.kind.name)
            put("reference_value", provenance.reference.orEmpty())
        }
        database.insertOrThrow(
            DeliverySchema.DELIVERY_PROVENANCE,
            null,
            provenanceValues,
        )
        if (existingRecord != null) {
            val recordValues = ContentValues().apply {
                put("updated_at_ms", nowMs)
                put("version", existingRecord.version + 1)
            }
            check(
                database.update(
                    DeliverySchema.DELIVERY_RECORDS,
                    recordValues,
                    "delivery_id = ?",
                    arrayOf(deliveryObject.deliveryId.value),
                ) == 1
            )
        }
        transaction.markChanged()
    }

    if (existingRecord == null) {
        transaction.appendOutbox(
            listOf(DeliveryStoreEvent.DurablyStored(deliveryObject.deliveryId)),
            nowMs,
        )
    }
}

internal fun AndroidDeliveryStore.storeExpiredLegacyV1Delivery(
    transaction: AndroidDeliveryStore.Transaction,
    deliveryObject: DeliveryObject,
    provenance: IngressProvenance,
    nowMs: Long,
    tombstoneReason: TombstoneReason = TombstoneReason.EXPIRED,
    tombstoneExpiresAtMs: Long,
    emitExpiredEvent: Boolean = true,
) {
    require(tombstoneExpiresAtMs > nowMs) {
        "Legacy v1 tombstone expiry must be later than creation"
    }
    val database = transaction.database
    if (deliveryObject.canonicalBytes.size > configuredLimits.maxObjectBytes) {
        throw DeliveryStoreAdmissionException(
            "Delivery object exceeds ${configuredLimits.maxObjectBytes} bytes"
        )
    }
    val existingObject = loadMigrationObject(database, deliveryObject.deliveryId)
    val existingRecord = loadMigrationRecord(database, deliveryObject.deliveryId)
    if (existingRecord != null && existingObject == null) {
        throw CanonicalDeliveryConflictException(deliveryObject.deliveryId)
    }
    if (existingObject != null && existingObject != deliveryObject) {
        throw CanonicalDeliveryConflictException(deliveryObject.deliveryId)
    }

    if (existingRecord == null) {
        if (
            DatabaseUtils.queryNumEntries(database, DeliverySchema.DELIVERY_RECORDS) >=
            configuredLimits.maxDeliveryRecords
        ) {
            throw DeliveryStoreAdmissionException("Delivery record limit reached")
        }
        val recordValues = ContentValues().apply {
            put("delivery_id", deliveryObject.deliveryId.value)
            put("state", DeliveryState.EXPIRED.name)
            put("stored_at_ms", nowMs)
            put("updated_at_ms", nowMs)
            put("version", 1)
        }
        database.insertOrThrow(DeliverySchema.DELIVERY_RECORDS, null, recordValues)
    } else {
        val recordValues = ContentValues().apply {
            put("state", DeliveryState.EXPIRED.name)
            put("updated_at_ms", nowMs)
            put("version", existingRecord.version + 1)
        }
        check(
            database.update(
                DeliverySchema.DELIVERY_RECORDS,
                recordValues,
                "delivery_id = ?",
                arrayOf(deliveryObject.deliveryId.value),
            ) == 1
        )
        check(
            database.delete(
                DeliverySchema.DELIVERY_OBJECTS,
                "delivery_id = ?",
                arrayOf(deliveryObject.deliveryId.value),
            ) == 1
        )
    }

    if (!hasMigrationProvenance(database, deliveryObject.deliveryId, provenance)) {
        val provenanceCount = DatabaseUtils.queryNumEntries(
            database,
            DeliverySchema.DELIVERY_PROVENANCE,
            "delivery_id = ?",
            arrayOf(deliveryObject.deliveryId.value),
        )
        if (provenanceCount >= configuredLimits.maxProvenanceEntriesPerDelivery) {
            throw DeliveryStoreAdmissionException(
                "Provenance limit reached for ${deliveryObject.deliveryId}"
            )
        }
        val provenanceValues = ContentValues().apply {
            put("delivery_id", deliveryObject.deliveryId.value)
            put("kind", provenance.kind.name)
            put("reference_value", provenance.reference.orEmpty())
        }
        database.insertOrThrow(
            DeliverySchema.DELIVERY_PROVENANCE,
            null,
            provenanceValues,
        )
    }

    val tombstoneValues = ContentValues().apply {
        put("delivery_id", deliveryObject.deliveryId.value)
        put("reason", tombstoneReason.name)
        put("created_at_ms", nowMs)
        put("expires_at_ms", tombstoneExpiresAtMs)
    }
    database.insertWithOnConflict(
        DeliverySchema.TOMBSTONES,
        null,
        tombstoneValues,
        SQLiteDatabase.CONFLICT_REPLACE,
    ).also { check(it != -1L) }
    transaction.markChanged()
    if (emitExpiredEvent) {
        transaction.appendOutbox(
            listOf(DeliveryStoreEvent.DeliveryExpired(deliveryObject.deliveryId)),
            nowMs,
        )
    }
}

internal suspend fun AndroidDeliveryStore.normalizeInfiniteImportedLegacyV1Tombstones(
    migrationId: String,
    nowMs: Long,
    replayGuardMs: Long,
): Int = writeForLegacyV1Runtime { transaction ->
    requireLegacyV1ReplayGuard(replayGuardMs)
    val metadata = loadMigrationStatus(transaction.database, migrationId)
        ?: throw LegacyMigrationVerificationException("Missing migration metadata $migrationId")
    if (metadata.state != LegacyMigrationState.LEGACY_RETAINED) {
        throw LegacyMigrationVerificationException(
            "Cannot normalize tombstones while migration is ${metadata.state}"
        )
    }
    val tombstones = transaction.database.rawQuery(
        """
        SELECT tombstones.delivery_id, tombstones.created_at_ms
        FROM ${DeliverySchema.TOMBSTONES} tombstones
        JOIN ${DeliverySchema.LEGACY_IMPORT_ITEMS} imported
          ON imported.delivery_id = tombstones.delivery_id
        WHERE imported.migration_id = ?
          AND imported.expired_at_import = 1
          AND tombstones.expires_at_ms = ?
        ORDER BY tombstones.delivery_id
        """.trimIndent(),
        arrayOf(migrationId, Long.MAX_VALUE.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.getString(0) to cursor.getLong(1))
            }
        }
    }
    tombstones.forEach { (deliveryId, createdAtMs) ->
        val values = ContentValues().apply {
            put(
                "expires_at_ms",
                legacyV1TombstoneExpiresAt(
                    packetExpiresAtMs = createdAtMs,
                    nowMs = nowMs,
                    replayGuardMs = replayGuardMs,
                ),
            )
        }
        check(
            transaction.database.update(
                DeliverySchema.TOMBSTONES,
                values,
                "delivery_id = ? AND expires_at_ms = ?",
                arrayOf(deliveryId, Long.MAX_VALUE.toString()),
            ) == 1
        )
    }
    if (tombstones.isNotEmpty()) transaction.markChanged()
    tombstones.size
}

private suspend fun AndroidDeliveryStore.verifyLegacyImport(
    migrationId: String,
    sourceName: String,
    snapshot: LegacyPacketSnapshot,
    localNodeId: NodeId,
): String = readForLegacyMigration { database ->
    val metadata = loadMigrationStatus(database, migrationId)
        ?: throw LegacyMigrationVerificationException("Missing migration metadata $migrationId")
    if (metadata.expectedCount != snapshot.entries.size) {
        throw LegacyMigrationVerificationException("Legacy count changed during verification")
    }
    if (metadata.expectedManifestHash != snapshot.manifestHash) {
        throw LegacyMigrationVerificationException("Legacy manifest changed during verification")
    }
    if (metadata.localNodeId != localNodeId) {
        throw LegacyMigrationVerificationException(
            "Legacy migration local node changed during verification"
        )
    }

    val imported = loadLegacyItems(database, migrationId)
    if (imported.size != snapshot.entries.size) {
        throw LegacyMigrationVerificationException(
            "SQLite contains ${imported.size} imported items; expected ${snapshot.entries.size}"
        )
    }
    val expectedByKey = snapshot.entries.associateBy(LegacyPacketEntry::legacyKey)
    imported.forEach { item ->
        val expected = expectedByKey[item.legacyKey]
            ?: throw LegacyMigrationVerificationException(
                "SQLite contains unexpected legacy key ${item.legacyKey}"
            )
        if (
            item.deliveryId != expected.deliveryId ||
            item.canonicalHash != expected.canonicalHash
        ) {
            throw LegacyMigrationVerificationException(
                "SQLite fingerprint mismatch for ${item.legacyKey}"
            )
        }
        val expectedDisposition = expected.dispositionFor(localNodeId)
        if (item.disposition != expectedDisposition) {
            throw LegacyMigrationVerificationException(
                "SQLite disposition mismatch for ${item.legacyKey}"
            )
        }
        val provenance = IngressProvenance(
            IngressProvenance.Kind.LEGACY_IMPORT,
            legacyProvenanceReference(sourceName, item.legacyKey),
        )
        if (!hasMigrationProvenance(database, item.deliveryId, provenance)) {
            throw LegacyMigrationVerificationException(
                "SQLite lacks LEGACY_IMPORT provenance for ${item.deliveryId}"
            )
        }
        if (hasInventedLegacyFacts(database, item.deliveryId)) {
            throw LegacyMigrationVerificationException(
                "SQLite contains invented delivery evidence for ${item.deliveryId}"
            )
        }
        if (item.expiredAtImport) {
            val record = loadMigrationRecord(database, item.deliveryId)
            if (
                record?.state != DeliveryState.EXPIRED ||
                loadMigrationObject(database, item.deliveryId) != null ||
                !hasMigrationTombstone(database, item.deliveryId)
            ) {
                throw LegacyMigrationVerificationException(
                    "Expired legacy packet ${item.deliveryId} lacks its retained tombstone"
                )
            }
        } else {
            val storedObject = loadMigrationObject(database, item.deliveryId)
                ?: throw LegacyMigrationVerificationException(
                    "SQLite lacks object bytes for ${item.deliveryId}"
                )
            val bytes = storedObject.canonicalBytes.copyToByteArray()
            if (
                !bytes.contentEquals(expected.canonicalBytes) ||
                sha256Hex(bytes) != item.canonicalHash
            ) {
                throw LegacyMigrationVerificationException(
                    "SQLite object hash mismatch for ${item.deliveryId}"
                )
            }
            val expectedState = when (item.disposition) {
                LegacyDeliveryDisposition.LOCAL_QUARANTINED -> DeliveryState.QUARANTINED
                LegacyDeliveryDisposition.REMOTE_FORWARDABLE,
                LegacyDeliveryDisposition.BROADCAST_FORWARDABLE -> DeliveryState.WAITING
            }
            if (loadMigrationRecord(database, item.deliveryId)?.state != expectedState) {
                throw LegacyMigrationVerificationException(
                    "SQLite lifecycle mismatch for ${item.deliveryId}; expected $expectedState"
                )
            }
        }
    }
    val importedManifest = manifestEntriesHash(
        imported.map { item ->
            ManifestEntry(item.legacyKey, item.deliveryId.value, item.canonicalHash)
        }
    )
    if (importedManifest != snapshot.manifestHash) {
        throw LegacyMigrationVerificationException(
            "SQLite manifest $importedManifest differs from source ${snapshot.manifestHash}"
        )
    }
    importedManifest
}

private data class MigrationRecord(
    val state: DeliveryState,
    val version: Long,
)

private data class LegacyImportItem(
    val legacyKey: String,
    val deliveryId: DeliveryId,
    val canonicalHash: String,
    val disposition: LegacyDeliveryDisposition,
    val expiredAtImport: Boolean,
)

private fun loadMigrationStatus(
    database: SQLiteDatabase,
    migrationId: String,
): LegacyMigrationStatus? = database.query(
    DeliverySchema.MIGRATION_METADATA,
    null,
    "migration_id = ?",
    arrayOf(migrationId),
    null,
    null,
    null,
).use { cursor ->
    if (!cursor.moveToFirst()) return@use null
    LegacyMigrationStatus(
        migrationId = cursor.stringValue("migration_id"),
        sourceName = cursor.stringValue("source_name"),
        localNodeId = NodeId(cursor.stringValue("local_node_id")),
        state = enumValueOf(cursor.stringValue("state")),
        expectedCount = cursor.intValue("expected_count"),
        expectedManifestHash = cursor.nullableStringValue("expected_manifest_hash"),
        importedCount = cursor.intValue("imported_count"),
        importedManifestHash = cursor.nullableStringValue("imported_manifest_hash"),
        verifiedAtMs = cursor.nullableLongValue("verified_at_ms"),
        updatedAtMs = cursor.longValue("updated_at_ms"),
    )
}

private fun loadLegacyItems(
    database: SQLiteDatabase,
    migrationId: String,
): List<LegacyImportItem> = database.query(
    DeliverySchema.LEGACY_IMPORT_ITEMS,
    null,
    "migration_id = ?",
    arrayOf(migrationId),
    null,
    null,
    "legacy_key",
).use { cursor ->
    buildList {
        while (cursor.moveToNext()) add(cursor.toLegacyImportItem())
    }
}

private fun loadLegacyItem(
    database: SQLiteDatabase,
    migrationId: String,
    legacyKey: String,
): LegacyImportItem? = database.query(
    DeliverySchema.LEGACY_IMPORT_ITEMS,
    null,
    "migration_id = ? AND legacy_key = ?",
    arrayOf(migrationId, legacyKey),
    null,
    null,
    null,
).use { cursor ->
    if (!cursor.moveToFirst()) null else cursor.toLegacyImportItem()
}

private fun Cursor.toLegacyImportItem(): LegacyImportItem = LegacyImportItem(
    legacyKey = stringValue("legacy_key"),
    deliveryId = DeliveryId(stringValue("delivery_id")),
    canonicalHash = stringValue("canonical_hash"),
    disposition = enumValueOf(stringValue("disposition")),
    expiredAtImport = intValue("expired_at_import") == 1,
)

private fun countLegacyItems(database: SQLiteDatabase, migrationId: String): Long =
    DatabaseUtils.queryNumEntries(
        database,
        DeliverySchema.LEGACY_IMPORT_ITEMS,
        "migration_id = ?",
        arrayOf(migrationId),
    )

private fun loadMigrationObject(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): DeliveryObject? = database.query(
    DeliverySchema.DELIVERY_OBJECTS,
    null,
    "delivery_id = ?",
    arrayOf(deliveryId.value),
    null,
    null,
    null,
).use { cursor ->
    if (!cursor.moveToFirst()) return@use null
    DeliveryObject(
        deliveryId = deliveryId,
        destination = EndpointId(cursor.stringValue("destination")),
        source = cursor.nullableStringValue("source")?.let(::EndpointId),
        createdAtMs = cursor.longValue("created_at_ms"),
        expiresAtMs = cursor.longValue("expires_at_ms"),
        canonicalBytes = com.openmesh.core.OpaqueBytes.copyOf(
            cursor.getBlob(cursor.getColumnIndexOrThrow("canonical_bytes"))
        ),
    )
}

private fun loadMigrationRecord(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): MigrationRecord? = database.query(
    DeliverySchema.DELIVERY_RECORDS,
    arrayOf("state", "version"),
    "delivery_id = ?",
    arrayOf(deliveryId.value),
    null,
    null,
    null,
).use { cursor ->
    if (!cursor.moveToFirst()) null else MigrationRecord(
        state = enumValueOf(cursor.getString(0)),
        version = cursor.getLong(1),
    )
}

private fun hasMigrationProvenance(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
    provenance: IngressProvenance,
): Boolean = DatabaseUtils.queryNumEntries(
    database,
    DeliverySchema.DELIVERY_PROVENANCE,
    "delivery_id = ? AND kind = ? AND reference_value = ?",
    arrayOf(deliveryId.value, provenance.kind.name, provenance.reference.orEmpty()),
) > 0

private fun hasMigrationTombstone(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): Boolean = DatabaseUtils.queryNumEntries(
    database,
    DeliverySchema.TOMBSTONES,
    "delivery_id = ?",
    arrayOf(deliveryId.value),
) == 1L

private fun hasInventedLegacyFacts(
    database: SQLiteDatabase,
    deliveryId: DeliveryId,
): Boolean = listOf(
    DeliverySchema.TRANSFER_ATTEMPTS,
    DeliverySchema.ACCEPTANCE_EVIDENCE,
    DeliverySchema.RECEIPTS,
    DeliverySchema.INBOX,
).any { table ->
    DatabaseUtils.queryNumEntries(
        database,
        table,
        "delivery_id = ?",
        arrayOf(deliveryId.value),
    ) > 0
}

private fun LegacyPacketEntry.dispositionFor(
    localNodeId: NodeId,
): LegacyDeliveryDisposition = legacyV1Disposition(envelope, localNodeId)

internal fun legacyV1Disposition(
    envelope: MeshEnvelope,
    localNodeId: NodeId,
): LegacyDeliveryDisposition = when (envelope.destinationNodeId) {
    null -> LegacyDeliveryDisposition.BROADCAST_FORWARDABLE
    localNodeId.value -> LegacyDeliveryDisposition.LOCAL_QUARANTINED
    else -> LegacyDeliveryDisposition.REMOTE_FORWARDABLE
}

private fun LegacyPacketEntry.toDeliveryObject(): DeliveryObject = legacyV1DeliveryObject(
    envelope = envelope,
    canonicalBytes = canonicalBytes,
)

internal fun legacyV1DeliveryObject(
    envelope: MeshEnvelope,
    canonicalBytes: ByteArray = MeshEnvelopeCodec.encode(envelope),
): DeliveryObject = DeliveryObject.copyOf(
    deliveryId = DeliveryId(envelope.packetId),
    destination = envelope.destinationNodeId?.let(::legacyNodeEndpoint)
        ?: EndpointId("dtn://openmesh/legacy-v1/broadcast"),
    source = legacyNodeEndpoint(envelope.sourceNodeId),
    createdAtMs = envelope.createdAtMs,
    expiresAtMs = envelope.expiresAtMs,
    canonicalBytes = canonicalBytes,
)

internal fun legacyNodeEndpoint(nodeId: String): EndpointId {
    val encoded = URLEncoder.encode(nodeId, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
    return EndpointId("dtn://openmesh/legacy-v1/node/$encoded")
}

private fun legacyProvenanceReference(sourceName: String, legacyKey: String): String =
    "$sourceName:item:${sha256Hex(legacyKey.encodeToByteArray()).take(32)}"

private data class ManifestEntry(
    val legacyKey: String,
    val deliveryIdValue: String,
    val canonicalHash: String,
)

private fun manifestHash(entries: List<LegacyPacketEntry>): String = manifestEntriesHash(
    entries.map { ManifestEntry(it.legacyKey, it.deliveryId.value, it.canonicalHash) }
)

private fun manifestEntriesHash(entries: List<ManifestEntry>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    entries.sortedBy(ManifestEntry::legacyKey).forEach { entry ->
        listOf(entry.legacyKey, entry.deliveryIdValue, entry.canonicalHash).forEach { value ->
            val bytes = value.encodeToByteArray()
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
    }
    return digest.digest().joinToString(separator = "") { "%02x".format(it) }
}

private fun Cursor.stringValue(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.intValue(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun Cursor.longValue(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.nullableStringValue(name: String): String? =
    getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
private fun Cursor.nullableLongValue(name: String): Long? =
    getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
