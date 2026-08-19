package com.openmesh.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder

/** Foreground lifecycle host for a continuously participating OpenMesh node. */
class MeshNodeService : Service() {
    private val binder = LocalBinder()
    private var node: BleMeshNode? = null

    inner class LocalBinder : Binder() {
        fun service(): MeshNodeService = this@MeshNodeService
        fun node(): BleMeshNode? = this@MeshNodeService.node
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNode()
            return START_NOT_STICKY
        }

        val nodeId = intent?.getStringExtra(EXTRA_NODE_ID)
        if (nodeId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        showForeground("OpenMesh ativo")

        if (node == null) {
            val created = BleMeshNode(applicationContext, nodeId)
            when (created.start()) {
                MeshNodeStartResult.Started -> node = created
                else -> {
                    created.close()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        node?.close()
        node = null
        super.onDestroy()
    }

    fun currentNode(): BleMeshNode? = node

    private fun stopNode() {
        node?.close()
        node = null
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

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenMesh")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

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

    companion object {
        const val ACTION_START = "com.openmesh.android.action.START"
        const val ACTION_STOP = "com.openmesh.android.action.STOP"
        const val EXTRA_NODE_ID = "com.openmesh.android.extra.NODE_ID"
        private const val CHANNEL_ID = "openmesh_node"
        private const val NOTIFICATION_ID = 0x4D455348

        fun startIntent(context: Context, nodeId: String): Intent =
            Intent(context, MeshNodeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NODE_ID, nodeId)

        fun stopIntent(context: Context): Intent =
            Intent(context, MeshNodeService::class.java)
                .setAction(ACTION_STOP)
    }
}
