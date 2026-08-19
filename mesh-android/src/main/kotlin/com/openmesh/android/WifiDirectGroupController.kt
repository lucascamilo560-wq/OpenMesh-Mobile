package com.openmesh.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume

/**
 * High-bandwidth upgrade controller for Android 10+.
 *
 * OpenMesh exchanges [WifiDirectCredentials] over an authenticated BLE control
 * envelope. A group owner is considered connected only when at least one real
 * P2P client has joined; merely creating the local group is not a connection.
 */
class WifiDirectGroupController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(appContext, Looper.getMainLooper(), null)

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && manager != null && channel != null

    @SuppressLint("MissingPermission")
    suspend fun prepareGroup(
        credentials: WifiDirectCredentials = WifiDirectCredentials.generate(),
    ): WifiDirectPrepareResult {
        if (!supported) return WifiDirectPrepareResult.Unsupported
        val readiness = WifiDirectRadioGuard(appContext).snapshot()
        if (!readiness.ready) return WifiDirectPrepareResult.NotReady(readiness)

        // Clear any stale group left by a cancelled/previous test. Some Android
        // implementations otherwise answer createGroup() with BUSY indefinitely.
        removeGroupAndAwait()
        delay(INITIAL_RADIO_SETTLE_MS)

        var lastReason = WifiP2pManager.ERROR
        repeat(MAX_BUSY_ATTEMPTS) { attempt ->
            when (val result = createGroupOnce(credentials)) {
                is WifiDirectPrepareResult.Ready -> return result
                WifiDirectPrepareResult.Unsupported -> return result
                is WifiDirectPrepareResult.NotReady -> return result
                is WifiDirectPrepareResult.Failed -> {
                    lastReason = result.reason
                    if (result.reason != WifiP2pManager.BUSY) return result
                    removeGroupAndAwait()
                    delay(BUSY_BACKOFF_MS * (attempt + 1L))
                }
            }
        }
        return WifiDirectPrepareResult.Failed(lastReason)
    }

    private suspend fun createGroupOnce(
        credentials: WifiDirectCredentials,
    ): WifiDirectPrepareResult = suspendCancellableCoroutine { continuation ->
        manager!!.createGroup(channel!!, config(credentials), object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (continuation.isActive) continuation.resume(WifiDirectPrepareResult.Ready(credentials))
            }

            override fun onFailure(reason: Int) {
                if (continuation.isActive) continuation.resume(WifiDirectPrepareResult.Failed(reason))
            }
        })
    }

    suspend fun createGroup(
        credentials: WifiDirectCredentials = WifiDirectCredentials.generate(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): WifiDirectGroupResult {
        return when (val prepared = prepareGroup(credentials)) {
            is WifiDirectPrepareResult.Ready -> awaitConnection(prepared.credentials, timeoutMs)
            WifiDirectPrepareResult.Unsupported -> WifiDirectGroupResult.Unsupported
            is WifiDirectPrepareResult.NotReady -> WifiDirectGroupResult.NotReady(prepared.snapshot)
            is WifiDirectPrepareResult.Failed -> WifiDirectGroupResult.Failed(prepared.reason)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun joinGroup(
        credentials: WifiDirectCredentials,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): WifiDirectGroupResult {
        if (!supported) return WifiDirectGroupResult.Unsupported
        val readiness = WifiDirectRadioGuard(appContext).snapshot()
        if (!readiness.ready) return WifiDirectGroupResult.NotReady(readiness)

        var lastFailure: WifiDirectGroupResult = WifiDirectGroupResult.Failed(WifiP2pManager.ERROR)
        repeat(MAX_BUSY_ATTEMPTS) { attempt ->
            val result = joinGroupOnce(credentials, timeoutMs)
            if (result !is WifiDirectGroupResult.Failed || result.reason != WifiP2pManager.BUSY) {
                return result
            }
            lastFailure = result
            removeGroupAndAwait()
            delay(BUSY_BACKOFF_MS * (attempt + 1L))
        }
        return lastFailure
    }

    @SuppressLint("MissingPermission")
    private suspend fun joinGroupOnce(
        credentials: WifiDirectCredentials,
        timeoutMs: Long,
    ): WifiDirectGroupResult {
        val connection = CompletableDeferred<WifiDirectGroupResult>()
        val receiver = registerConnectionReceiver(connection, credentials)

        manager!!.connect(channel!!, config(credentials), object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestCurrentConnection(connection, credentials)
            }

            override fun onFailure(reason: Int) {
                connection.complete(WifiDirectGroupResult.Failed(reason))
            }
        })

        return try {
            withTimeoutOrNull(timeoutMs) { connection.await() } ?: WifiDirectGroupResult.Timeout
        } finally {
            unregisterReceiver(receiver)
        }
    }

    suspend fun awaitConnection(
        credentials: WifiDirectCredentials,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): WifiDirectGroupResult {
        if (!supported) return WifiDirectGroupResult.Unsupported
        val completion = CompletableDeferred<WifiDirectGroupResult>()
        val receiver = registerConnectionReceiver(completion, credentials)

        requestCurrentConnection(completion, credentials)

        return try {
            withTimeoutOrNull(timeoutMs) { completion.await() } ?: WifiDirectGroupResult.Timeout
        } finally {
            unregisterReceiver(receiver)
        }
    }

    @SuppressLint("MissingPermission")
    fun removeGroup(onComplete: (Boolean) -> Unit = {}) {
        val localManager = manager ?: return onComplete(false)
        val localChannel = channel ?: return onComplete(false)
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onComplete(true)
            override fun onFailure(reason: Int) = onComplete(false)
        })
    }

    @SuppressLint("MissingPermission")
    private suspend fun removeGroupAndAwait(): Boolean = suspendCancellableCoroutine { continuation ->
        val localManager = manager
        val localChannel = channel
        if (localManager == null || localChannel == null) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onFailure(reason: Int) {
                // No existing group is not fatal; caller wants a clean slate.
                if (continuation.isActive) continuation.resume(false)
            }
        })
    }

    private fun config(credentials: WifiDirectCredentials): WifiP2pConfig =
        WifiP2pConfig.Builder()
            .setNetworkName(credentials.networkName)
            .setPassphrase(credentials.passphrase)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            .enablePersistentMode(false)
            .build()

    @SuppressLint("MissingPermission")
    private fun requestCurrentConnection(
        completion: CompletableDeferred<WifiDirectGroupResult>,
        credentials: WifiDirectCredentials,
    ) {
        val localManager = manager ?: return
        val localChannel = channel ?: return
        localManager.requestConnectionInfo(localChannel) { info ->
            completeFromInfo(completion, credentials, info)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerConnectionReceiver(
        completion: CompletableDeferred<WifiDirectGroupResult>,
        credentials: WifiDirectCredentials,
    ): BroadcastReceiver {
        val localManager = requireNotNull(manager)
        val localChannel = requireNotNull(channel)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                localManager.requestConnectionInfo(localChannel) { info: WifiP2pInfo? ->
                    completeFromInfo(completion, credentials, info)
                }
            }
        }

        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        return receiver
    }

    @SuppressLint("MissingPermission")
    private fun completeFromInfo(
        completion: CompletableDeferred<WifiDirectGroupResult>,
        credentials: WifiDirectCredentials,
        info: WifiP2pInfo?,
    ) {
        if (info?.groupFormed != true || completion.isCompleted) return
        val owner = info.groupOwnerAddress?.hostAddress ?: return

        if (!info.isGroupOwner) {
            completion.complete(
                WifiDirectGroupResult.Connected(
                    credentials = credentials,
                    groupOwnerAddress = owner,
                    isGroupOwner = false,
                )
            )
            return
        }

        // The owner has a local group immediately after createGroup(). Do not
        // report success until Android confirms a real client is in that group.
        val localManager = manager ?: return
        val localChannel = channel ?: return
        localManager.requestGroupInfo(localChannel) { group ->
            if (completion.isCompleted) return@requestGroupInfo
            if (group?.clientList?.isEmpty() != false) return@requestGroupInfo
            completion.complete(
                WifiDirectGroupResult.Connected(
                    credentials = credentials,
                    groupOwnerAddress = owner,
                    isGroupOwner = true,
                )
            )
        }
    }

    private fun unregisterReceiver(receiver: BroadcastReceiver) {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 25_000L
        private const val MAX_BUSY_ATTEMPTS = 3
        private const val INITIAL_RADIO_SETTLE_MS = 350L
        private const val BUSY_BACKOFF_MS = 600L
    }
}

