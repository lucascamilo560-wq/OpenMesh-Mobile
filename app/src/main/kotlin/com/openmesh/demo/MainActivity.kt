package com.openmesh.demo

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.openmesh.android.BleMeshNode
import com.openmesh.android.MeshNodeService
import com.openmesh.android.MeshRadioGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var nodeLabel: TextView
    private lateinit var guard: MeshRadioGuard
    private var node: BleMeshNode? = null
    private var bound = false
    private var deliveryJob: Job? = null

    private val nodeId: String by lazy {
        val prefs = getSharedPreferences("openmesh_demo", MODE_PRIVATE)
        prefs.getString("node_id", null) ?: "demo-${UUID.randomUUID()}".also {
            prefs.edit().putString("node_id", it).apply()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bound = true
            val binder = service as? MeshNodeService.LocalBinder
            node = binder?.node()
            status.text = if (node != null) "Mesh ativa e procurando outros nós" else "Serviço ativo; iniciando nó..."
            observeDeliveries()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            node = null
            deliveryJob?.cancel()
            status.text = "Serviço mesh desconectado"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guard = MeshRadioGuard(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        nodeLabel = TextView(this).apply {
            text = "Node ID: $nodeId"
            textSize = 16f
        }
        status = TextView(this).apply {
            text = "Mesh parada"
            textSize = 18f
            setPadding(0, 32, 0, 32)
        }

        val start = Button(this).apply {
            text = "Ativar OpenMesh"
            setOnClickListener { ensureMeshReady() }
        }
        val send = Button(this).apply {
            text = "Enviar mensagem de teste"
            setOnClickListener { sendTestMessage() }
        }
        val stop = Button(this).apply {
            text = "Parar OpenMesh"
            setOnClickListener { stopMesh() }
        }

        root.addView(nodeLabel, matchWidth())
        root.addView(status, matchWidth())
        root.addView(start, matchWidth())
        root.addView(send, matchWidth())
        root.addView(stop, matchWidth())
        setContentView(root)
    }

    private fun ensureMeshReady() {
        val missing = buildList {
            addAll(guard.missingBlePermissions())
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.distinct()

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            status.text = "Aguardando permissões do usuário"
            return
        }

        val snapshot = guard.snapshot()
        if (!snapshot.bluetoothAvailable) {
            status.text = "Este aparelho não possui Bluetooth disponível"
            return
        }
        if (!snapshot.bluetoothEnabled) {
            startActivityForResult(guard.bluetoothEnableRequestIntent(), REQUEST_ENABLE_BLUETOOTH)
            status.text = "Aguardando ativação do Bluetooth"
            return
        }
        if (!snapshot.bleAdvertisingSupported) {
            status.text = "Este aparelho não suporta anúncio BLE necessário para esta versão"
            return
        }

        startMesh()
    }

    private fun startMesh() {
        val intent = MeshNodeService.startIntent(this, nodeId)
        startForegroundService(intent)
        if (!bound) bindService(intent, connection, Context.BIND_AUTO_CREATE)
        status.text = "Iniciando OpenMesh..."
    }

    private fun stopMesh() {
        deliveryJob?.cancel()
        node = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        startService(MeshNodeService.stopIntent(this))
        status.text = "Mesh parada"
    }

    private fun sendTestMessage() {
        val activeNode = node
        if (activeNode == null) {
            status.text = "Ative a mesh antes de enviar"
            return
        }

        scope.launch {
            val text = "OpenMesh ${System.currentTimeMillis()}"
            val envelope = activeNode.send(
                payload = text.encodeToByteArray(),
                destinationNodeId = null,
                contentType = "text/plain",
            )
            status.text = "Mensagem ${envelope.packetId.take(8)} armazenada para propagação"
        }
    }

    private fun observeDeliveries() {
        deliveryJob?.cancel()
        val activeNode = node ?: return
        deliveryJob = scope.launch {
            activeNode.deliveries.collect { envelope ->
                val body = runCatching {
                    Base64.decode(envelope.payloadBase64, Base64.NO_WRAP).decodeToString()
                }.getOrDefault("[payload binário]")
                status.text = "Recebido de ${envelope.sourceNodeId}: $body"
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                ensureMeshReady()
            } else {
                status.text = "Permissões negadas; este aparelho ficará fora da mesh"
            }
        }
    }

    @Deprecated("Legacy activity result is sufficient for the dependency-free demo app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) ensureMeshReady()
    }

    override fun onDestroy() {
        deliveryJob?.cancel()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_ENABLE_BLUETOOTH = 1002
    }
}
