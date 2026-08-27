package com.openmesh.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openmesh.core.NodeId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/** Persistent owner of the v1 PacketStore compatibility runtime. */
enum class LegacyV1RuntimeOwner(internal val persistedValue: Long) {
    SQLITE(1L),
}

/**
 * Successful proof that migration, node binding and the write-once owner marker
 * committed before a v1 runtime is allowed to receive its PacketStore.
 */
class LegacyV1RuntimePreparation internal constructor(
    val packetStore: LegacyV1DeliveryPacketStore,
    val owner: LegacyV1RuntimeOwner,
    val migrationStatus: LegacyMigrationStatus,
    internal val deliveryStore: AndroidDeliveryStore,
)

class LegacyV1CutoverException(message: String) : IllegalStateException(message)

/**
 * Builds and proves the v1 SQLite cutover without starting any radio or node.
 *
 * The SharedPreferences source is opened only through the read-only migration
 * source. There is deliberately no fallback PacketStore: any failure closes
 * SQLite and leaves runtime creation unavailable until a later safe retry.
 */
class LegacyV1RuntimeCutoverCoordinator internal constructor(
    context: Context,
    private val localNodeId: NodeId,
    private val databaseName: String,
    private val legacyPreferencesName: String,
    private val tombstoneRetentionMs: Long,
    private val clock: () -> Long,
    private val migrationHooks: LegacyMigrationHooks,
    private val cutoverHooks: LegacyV1CutoverHooks,
) : Closeable {
    private val appContext = context.applicationContext
    private val preparationMutex = mutexFor(
        "${appContext.packageName}:${appContext.getDatabasePath(databaseName).absolutePath}"
    )

    @Volatile
    private var closed = false
    private var openedStore: AndroidDeliveryStore? = null
    private var prepared: LegacyV1RuntimePreparation? = null

    @JvmOverloads
    constructor(
        context: Context,
        localNodeId: NodeId,
        databaseName: String = AndroidDeliveryStore.DEFAULT_DATABASE_NAME,
        legacyPreferencesName: String =
            LegacyPacketStoreMigrator.DEFAULT_LEGACY_PREFERENCES_NAME,
        tombstoneRetentionMs: Long =
            LegacyV1DeliveryPacketStore.DEFAULT_TOMBSTONE_RETENTION_MS,
    ) : this(
        context = context,
        localNodeId = localNodeId,
        databaseName = databaseName,
        legacyPreferencesName = legacyPreferencesName,
        tombstoneRetentionMs = tombstoneRetentionMs,
        clock = System::currentTimeMillis,
        migrationHooks = LegacyMigrationHooks(),
        cutoverHooks = LegacyV1CutoverHooks(),
    )

    init {
        require(databaseName.isNotBlank()) { "Delivery database name must not be blank" }
        require(legacyPreferencesName.isNotBlank()) {
            "Legacy preferences name must not be blank"
        }
        require(tombstoneRetentionMs > 0) { "Tombstone retention must be positive" }
    }

    suspend fun prepare(nowMs: Long = clock()): LegacyV1RuntimePreparation =
        preparationMutex.withLock {
            check(!closed) { "Legacy v1 cutover coordinator is closed" }
            prepared?.let { return@withLock it }

            val store = AndroidDeliveryStore(appContext, databaseName)
            try {
                val owner = store.legacyV1RuntimeOwner()
                val migrationStatus = if (owner == null) {
                    val report = LegacyPacketStoreMigrator(
                        store = store,
                        source = SharedPreferencesLegacyPacketSource(
                            appContext,
                            legacyPreferencesName,
                        ),
                        migrationId = LegacyPacketStoreMigrator.DEFAULT_MIGRATION_ID,
                        localNodeId = localNodeId,
                        hooks = migrationHooks,
                    ).migrate(nowMs)
                    val retained = requireRetainedMigration(report.status)
                    cutoverHooks.beforeOwnerMarker()
                    val claimed = store.claimLegacyV1RuntimeOwnership()
                    check(claimed == LegacyV1RuntimeOwner.SQLITE)
                    cutoverHooks.afterOwnerMarkerCommitted()
                    retained
                } else {
                    if (owner != LegacyV1RuntimeOwner.SQLITE) {
                        throw LegacyV1CutoverException("Unsupported v1 runtime owner $owner")
                    }
                    requireRetainedMigration(
                        store.legacyMigrationStatus(
                            LegacyPacketStoreMigrator.DEFAULT_MIGRATION_ID
                        )
                    )
                }

                val result = LegacyV1RuntimePreparation(
                    packetStore = LegacyV1DeliveryPacketStore(
                        store = store,
                        localNodeId = localNodeId,
                        clock = clock,
                        tombstoneRetentionMs = tombstoneRetentionMs,
                    ),
                    owner = LegacyV1RuntimeOwner.SQLITE,
                    migrationStatus = migrationStatus,
                    deliveryStore = store,
                )
                openedStore = store
                prepared = result
                result
            } catch (failure: Throwable) {
                store.close()
                throw failure
            }
        }

    private fun requireRetainedMigration(
        status: LegacyMigrationStatus?,
    ): LegacyMigrationStatus {
        val persisted = status ?: throw LegacyV1CutoverException(
            "SQLite ownership requires completed legacy migration metadata"
        )
        if (persisted.state != LegacyMigrationState.LEGACY_RETAINED) {
            throw LegacyV1CutoverException(
                "Legacy migration is ${persisted.state}, not LEGACY_RETAINED"
            )
        }
        if (persisted.localNodeId != localNodeId) {
            throw LegacyV1CutoverException(
                "Cutover node changed from ${persisted.localNodeId} to $localNodeId"
            )
        }
        return persisted
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            prepared = null
            openedStore?.close()
            openedStore = null
        }
    }

    private companion object {
        val PREPARATION_MUTEXES = ConcurrentHashMap<String, Mutex>()

        fun mutexFor(databaseIdentity: String): Mutex =
            PREPARATION_MUTEXES.computeIfAbsent(databaseIdentity) { Mutex() }
    }
}