data class WifiDirectCredentials(
    val networkName: String,
    val passphrase: String,
    val port: Int = WifiDirectDataChannel.DEFAULT_PORT,
) {
    init {
        require(networkName.matches(Regex("DIRECT-[A-Za-z0-9]{2}.*"))) {
            "Wi-Fi Direct network name must start with DIRECT- followed by two alphanumeric characters"
        }
        require(passphrase.length in 8..63) { "Wi-Fi Direct passphrase must contain 8..63 characters" }
        require(port in 1..65535) { "Invalid TCP port" }
    }

    companion object {
        private val random = SecureRandom()

        fun generate(): WifiDirectCredentials {
            val suffixBytes = ByteArray(6).also(random::nextBytes)
            val suffix = suffixBytes.joinToString("") { "%02x".format(it) }
            val passBytes = ByteArray(18).also(random::nextBytes)
            val passphrase = Base64.getUrlEncoder().withoutPadding().encodeToString(passBytes)
            return WifiDirectCredentials(
                networkName = "DIRECT-OM-$suffix",
                passphrase = passphrase,
            )
        }
    }
}

sealed interface WifiDirectPrepareResult {
    data object Unsupported : WifiDirectPrepareResult
    data class NotReady(val snapshot: WifiDirectRadioSnapshot) : WifiDirectPrepareResult
    data class Failed(val reason: Int) : WifiDirectPrepareResult
    data class Ready(val credentials: WifiDirectCredentials) : WifiDirectPrepareResult
}

sealed interface WifiDirectGroupResult {
    data object Unsupported : WifiDirectGroupResult
    data class NotReady(val snapshot: WifiDirectRadioSnapshot) : WifiDirectGroupResult
    data object Timeout : WifiDirectGroupResult
    data class Failed(val reason: Int) : WifiDirectGroupResult
    data class Connected(
        val credentials: WifiDirectCredentials,
        val groupOwnerAddress: String,
        val isGroupOwner: Boolean,
    ) : WifiDirectGroupResult
}
