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
import android.provider.Settings
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.openmesh.android.AndroidMeshIdentityStore
import com.openmesh.android.BleMeshNode
import com.openmesh.android.MeshNodeService
import com.openmesh.android.MeshRadioGuard
import com.openmesh.android.VerifiedPeerMetadata
import com.openmesh.android.WifiDirectRadioGuard
import com.openmesh.android.WifiDirectSocketSession
import com.openmesh.android.WifiDirectUpgradeCoordinator
import com.openmesh.android.WifiDirectUpgradeEvent
import com.openmesh.android.WifiDirectUpgradeResult
import com.openmesh.core.SecureMeshMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var nodeLabel: TextView
    private lateinit var peerLabel: TextView
    private lateinit var messageInput: EditText
    private lateinit var guard: MeshRadioGuard
    private lateinit var wifiGuard: WifiDirectRadioGuard

    private var node: BleMeshNode? = null
    private var bound = false
    private var deliveryJob: Job? = null
    private var peerRefreshJob: Job? = null
    private var upgradeEventsJob: Job? = null
    private var upgradeSessionsJob: Job? = null
    private var upgradeCoordinator: WifiDirectUpgradeCoordinator? = null
    private var selectedPeer: VerifiedPeerMetadata? = null
    private var fastSession: WifiDirectSocketSession? = null

    private val identity by lazy {
        AndroidMeshIdentityStore(this).loadOrCreate()
    }
    private val nodeId: String by lazy { identity.nodeId }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bound = true
            val binder = service as? MeshNodeService.LocalBinder
            node = binder?.node()
            if (node != null) {
                setStatus("Mesh ativa. Procurando outros nós…")
                observeDeliveries()
                observeVerifiedPeers()
                startUpgradeCoordinator()
            } else {
                setStatus("Serviço ativo; nó ainda está iniciando…")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            node = null
            stopObservers()
            closeUpgradeCoordinator()
            setStatus("Serviço mesh desconectado")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guard = MeshRadioGuard(this)
        wifiGuard = WifiDirectRadioGuard(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 40)
        }
        val scroll = ScrollView(this).apply { addView(content) }

        nodeLabel = TextView(this).apply {
            text = "Meu Node ID:\n$nodeId"
            textSize = 14f
            setTextIsSelectable(true)
        }
        peerLabel = TextView(this).apply {
            text = "Peer verificado: nenhum"
            textSize = 16f
            setPadding(0, 24, 0, 12)
            setTextIsSelectable(true)
        }
        status = TextView(this).apply {
            text = "Mesh parada"
            textSize = 17f
            setPadding(0, 16, 0, 24)
        }
        messageInput = EditText(this).apply {
            hint = "Mensagem de teste"
            setText("Olá pela OpenMesh")
            maxLines = 3
        }

        val start = Button(this).apply {
            text = "1. Ativar OpenMesh"
            setOnClickListener { ensureMeshReady() }
        }
        val refresh = Button(this).apply {
            text = "2. Atualizar peer"
            setOnClickListener { refreshPeerNow() }
        }
        val sendSecure = Button(this).apply {
            text = "3. Enviar mensagem segura ao peer"
            setOnClickListener { sendSecureTestMessage() }
        }
        val upgrade = Button(this).apply {
            text = "4. Abrir canal rápido Wi‑Fi Direct"
            setOnClickListener { requestFastChannel() }
        }
        val fastPing = Button(this).apply {
            text = "5. Testar canal rápido"
            setOnClickListener { sendFastPing() }
        }
        val stop = Button(this).apply {
            text = "Parar OpenMesh"
            setOnClickListener { stopMesh() }
        }

        content.addView(nodeLabel, matchWidth())
        content.addView(peerLabel, matchWidth())
        content.addView(status, matchWidth())
        content.addView(start, matchWidth())
        content.addView(refresh, matchWidth())
        content.addView(messageInput, matchWidth())
        content.addView(sendSecure, matchWidth())
        content.addView(upgrade, matchWidth())
        content.addView(fastPing, matchWidth())
        content.addView(stop, matchWidth())
        setContentView(scroll)
    }

    private fun ensureMeshReady() {
        val missing = buildList {
            addAll(guard.missingBlePermissions())
            addAll(wifiGuard.missingPermissions())
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.distinct()

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            setStatus("Aguardando permissões do usuário…")
            return
        }

        val snapshot = guard.snapshot()
        if (!snapshot.bluetoothAvailable) {
            setStatus("Este aparelho não possui Bluetooth disponível")
            return
        }
        if (!snapshot.bluetoothEnabled) {
            startActivityForResult(guard.bluetoothEnableRequestIntent(), REQUEST_ENABLE_BLUETOOTH)
            setStatus("Confirme a ativação do Bluetooth")
            return
        }
        if (!snapshot.bleAdvertisingSupported) {
            setStatus("Este aparelho não suporta anúncio BLE necessário nesta versão")
            return
        }

        startMesh()
    }

    private fun startMesh() {
        val intent = MeshNodeService.startIntent(this, nodeId)
        startForegroundService(intent)
        if (!bound) bindService(intent, connection, Context.BIND_AUTO_CREATE)
        setStatus("Iniciando OpenMesh…")
    }

    private fun stopMesh() {
        stopObservers()
        closeUpgradeCoordinator()
        node = null
        selectedPeer = null
        updatePeerLabel()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        startService(MeshNodeService.stopIntent(this))
        setStatus("Mesh parada")
    }

    private fun observeVerifiedPeers() {
        peerRefreshJob?.cancel()
        peerRefreshJob = scope.launch {
            while (isActive) {
                refreshPeerNow()
                delay(2_000)
            }
        }
    }

    private fun refreshPeerNow() {
        val peers = node?.verifiedPeers().orEmpty().filter { it.nodeId != nodeId }
        val newest = peers.maxByOrNull { it.lastVerifiedAtMs }
        if (newest?.nodeId != selectedPeer?.nodeId) {
            selectedPeer = newest
            updatePeerLabel()
            if (newest != null) appendStatus("Peer verificado encontrado: ${shortId(newest.nodeId)}")
        } else {
            updatePeerLabel()
        }
    }

    private fun updatePeerLabel() {
        val peer = selectedPeer
        peerLabel.text = if (peer == null) {
            "Peer verificado: nenhum ainda"
        } else {
            "Peer verificado:\n${peer.nodeId}"
        }
    }

    private fun sendSecureTestMessage() {
        val activeNode = node ?: return setStatus("Ative a mesh antes de enviar")
        val peer = selectedPeer ?: return setStatus("Nenhum peer verificado. Ative o app no segundo celular e mantenha-os próximos.")
        val text = messageInput.text?.toString()?.ifBlank { "Olá pela OpenMesh" } ?: "Olá pela OpenMesh"

        scope.launch {
            val envelope = runCatching {
                activeNode.sendSecure(
                    payload = text.encodeToByteArray(),
                    contentType = TEST_MESSAGE_CONTENT_TYPE,
                    senderIdentity = identity,
                    recipientNodeId = peer.nodeId,
                    recipientPublicKeyBase64 = peer.publicKeyBase64,
                )
            }.getOrElse {
                setStatus("Falha ao enfileirar mensagem segura: ${it.message}")
                return@launch
            }
            setStatus("Mensagem segura ${envelope.packetId.take(8)} enfileirada para ${shortId(peer.nodeId)}")
        }
    }

    private fun observeDeliveries() {
        deliveryJob?.cancel()
        val activeNode = node ?: return
        deliveryJob = scope.launch {
            activeNode.deliveries.collect { envelope ->
                if (envelope.contentType == SecureMeshMessage.OUTER_CONTENT_TYPE) {
                    val opened = runCatching { SecureMeshMessage.open(envelope, identity) }.getOrNull()
                    if (opened?.contentType == TEST_MESSAGE_CONTENT_TYPE) {
                        appendStatus("Mensagem segura recebida de ${shortId(opened.senderNodeId)}: ${opened.payload.decodeToString()}")
                    }
                    return@collect
                }

                val body = runCatching {
                    Base64.decode(envelope.payloadBase64, Base64.NO_WRAP).decodeToString()
                }.getOrDefault("[payload binário]")
                appendStatus("Recebido de ${shortId(envelope.sourceNodeId)}: $body")
            }
        }
    }

    private fun startUpgradeCoordinator() {
        closeUpgradeCoordinator()
        val activeNode = node ?: return
        val coordinator = WifiDirectUpgradeCoordinator(this, activeNode, identity)
        upgradeCoordinator = coordinator
        coordinator.start()

        upgradeEventsJob = scope.launch {
            coordinator.events.collect { event ->
                when (event) {
                    is WifiDirectUpgradeEvent.OfferQueued -> appendStatus("Convite Wi‑Fi enviado a ${shortId(event.peerNodeId)}")
                    is WifiDirectUpgradeEvent.OfferReceived -> appendStatus("Convite Wi‑Fi recebido de ${shortId(event.peerNodeId)}")
                    is WifiDirectUpgradeEvent.GroupConnected -> appendStatus("Grupo Wi‑Fi Direct conectado com ${shortId(event.peerNodeId)}")
                    is WifiDirectUpgradeEvent.SessionOpened -> appendStatus("Canal rápido autenticado com ${shortId(event.peerNodeId)}")
                    is WifiDirectUpgradeEvent.Failed -> appendStatus("Wi‑Fi Direct falhou (${event.reason}); BLE continua ativo")
                }
            }
        }

        upgradeSessionsJob = scope.launch {
            coordinator.sessions.collect { session ->
                fastSession?.close()
                fastSession = session
                session.onFrame { frame ->
                    runOnUiThread {
                        appendStatus("Canal rápido recebeu ${frame.size} bytes: ${frame.decodeToString()}")
                    }
                }
            }
        }
    }

    private fun requestFastChannel() {
        val peer = selectedPeer ?: return setStatus("Nenhum peer verificado para abrir o canal rápido")
        val coordinator = upgradeCoordinator ?: return setStatus("Ative a OpenMesh primeiro")
        val wifi = wifiGuard.snapshot()

        if (wifi.missingPermissions.isNotEmpty()) {
            requestPermissions(wifi.missingPermissions.toTypedArray(), REQUEST_WIFI_PERMISSIONS)
            setStatus("Conceda a permissão de dispositivos Wi‑Fi próximos")
            return
        }
        if (!wifi.wifiEnabled) {
            openWifiSettings()
            setStatus("Ative o Wi‑Fi e volte ao app para tentar novamente")
            return
        }
        if (!wifi.locationModeEnabled) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            setStatus("Ative a localização exigida por esta versão do Android e tente novamente")
            return
        }
        if (!wifi.supported) {
            setStatus("Este aparelho não suporta Wi‑Fi Direct")
            return
        }

        scope.launch {
            setStatus("Negociando canal rápido com ${shortId(peer.nodeId)}…")
            when (val result = coordinator.offer(peer.nodeId)) {
                WifiDirectUpgradeResult.PeerKeyUnavailable -> setStatus("Chave verificada do peer não está disponível")
                is WifiDirectUpgradeResult.PrepareFailed -> setStatus("Falha ao preparar Wi‑Fi Direct: ${result.result}")
                WifiDirectUpgradeResult.DataChannelFailed -> setStatus("Falha ao abrir servidor do canal rápido")
                WifiDirectUpgradeResult.ControlMessageFailed -> setStatus("Falha ao enviar convite E2E")
                is WifiDirectUpgradeResult.ConnectionFailed -> setStatus("Falha ao formar grupo Wi‑Fi: ${result.result}")
                is WifiDirectUpgradeResult.Connected -> appendStatus("Grupo formado; autenticando sessão ${result.sessionId.take(8)}…")
            }
        }
    }

    private fun sendFastPing() {
        val session = fastSession ?: return setStatus("Nenhum canal rápido autenticado aberto")
        scope.launch {
            val payload = "FAST_TEST|${System.currentTimeMillis()}|$nodeId".encodeToByteArray()
            if (session.sendFrame(payload)) {
                setStatus("Teste rápido enviado: ${payload.size} bytes")
            } else {
                setStatus("Falha ao enviar pelo canal rápido")
            }
        }
    }

    private fun openWifiSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        startActivity(intent)
    }

    private fun closeUpgradeCoordinator() {
        fastSession?.close()
        fastSession = null
        upgradeEventsJob?.cancel()
        upgradeSessionsJob?.cancel()
        upgradeEventsJob = null
        upgradeSessionsJob = null
        upgradeCoordinator?.close()
        upgradeCoordinator = null
    }

    private fun stopObservers() {
        deliveryJob?.cancel()
        peerRefreshJob?.cancel()
        deliveryJob = null
        peerRefreshJob = null
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun appendStatus(text: String) {
        status.text = "$text\n\n${status.text}".take(MAX_STATUS_CHARS)
    }

    private fun shortId(value: String): String =
        if (value.length <= 14) value else "${value.take(8)}…${value.takeLast(4)}"

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    ensureMeshReady()
                } else {
                    setStatus("Permissões negadas; algumas funções da mesh ficarão indisponíveis")
                }
            }
            REQUEST_WIFI_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    requestFastChannel()
                } else {
                    setStatus("Permissão Wi‑Fi negada; BLE continua funcionando")
                }
            }
        }
    }

    @Deprecated("Legacy activity result is sufficient for the dependency-light demo app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) ensureMeshReady()
    }

    override fun onDestroy() {
        stopObservers()
        closeUpgradeCoordinator()
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
        private const val REQUEST_WIFI_PERMISSIONS = 1003
        private const val TEST_MESSAGE_CONTENT_TYPE = "text/vnd.openmesh.demo"
        private const val MAX_STATUS_CHARS = 8_000
    }
}
