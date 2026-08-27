package com.openmesh.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class OpenMeshDeliveryDatabase(
    context: Context,
    name: String,
) : SQLiteOpenHelper(context.applicationContext, name, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO ${DeliverySchema.STORE_METADATA} (key, long_value) VALUES (?, ?)",
            arrayOf(DeliverySchema.NEXT_ATTEMPT_SEQUENCE, 1L),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No OpenMesh delivery database upgrade exists from $oldVersion to $newVersion")
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("OpenMesh delivery database downgrade is not supported: $oldVersion to $newVersion")
    }

    companion object {
        private const val DATABASE_VERSION = 1

        private val SCHEMA = listOf(
            """
            CREATE TABLE ${DeliverySchema.DELIVERY_RECORDS} (
                delivery_id TEXT NOT NULL PRIMARY KEY,
                state TEXT NOT NULL,
                stored_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                version INTEGER NOT NULL CHECK(version > 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE ${DeliverySchema.DELIVERY_OBJECTS} (
                delivery_id TEXT NOT NULL PRIMARY KEY
                    REFERENCES ${DeliverySchema.DELIVERY_RECORDS}(delivery_id) ON DELETE CASCADE,
                destination TEXT NOT NULL,
                source TEXT,
                created_at_ms INTEGER NOT NULL,
                expires_at_ms INTEGER NOT NULL,
                canonical_bytes BLOB NOT NULL,
                canonical_hash TEXT NOT NULL,
                CHECK(length(canonical_bytes) > 0),
                CHECK(expires_at_ms > created_at_ms)
            )
            """.trimIndent(),
            """
            CREATE TABLE ${DeliverySchema.DELIVERY_PROVENANCE} (
                delivery_id TEXT NOT NULL
                    REFERENCES ${DeliverySchema.DELIVERY_RECORDS}(delivery_id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                reference_value TEXT NOT NULL,
                PRIMARY KEY(delivery_id, kind, reference_value)
            )
            """.trimIndent(),
            """
            CREATE TABLE ${DeliverySchema.TRANSFER_ATTEMPTS} (
                attempt_id TEXT NOT NULL PRIMARY KEY,
                delivery_id TEXT NOT NULL
                    REFERENCES ${DeliverySchema.DELIVERY_RECORDS}(delivery_id) ON DELETE CASCADE,
                adapter_id TEXT NOT NULL,
                opportunity_id TEXT NOT NULL,
                state TEXT NOT NULL,
                reserved_at_ms INTEGER NOT NULL,
                lease_expires_at_ms INTEGER NOT NULL,
                lease_owner_token TEXT NOT NULL,
                started_at_ms INTEGER,
                link_write_completed_at_ms INTEGER,
                next_hop_accepted_durably_at_ms INTEGER,
                next_hop_acceptance_evidence_id TEXT,
                lease_expired_at_ms INTEGER,
                finished_at_ms INTEGER,
                failure_reason TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE ${DeliverySchema.RECEIPTS} (
                receipt_id TEXT NOT NULL PRIMARY KEY,
                delivery_id TEXT NOT NULL
                    REFERENCES ${DeliverySchema.DELIVERY_RECORDS}(delivery_id) ON DELETE CASCADE,
                evidence TEXT NOT NULL,
                issuer TEXT NOT NULL,
                verification TEXT NOT NULL,
                protected_bytes BLOB NOT NULL,
                linked_transfer_attempt_id TEXT
                    REFERENCES ${DeliverySchema.TRANSFER_ATTEMPTS}(attempt_id),
                stored_at_ms INTEGER NOT NULL,
                applied_at_ms INTEGER,
                CHECK(length(protected_bytes) > 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE ${DeliverySchema.ACCEPTANCE_EVIDENCE} (
                evidence_id TEXT NOT NULL PRIMARY KEY,
                delivery_id TEXT NOT NULL
                    REFERENCES ${DeliverySchema.DELIVERY_RECORDS}(delivery_id) ON DELETE CASCADE,
                transfer_attempt_id TEXT NOT NULL
                    REFERENCES ${DeliverySchema.TRANSFER_ATTEMPTS}(attempt_id) ON DELETE CASCADE,
                authority TEXT NOT NULL,
                kind TEXT NOT NULL,
                protected_bytes BLOB NOT NULL,
                receipt_id TEXT REFERENCES ${DeliverySchema.RECEIPTS}(receipt_id),
                stored_at_ms INTEGER NOT NULL,
                CHECK(length(protected_bytes) > 0)
            )
            """.trimIndent(),
            """
            CREATE TABLE ${DeliverySchema.INBOX} (
                delivery_id TEXT NOT NULL PRIMARY KEY
                    REFERENCES ${DeliverySchema.DELIVERY_RECORDS}(delivery_id) ON DELETE CASCADE,
                state TEXT NOT NULL,
                enqueued_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE ${DeliverySchema.OUTBOX} (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id TEXT UNIQUE,
                event_type TEXT NOT NULL,
                delivery_id TEXT NOT NULL,
                receipt_id TEXT,
                transfer_attempt_id TEXT,
                evidence_id TEXT,
                committed_at_ms INTEGER NOT NULL,
                acknowledged_at_ms INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE ${DeliverySchema.TOMBSTONES} (
                delivery_id TEXT NOT NULL PRIMARY KEY
                    REFERENCES ${DeliverySchema.DELIVERY_RECORDS}(delivery_id) ON DELETE CASCADE,
                reason TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL,
                expires_at_ms INTEGER NOT NULL,
                CHECK(expires_at_ms > created_at_ms)
            )
            """.trimIndent(),
            """
            CREATE TABLE ${DeliverySchema.MIGRATION_METADATA} (
                migration_id TEXT NOT NULL PRIMARY KEY,
                source_name TEXT NOT NULL,
                local_node_id TEXT NOT NULL,
                state TEXT NOT NULL,
                expected_count INTEGER NOT NULL DEFAULT 0,
                expected_manifest_hash TEXT,
                imported_count INTEGER NOT NULL DEFAULT 0,
                imported_manifest_hash TEXT,
                verified_at_ms INTEGER,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE ${DeliverySchema.LEGACY_IMPORT_ITEMS} (
                migration_id TEXT NOT NULL
                    REFERENCES ${DeliverySchema.MIGRATION_METADATA}(migration_id) ON DELETE CASCADE,
                legacy_key TEXT NOT NULL,
                delivery_id TEXT NOT NULL,
                canonical_hash TEXT NOT NULL,
                disposition TEXT NOT NULL,
                expired_at_import INTEGER NOT NULL CHECK(expired_at_import IN (0, 1)),
                PRIMARY KEY(migration_id, legacy_key),
                UNIQUE(migration_id, delivery_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE ${DeliverySchema.STORE_METADATA} (
                key TEXT NOT NULL PRIMARY KEY,
                long_value INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX delivery_objects_expiry ON ${DeliverySchema.DELIVERY_OBJECTS}(expires_at_ms)",
            "CREATE INDEX transfer_attempts_delivery ON ${DeliverySchema.TRANSFER_ATTEMPTS}(delivery_id)",
            "CREATE INDEX acceptance_evidence_delivery ON ${DeliverySchema.ACCEPTANCE_EVIDENCE}(delivery_id)",
            "CREATE INDEX receipts_delivery ON ${DeliverySchema.RECEIPTS}(delivery_id)",
            "CREATE INDEX outbox_ack_sequence ON ${DeliverySchema.OUTBOX}(acknowledged_at_ms, sequence)",
            "CREATE INDEX tombstones_expiry ON ${DeliverySchema.TOMBSTONES}(expires_at_ms)",
        )
    }
}

internal object DeliverySchema {
    const val DELIVERY_OBJECTS = "delivery_objects"
    const val DELIVERY_RECORDS = "delivery_records"
    const val DELIVERY_PROVENANCE = "delivery_provenance"
    const val TRANSFER_ATTEMPTS = "transfer_attempts"
    const val ACCEPTANCE_EVIDENCE = "acceptance_evidence"
    const val RECEIPTS = "receipts"
    const val INBOX = "inbox"
    const val OUTBOX = "outbox"
    const val TOMBSTONES = "tombstones"
    const val MIGRATION_METADATA = "migration_metadata"
    const val LEGACY_IMPORT_ITEMS = "legacy_import_items"
    const val STORE_METADATA = "store_metadata"
    const val NEXT_ATTEMPT_SEQUENCE = "next_attempt_sequence"
}
