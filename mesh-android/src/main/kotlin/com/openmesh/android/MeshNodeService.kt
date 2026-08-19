package com.openmesh.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder

/**
 * Foreground lifecycle host for a continuously participating OpenMesh node.
 *
 * The service watches Bluetooth state changes. If the user turns Bluetooth off,
 * the mesh transport is stopped without discarding queued packets. The
 * foreground notification explains that participation is paused and opens the
 * host app so it can request Bluetooth activation through Android's consent UI.
 * When Bluetooth returns, the node attempts to rejoin automatically.
 */
class MeshNodeService : Service() {
    private val binder = LocalBinder()
    private lateinit var radioGuard: MeshRadioGuard
    private var node: BleMeshNode? = null
    private var nodeId: String? = null
    private var receiverRegistered = false

    inner class LocalBinder : Binder() {
        fun service(): MeshNodeService = this@MeshNodeService
        fun node(): BleMeshNode? = this@MeshNodeService.node
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF,
                BluetoothAdapter.STATE_TURNING_OFF -> pauseForRadioOff()

                BluetoothAdapter.STATE_ON -> attemptResume()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        radioGuard = MeshRadioGuard(applicationContext)
        ensureNotificationChannel()
        registerBluetoothReceiver()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNodeAndService()
            return START_NOT_STICKY
        }

        val requestedNodeId = intent?.getStringExtra(EXTRA_NODE_ID)
            ?: preferences().getString(PREF_NODE_ID, null)

        if (requestedNodeId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        nodeId = requestedNodeId
        preferences().edit().putString(PREF_NODE_ID, requestedNodeId).apply()
        showForeground("OpenMesh iniciando…")
        attemptResume()
        return START_STICKY
    }

    override fun onDestroy() {
        node?.close()
        node = null
        unregisterBluetoothReceiver()
        super.onDestroy()
    }

    fun currentNode(): BleMeshNode? = node

    private fun attemptResume() {
        val currentNodeId = nodeId ?: return
        val snapshot = radioGuard.snapshot()

        when {
            !snapshot.bluetoothAvailable -> {
                node?.stop()
                updateNotification("Bluetooth indisponível neste aparelho")
            }

            snapshot.missingBlePermissions.isNotEmpty() -> {
                node?.stop()
                updateNotification("Permissão necessária — toque para ativar a mesh")
            }

            !snapshot.bluetoothEnabled -> {
                node?.stop()
                updateNotification("Bluetooth desligado — toque para reativar")
            }

            !snapshot.bleAdvertisingSupported -> {
                node?.stop()
                updateNotification("Anúncio BLE não suportado neste aparelho")
            }

            else -> {
                val activeNode = node ?: createNode(currentNodeId).also {
                    node = it
                }
                when (val result = activeNode.start()) {
                    MeshNodeStartResult.Started -> updateNotification("OpenMesh ativo — procurando outros nós")
                    is MeshNodeStartResult.PermissionsRequired ->
                        updateNotification("Permissão necessária — toque para ativar a mesh")
                    MeshNodeStartResult.BluetoothDisabled ->
                        updateNotification("Bluetooth desligado — toque para reativar")
                    MeshNodeStartResult.BluetoothUnavailable ->
                        updateNotification("Bluetooth indisponível neste aparelho")
                    MeshNodeStartResult.AdvertisingUnsupported ->
                        updateNotification("Anúncio BLE não suportado neste aparelho")
                    is MeshNodeStartResult.TransportFailure ->
                        updateNotification("Mesh pausada: falha em ${result.component}")
                }
            }
        }
    }

    private fun createNode(currentNodeId: String): BleMeshNode {
        // Default integrations can expose their public key automatically. Hosts
        // using a custom identity may still create BleMeshNode directly.
        val identity = runCatching { AndroidMeshIdentityStore(applicationContext).loadOrCreate() }
            .getOrNull()
            ?.takeIf { it.nodeId == currentNodeId }

        return BleMeshNode(
            context = applicationContext,
            localNodeId = currentNodeId,
            localIdentity = identity,
        )
    }

    private fun pauseForRadioOff() {
        node?.stop()
        updateNotification("Bluetooth desligado — toque para reativar")
    }

    private fun stopNodeAndService() {
        node?.close()
        node = null
        nodeId = null
        preferences().edit().remove(PREF_NODE_ID).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "OpenMesh",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantém a comunicação mesh ativa em segundo plano"
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenMesh")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        hostLaunchPendingIntent()?.let(builder::setContentIntent)
        return builder.build()
    }

    private fun hostLaunchPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_HOST,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothStateReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        receiverRegistered = false
    }

    private fun preferences() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val ACTION_START = "com.openmesh.android.action.START"
        const val ACTION_STOP = "com.openmesh.android.action.STOP"
        const val EXTRA_NODE_ID = "com.openmesh.android.extra.NODE_ID"
        private const val CHANNEL_ID = "openmesh_node"
        private const val NOTIFICATION_ID = 0x4D455348
        private const val REQUEST_OPEN_HOST = 0x4D45
        private const val PREFS_NAME = "openmesh_service"
        private const val PREF_NODE_ID = "node_id"

        fun startIntent(context: Context, nodeId: String): Intent =
            Intent(context, MeshNodeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NODE_ID, nodeId)

        fun stopIntent(context: Context): Intent =
            Intent(context, MeshNodeService::class.java)
                .setAction(ACTION_STOP)
    }
}
