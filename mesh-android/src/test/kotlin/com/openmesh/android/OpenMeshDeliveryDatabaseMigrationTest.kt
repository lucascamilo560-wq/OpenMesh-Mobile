package com.openmesh.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openmesh.core.DeliveryId
import com.openmesh.core.DeliveryState
import com.openmesh.core.TransferAttemptState
import com.openmesh.core.TransferContext
import com.openmesh.core.TransferReservation
import com.openmesh.core.TransferReservationResult
import com.openmesh.core.TransportAdapterId
import com.openmesh.core.TransportOpportunityId
import com.openmesh.core.TransportOpportunityKey
import com.openmesh.core.TransportOpportunityReference
import com.openmesh.core.TransportOpportunityRevision
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OpenMeshDeliveryDatabaseMigrationTest {
    private lateinit var context: Context
    private val databaseNames = mutableSetOf<String>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun `v1 upgrade preserves legacy attempt exactly with null opportunity revision`() =
        runBlocking {
            val name = databaseName()
            val expected = createRealisticV1Database(name)

            val store = AndroidDeliveryStore(context, name)
            val snapshot = store.snapshot(DeliveryId(DELIVERY_ID))
            val legacy = snapshot.transferAttempts.single()

            assertEquals(TransferAttemptState.FAILED, legacy.state)
            assertEquals(expected.adapterId, legacy.context.adapterId)
            assertEquals(expected.opportunityId, legacy.context.opportunityId)
            assertNull(legacy.context.opportunityRevision)
            assertNull(legacy.context.exactOpportunityReferenceOrNull())
            store.close()

            val database = openReadOnly(name)
            assertEquals(OpenMeshDeliveryDatabase.DATABASE_VERSION, database.version)
            assertTrue("opportunity_revision" in columnNames(database, "transfer_attempts"))
            assertEquals(expected, loadLegacyAttempt(database))
            assertNull(
                database.rawQuery(
                    "SELECT opportunity_revision FROM transfer_attempts WHERE attempt_id = ?",
                    arrayOf(ATTEMPT_ID),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    if (cursor.isNull(0)) null else cursor.getLong(0)
                },
            )
            database.close()
        }

    @Test
    fun `new revisioned attempt after v1 upgrade persists exact binding through reopen`() =
        runBlocking {
            val name = databaseName()
            createRealisticV1Database(name)
            val reference = TransportOpportunityReference(
                key = TransportOpportunityKey(
                    TransportAdapterId("post-upgrade-adapter"),
                    TransportOpportunityId("post-upgrade-opportunity"),
                ),
                revision = TransportOpportunityRevision(9),
            )
            val context = TransferContext.forOpportunity(reference)
            val store = AndroidDeliveryStore(context = this@OpenMeshDeliveryDatabaseMigrationTest.context, databaseName = name)

            val acquired = store.reserveTransfer(
                TransferReservation(
                    deliveryId = DeliveryId(DELIVERY_ID),
                    context = context,
                    leaseDurationMs = 1_000,
                ),
                nowMs = 200,
            )
            assertTrue(acquired is TransferReservationResult.Acquired)
            store.close()

            val reopened = AndroidDeliveryStore(this@OpenMeshDeliveryDatabaseMigrationTest.context, name)
            val attempts = reopened.snapshot(DeliveryId(DELIVERY_ID)).transferAttempts
            assertEquals(2, attempts.size)
            assertEquals(
                reference,
                attempts.single { it.context.opportunityRevision != null }
                    .context.exactOpportunityReferenceOrNull(),
            )
            assertNull(attempts.single { it.attemptId.value == ATTEMPT_ID }.context.opportunityRevision)
            reopened.close()
        }

    @Test
    fun `unsupported future database version fails closed`() = runBlocking {
        val name = databaseName()
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            database.execSQL("CREATE TABLE future_marker (value INTEGER NOT NULL)")
            database.version = OpenMeshDeliveryDatabase.DATABASE_VERSION + 1
        }
        val store = AndroidDeliveryStore(context, name)

        val failure = runCatching { store.snapshot(DeliveryId("future-schema")) }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(
            generateSequence(failure) { it.cause }.any { cause ->
                cause.message?.contains("downgrade", ignoreCase = true) == true
            },
        )
        runCatching { store.close() }
        Unit
    }

    private fun createRealisticV1Database(name: String): LegacyAttemptRow {
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(path, null)
        V1_SCHEMA.forEach(database::execSQL)
        database.execSQL(
            "INSERT INTO store_metadata (key, long_value) VALUES (?, ?)",
            arrayOf<Any>("next_attempt_sequence", 2L),
        )
        database.insertOrThrow(
            "delivery_records",
            null,
            ContentValues().apply {
                put("delivery_id", DELIVERY_ID)
                put("state", DeliveryState.WAITING.name)
                put("stored_at_ms", 100L)
                put("updated_at_ms", 100L)
                put("version", 1L)
            },
        )
        val bytes = "legacy-canonical-object".encodeToByteArray()
        database.insertOrThrow(
            "delivery_objects",
            null,
            ContentValues().apply {
                put("delivery_id", DELIVERY_ID)
                put("destination", "dtn://example.test/inbox/legacy")
                putNull("source")
                put("created_at_ms", 90L)
                put("expires_at_ms", 10_000L)
                put("canonical_bytes", bytes)
                put("canonical_hash", sha256Hex(bytes))
            },
        )
        database.insertOrThrow(
            "delivery_provenance",
            null,
            ContentValues().apply {
                put("delivery_id", DELIVERY_ID)
                put("kind", "LOCAL_APPLICATION")
                put("reference_value", "legacy-v1-test")
            },
        )
        val expected = LegacyAttemptRow(
            attemptId = ATTEMPT_ID,
            deliveryId = DELIVERY_ID,
            adapterId = "legacy-adapter",
            opportunityId = "legacy-opportunity",
            state = TransferAttemptState.FAILED.name,
            reservedAtMs = 110,
            leaseExpiresAtMs = 1_110,
            leaseOwnerToken = "legacy-owner-token",
            startedAtMs = 120,
            linkWriteCompletedAtMs = null,
            nextHopAcceptedDurablyAtMs = null,
            nextHopAcceptanceEvidenceId = null,
            leaseExpiredAtMs = null,
            finishedAtMs = 130,
            failureReason = "LEGACY_IO_ERROR",
        )
        database.insertOrThrow(
            "transfer_attempts",
            null,
            expected.toValues(),
        )
        database.version = 1
        database.close()
        return expected
    }

    private fun openReadOnly(name: String): SQLiteDatabase = SQLiteDatabase.openDatabase(
        context.getDatabasePath(name).path,
        null,
        SQLiteDatabase.OPEN_READONLY,
    )

    private fun columnNames(database: SQLiteDatabase, table: String): Set<String> =
        database.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }

    private fun loadLegacyAttempt(database: SQLiteDatabase): LegacyAttemptRow = database.rawQuery(
        """
        SELECT attempt_id, delivery_id, adapter_id, opportunity_id, state,
               reserved_at_ms, lease_expires_at_ms, lease_owner_token,
               started_at_ms, link_write_completed_at_ms,
               next_hop_accepted_durably_at_ms, next_hop_acceptance_evidence_id,
               lease_expired_at_ms, finished_at_ms, failure_reason
        FROM transfer_attempts WHERE attempt_id = ?
        """.trimIndent(),
        arrayOf(ATTEMPT_ID),
    ).use { cursor ->
        check(cursor.moveToFirst())
        LegacyAttemptRow(
            attemptId = cursor.getString(0),
            deliveryId = cursor.getString(1),
            adapterId = cursor.getString(2),
            opportunityId = cursor.getString(3),
            state = cursor.getString(4),
            reservedAtMs = cursor.getLong(5),
            leaseExpiresAtMs = cursor.getLong(6),
            leaseOwnerToken = cursor.getString(7),
            startedAtMs = cursor.nullableLong(8),
            linkWriteCompletedAtMs = cursor.nullableLong(9),
            nextHopAcceptedDurablyAtMs = cursor.nullableLong(10),
            nextHopAcceptanceEvidenceId = cursor.nullableString(11),
            leaseExpiredAtMs = cursor.nullableLong(12),
            finishedAtMs = cursor.nullableLong(13),
            failureReason = cursor.nullableString(14),
        )
    }

    private fun android.database.Cursor.nullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.nullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun databaseName(): String =
        "delivery-migration-${UUID.randomUUID()}.db".also(databaseNames::add)

    private data class LegacyAttemptRow(
        val attemptId: String,
        val deliveryId: String,
        val adapterId: String,
        val opportunityId: String,
        val state: String,
        val reservedAtMs: Long,
        val leaseExpiresAtMs: Long,
        val leaseOwnerToken: String,
        val startedAtMs: Long?,
        val linkWriteCompletedAtMs: Long?,
        val nextHopAcceptedDurablyAtMs: Long?,
        val nextHopAcceptanceEvidenceId: String?,
        val leaseExpiredAtMs: Long?,
        val finishedAtMs: Long?,
        val failureReason: String?,
    ) {
        fun toValues(): ContentValues = ContentValues().apply {
            put("attempt_id", attemptId)
            put("delivery_id", deliveryId)
            put("adapter_id", adapterId)
            put("opportunity_id", opportunityId)
            put("state", state)
            put("reserved_at_ms", reservedAtMs)
            put("lease_expires_at_ms", leaseExpiresAtMs)
            put("lease_owner_token", leaseOwnerToken)
            putNullable("started_at_ms", startedAtMs)
            putNullable("link_write_completed_at_ms", linkWriteCompletedAtMs)
            putNullable("next_hop_accepted_durably_at_ms", nextHopAcceptedDurablyAtMs)
            putNullable("next_hop_acceptance_evidence_id", nextHopAcceptanceEvidenceId)
            putNullable("lease_expired_at_ms", leaseExpiredAtMs)
            putNullable("finished_at_ms", finishedAtMs)
            putNullable("failure_reason", failureReason)
        }

        private fun ContentValues.putNullable(key: String, value: Long?) {
            if (value == null) putNull(key) else put(key, value)
        }

        private fun ContentValues.putNullable(key: String, value: String?) {
            if (value == null) putNull(key) else put(key, value)
        }
    }

    private companion object {
        const val DELIVERY_ID = "legacy-v1-delivery"
        const val ATTEMPT_ID = "legacy-v1-attempt"

        val V1_SCHEMA = listOf(
            """CREATE TABLE delivery_records (
                delivery_id TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL,
                stored_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL,
                version INTEGER NOT NULL CHECK(version > 0))""".trimIndent(),
            """CREATE TABLE delivery_objects (
                delivery_id TEXT NOT NULL PRIMARY KEY REFERENCES delivery_records(delivery_id) ON DELETE CASCADE,
                destination TEXT NOT NULL, source TEXT, created_at_ms INTEGER NOT NULL,
                expires_at_ms INTEGER NOT NULL, canonical_bytes BLOB NOT NULL,
                canonical_hash TEXT NOT NULL, CHECK(length(canonical_bytes) > 0),
                CHECK(expires_at_ms > created_at_ms))""".trimIndent(),
            """CREATE TABLE delivery_provenance (
                delivery_id TEXT NOT NULL REFERENCES delivery_records(delivery_id) ON DELETE CASCADE,
                kind TEXT NOT NULL, reference_value TEXT NOT NULL,
                PRIMARY KEY(delivery_id, kind, reference_value))""".trimIndent(),
            """CREATE TABLE transfer_attempts (
                attempt_id TEXT NOT NULL PRIMARY KEY,
                delivery_id TEXT NOT NULL REFERENCES delivery_records(delivery_id) ON DELETE CASCADE,
                adapter_id TEXT NOT NULL, opportunity_id TEXT NOT NULL, state TEXT NOT NULL,
                reserved_at_ms INTEGER NOT NULL, lease_expires_at_ms INTEGER NOT NULL,
                lease_owner_token TEXT NOT NULL, started_at_ms INTEGER,
                link_write_completed_at_ms INTEGER, next_hop_accepted_durably_at_ms INTEGER,
                next_hop_acceptance_evidence_id TEXT, lease_expired_at_ms INTEGER,
                finished_at_ms INTEGER, failure_reason TEXT)""".trimIndent(),
            """CREATE TABLE receipts (
                receipt_id TEXT NOT NULL PRIMARY KEY,
                delivery_id TEXT NOT NULL REFERENCES delivery_records(delivery_id) ON DELETE CASCADE,
                evidence TEXT NOT NULL, issuer TEXT NOT NULL, verification TEXT NOT NULL,
                protected_bytes BLOB NOT NULL,
                linked_transfer_attempt_id TEXT REFERENCES transfer_attempts(attempt_id),
                stored_at_ms INTEGER NOT NULL, applied_at_ms INTEGER,
                CHECK(length(protected_bytes) > 0))""".trimIndent(),
            """CREATE TABLE acceptance_evidence (
                evidence_id TEXT NOT NULL PRIMARY KEY,
                delivery_id TEXT NOT NULL REFERENCES delivery_records(delivery_id) ON DELETE CASCADE,
                transfer_attempt_id TEXT NOT NULL REFERENCES transfer_attempts(attempt_id) ON DELETE CASCADE,
                authority TEXT NOT NULL, kind TEXT NOT NULL, protected_bytes BLOB NOT NULL,
                receipt_id TEXT REFERENCES receipts(receipt_id), stored_at_ms INTEGER NOT NULL,
                CHECK(length(protected_bytes) > 0))""".trimIndent(),
            """CREATE TABLE inbox (
                delivery_id TEXT NOT NULL PRIMARY KEY REFERENCES delivery_records(delivery_id) ON DELETE CASCADE,
                state TEXT NOT NULL, enqueued_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL)""".trimIndent(),
            """CREATE TABLE outbox (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT UNIQUE,
                event_type TEXT NOT NULL, delivery_id TEXT NOT NULL, receipt_id TEXT,
                transfer_attempt_id TEXT, evidence_id TEXT, committed_at_ms INTEGER NOT NULL,
                acknowledged_at_ms INTEGER)""".trimIndent(),
            """CREATE TABLE tombstones (
                delivery_id TEXT NOT NULL PRIMARY KEY REFERENCES delivery_records(delivery_id) ON DELETE CASCADE,
                reason TEXT NOT NULL, created_at_ms INTEGER NOT NULL, expires_at_ms INTEGER NOT NULL,
                CHECK(expires_at_ms > created_at_ms))""".trimIndent(),
            """CREATE TABLE migration_metadata (
                migration_id TEXT NOT NULL PRIMARY KEY, source_name TEXT NOT NULL,
                local_node_id TEXT NOT NULL, state TEXT NOT NULL,
                expected_count INTEGER NOT NULL DEFAULT 0, expected_manifest_hash TEXT,
                imported_count INTEGER NOT NULL DEFAULT 0, imported_manifest_hash TEXT,
                verified_at_ms INTEGER, updated_at_ms INTEGER NOT NULL)""".trimIndent(),
            """CREATE TABLE legacy_import_items (
                migration_id TEXT NOT NULL REFERENCES migration_metadata(migration_id) ON DELETE CASCADE,
                legacy_key TEXT NOT NULL, delivery_id TEXT NOT NULL, canonical_hash TEXT NOT NULL,
                disposition TEXT NOT NULL, expired_at_import INTEGER NOT NULL CHECK(expired_at_import IN (0, 1)),
                PRIMARY KEY(migration_id, legacy_key), UNIQUE(migration_id, delivery_id))""".trimIndent(),
            "CREATE TABLE store_metadata (key TEXT NOT NULL PRIMARY KEY, long_value INTEGER NOT NULL)",
            "CREATE INDEX delivery_objects_expiry ON delivery_objects(expires_at_ms)",
            "CREATE INDEX transfer_attempts_delivery ON transfer_attempts(delivery_id)",
            "CREATE INDEX acceptance_evidence_delivery ON acceptance_evidence(delivery_id)",
            "CREATE INDEX receipts_delivery ON receipts(delivery_id)",
            "CREATE INDEX outbox_ack_sequence ON outbox(acknowledged_at_ms, sequence)",
            "CREATE INDEX tombstones_expiry ON tombstones(expires_at_ms)",
        )
    }
}
