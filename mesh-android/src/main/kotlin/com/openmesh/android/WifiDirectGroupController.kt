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
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.Base64

/**
 * High-bandwidth upgrade controller for Android 10+.
 *
 * OpenMesh exchanges [WifiDirectCredentials] over an authenticated lower-bandwidth
 * control channel (BLE). The receiving peer can then join the P2P group using
 * network name + passphrase, avoiding a dependency on a visible peer MAC address.
 */
class WifiDirectGroupController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
    private var receiver: BroadcastReceiver? = null

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && manager != null && channel != null

    @SuppressLint("MissingPermission")
    suspend fun createGroup(
        credentials: WifiDirectCredentials = WifiDirectCredentials.generate(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): WifiDirectGroupResult {
        if (!supported) return WifiDirectGroupResult.Unsupported
        val readiness = WifiDirectRadioGuard(appContext).snapshot()
        if (!readiness.ready) return WifiDirectGroupResult.NotReady(readiness)

        val completion = CompletableDeferred<WifiDirectGroupResult>()
        registerConnectionReceiver(completion, credentials)

        val config = WifiP2pConfig.Builder()
            .setNetworkName(credentials.networkName)
            .setPassphrase(credentials.passphrase)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            .enablePersistentMode(false)
            .build()

        manager!!.createGroup(channel!!, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) {
                completion.complete(WifiDirectGroupResult.Failed(reason))
            }
        })

        return try {
            withTimeoutOrNull(timeoutMs) { completion.await() }
                ?: WifiDirectGroupResult.Timeout
        } finally {
            unregisterReceiver()
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

        val completion = CompletableDeferred<WifiDirectGroupResult>()
        registerConnectionReceiver(completion, credentials)

        val config = WifiP2pConfig.Builder()
            .setNetworkName(credentials.networkName)
            .setPassphrase(credentials.passphrase)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            .enablePersistentMode(false)
            .build()

        manager!!.connect(channel!!, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit
            override fun onFailure(reason: Int) {
                completion.complete(WifiDirectGroupResult.Failed(reason))
            }
        })

        return try {
            withTimeoutOrNull(timeoutMs) { completion.await() }
                ?: WifiDirectGroupResult.Timeout
        } finally {
            unregisterReceiver()
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
    private fun registerConnectionReceiver(
        completion: CompletableDeferred<WifiDirectGroupResult>,
        credentials: WifiDirectCredentials,
    ) {
        unregisterReceiver()
        val localManager = manager ?: return
        val localChannel = channel ?: return

        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                localManager.requestConnectionInfo(localChannel) { info: WifiP2pInfo? ->
                    if (info?.groupFormed != true || completion.isCompleted) return@requestConnectionInfo
                    val owner = info.groupOwnerAddress?.hostAddress
                    if (owner.isNullOrBlank()) return@requestConnectionInfo
                    completion.complete(
                        WifiDirectGroupResult.Connected(
                            credentials = credentials,
                            groupOwnerAddress = owner,
                            isGroupOwner = info.isGroupOwner,
                        )
                    )
                }
            }
        }
        receiver = created
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(created, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(created, filter)
        }
    }

    private fun unregisterReceiver() {
        val active = receiver ?: return
        runCatching { appContext.unregisterReceiver(active) }
        receiver = null
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 25_000L
    }
}

data class WifiDirectCredentials(
    val networkName: String,
    val passphrase: String,
    val port: Int = WifiDirectDataChannel.DEFAULT_PORT,
) {
    init {
        require(networkName.startsWith("DIRECT-")) { "Wi-Fi Direct network name must start with DIRECT-" }
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