internal data class LegacyV1CutoverHooks(
    val beforeOwnerMarker: () -> Unit = {},
    val afterOwnerMarkerCommitted: () -> Unit = {},
)

internal suspend fun AndroidDeliveryStore.legacyV1RuntimeOwner(): LegacyV1RuntimeOwner? =
    readForLegacyV1Runtime(::loadLegacyV1RuntimeOwner)

internal suspend fun AndroidDeliveryStore.claimLegacyV1RuntimeOwnership():
    LegacyV1RuntimeOwner = writeForLegacyV1Runtime { transaction ->
        loadLegacyV1RuntimeOwner(transaction.database)?.let {
            return@writeForLegacyV1Runtime it
        }
        val values = ContentValues().apply {
            put("key", LEGACY_V1_RUNTIME_OWNER_KEY)
            put("long_value", LegacyV1RuntimeOwner.SQLITE.persistedValue)
        }
        transaction.database.insertOrThrow(DeliverySchema.STORE_METADATA, null, values)
        transaction.markChanged()
        LegacyV1RuntimeOwner.SQLITE
    }

private fun loadLegacyV1RuntimeOwner(
    database: SQLiteDatabase,
): LegacyV1RuntimeOwner? = database.query(
    DeliverySchema.STORE_METADATA,
    arrayOf("long_value"),
    "key = ?",
    arrayOf(LEGACY_V1_RUNTIME_OWNER_KEY),
    null,
    null,
    null,
).use { cursor ->
    if (!cursor.moveToFirst()) return@use null
    val persisted = cursor.getLong(0)
    LegacyV1RuntimeOwner.entries.firstOrNull { it.persistedValue == persisted }
        ?: throw LegacyV1CutoverException(
            "Unknown persisted legacy v1 runtime owner value $persisted"
        )
}

internal const val LEGACY_V1_RUNTIME_OWNER_KEY = "legacy_v1_runtime_owner"
