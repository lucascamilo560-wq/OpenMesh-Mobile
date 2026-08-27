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
import com.openmesh.core.NodeId
import com.openmesh.core.PacketStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var radioGuard: MeshRadioGuard
    private lateinit var runtime: MeshNodeServiceRuntime
    private var receiverRegistered = false

    inner class LocalBinder : Binder() {
        fun service(): MeshNodeService = this@MeshNodeService
        fun node(): BleMeshNode? = currentNode()
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
        runtime = MeshNodeServiceRuntime(
            scope = serviceScope,
            preparationDispatcher = Dispatchers.IO,
            radioSnapshot = radioGuard::snapshot,
            coordinatorFactory = { nodeId ->
                productionLegacyV1CutoverHandle(applicationContext, nodeId)
            },
            nodeFactory = ::createNode,
            persistNodeId = { nodeId ->
                preferences().edit().putString(PREF_NODE_ID, nodeId.value).commit()
            },
            publishStatus = ::publishRuntimeStatus,
        )
        ensureNotificationChannel()
        registerBluetoothReceiver()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNodeAndService()
            return START_NOT_STICKY
        }

        val requestedNodeIdValue = intent?.getStringExtra(EXTRA_NODE_ID)
            ?: preferences().getString(PREF_NODE_ID, null)

        if (requestedNodeIdValue.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        showForeground("OpenMesh iniciando…")
        val requestedNodeId = runCatching { NodeId(requestedNodeIdValue) }.getOrNull()
        if (requestedNodeId == null) {
            updateNotification("Mesh pausada: identidade de nó inválida")
            if (!runtime.hasRuntime) stopSelf()
            return START_NOT_STICKY
        }
        runtime.requestStart(requestedNodeId)
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.shutdown()
        unregisterBluetoothReceiver()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun currentNode(): BleMeshNode? = runtime.exposedNode

    private fun attemptResume() {
        runtime.onBluetoothOn()
    }

    private fun createNode(
        currentNodeId: NodeId,
        store: PacketStore,
    ): MeshNodeRuntimeHandle {
        // Default integrations can expose their public key automatically. Hosts
        // using a custom identity may still create BleMeshNode directly.
        val identity = runCatching { AndroidMeshIdentityStore(applicationContext).loadOrCreate() }
            .getOrNull()
            ?.takeIf { it.nodeId == currentNodeId.value }

        val node = BleMeshNode(
            context = applicationContext,
            localNodeId = currentNodeId.value,
            store = store,
            localIdentity = identity,
        )
        return object : MeshNodeRuntimeHandle {
            override val exposedNode: BleMeshNode = node
            override fun start(): MeshNodeStartResult = node.start()
            override fun stop() = node.stop()
            override fun close() = node.close()
        }
    }

    private fun pauseForRadioOff() {
        runtime.onBluetoothOff()
    }

    private fun stopNodeAndService() {
        runtime.shutdown()
        preferences().edit().remove(PREF_NODE_ID).commit()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishRuntimeStatus(status: MeshNodeRuntimeStatus) {
        val message = when (status) {
            MeshNodeRuntimeStatus.PreparingStorage -> "OpenMesh preparando armazenamento seguro…"
            MeshNodeRuntimeStatus.Active -> "OpenMesh ativo — procurando outros nós"
            MeshNodeRuntimeStatus.BluetoothUnavailable ->
                "Bluetooth indisponível neste aparelho"
            MeshNodeRuntimeStatus.BluetoothDisabled ->
                "Bluetooth desligado — toque para reativar"
            MeshNodeRuntimeStatus.PermissionsRequired ->
                "Permissão necessária — toque para ativar a mesh"
            MeshNodeRuntimeStatus.AdvertisingUnsupported ->
                "Anúncio BLE não suportado neste aparelho"
            MeshNodeRuntimeStatus.CutoverFailed ->
                "Mesh pausada: armazenamento transacional indisponível"
            MeshNodeRuntimeStatus.IdentityPersistenceFailed ->
                "Mesh pausada: identidade não pôde ser confirmada"
            MeshNodeRuntimeStatus.NodeIdentityMismatch ->
                "OpenMesh mantém a identidade atual — troca de nó recusada"
            is MeshNodeRuntimeStatus.TransportFailure ->
                "Mesh pausada: falha em ${status.component}"
        }
        updateNotification(message)
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
