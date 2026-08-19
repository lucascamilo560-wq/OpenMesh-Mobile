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
import android.view.Gravity
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
import com.openmesh.android.MeshTransportEvent
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Real-device functional test UI.
 *
 * Chat messages are kept separate from diagnostic radio logs so field testing
 * can distinguish application delivery from transport state.
 */
class ChatActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var meshGuard: MeshRadioGuard
    private lateinit var wifiGuard: WifiDirectRadioGuard
    private lateinit var nodeLabel: TextView
    private lateinit var peerLabel: TextView
    private lateinit var transportLabel: TextView
    private lateinit var diagnostics: TextView
    private lateinit var messageInput: EditText
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var fastButton: Button
    private lateinit var fastTestButton: Button

    private var node: BleMeshNode? = null
    private var meshService: MeshNodeService? = null
    private var bound = false
    private var selectedPeer: VerifiedPeerMetadata? = null

    private var deliveryJob: Job? = null
    private var transportJob: Job? = null
    private var peerJob: Job? = null
    private var coordinatorEventsJob: Job? = null
    private var coordinatorSessionsJob: Job? = null
    private var upgradeCoordinator: WifiDirectUpgradeCoordinator? = null
    private var fastSession: WifiDirectSocketSession? = null

    private val outgoingViews = ConcurrentHashMap<String, TextView>()

    private val identity by lazy { AndroidMeshIdentityStore(this).loadOrCreate() }
    private val nodeId by lazy { identity.nodeId }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            bound = true
            val binder = service as? MeshNodeService.LocalBinder
            meshService = binder?.service()
            scope.launch {
                repeat(20) {
                    val activeNode = meshService?.currentNode()
                    if (activeNode != null) {
                        attachNode(activeNode)
                        return@launch
                    }
                    delay(250)
                }
                setDiagnostic("Serviço ativo, mas o nó BLE não iniciou.")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            meshService = null
            detachNode()
            setDiagnostic("Serviço OpenMesh desconectado.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        meshGuard = MeshRadioGuard(this)
        wifiGuard = WifiDirectRadioGuard(this)
        setContentView(buildUi())
        updatePeerUi()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }

        val title = TextView(this).apply {
            text = "OpenMesh • teste funcional"
            textSize = 23f
        }
        nodeLabel = TextView(this).apply {
            text = "Meu nó\n$nodeId"
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        }
        peerLabel = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(4))
        }
        transportLabel = TextView(this).apply {
            text = "Rádio: parado"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(12))
        }

        val start = Button(this).apply {
            text = "Ativar OpenMesh"
            setOnClickListener { ensureMeshReady() }
        }
        val refresh = Button(this).apply {
            text = "Atualizar peer"
            setOnClickListener { refreshPeer() }
        }

        val chatTitle = TextView(this).apply {
            text = "Conversa"
            textSize = 20f
            setPadding(0, dp(18), 0, dp(8))
        }
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        chatScroll = ScrollView(this).apply {
            addView(chatContainer)
        }

        messageInput = EditText(this).apply {
            hint = "Digite uma mensagem"
            maxLines = 4
        }
        val send = Button(this).apply {
            text = "Enviar mensagem segura"
            setOnClickListener { sendChatMessage() }
        }

        fastButton = Button(this).apply {
            text = "Abrir canal rápido Wi‑Fi Direct"
            setOnClickListener { requestFastChannel() }
        }
        fastTestButton = Button(this).apply {
            text = "Testar canal rápido"
            isEnabled = false
            setOnClickListener { sendFastTest() }
        }

        val diagnosticTitle = TextView(this).apply {
            text = "Diagnóstico"
            textSize = 18f
            setPadding(0, dp(18), 0, dp(6))
        }
        diagnostics = TextView(this).apply {
            text = "Aguardando ativação."
            textSize = 13f
            setTextIsSelectable(true)
        }
        val stop = Button(this).apply {
            text = "Parar OpenMesh"
            setOnClickListener { stopMesh() }
        }

        root.addView(title, matchWidth())
        root.addView(nodeLabel, matchWidth())
        root.addView(peerLabel, matchWidth())
        root.addView(transportLabel, matchWidth())
        root.addView(start, matchWidth())
        root.addView(refresh, matchWidth())
        root.addView(chatTitle, matchWidth())
        root.addView(
            chatScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(300),
            )
        )
        root.addView(messageInput, matchWidth())
        root.addView(send, matchWidth())
        root.addView(fastButton, matchWidth())
        root.addView(fastTestButton, matchWidth())
        root.addView(diagnosticTitle, matchWidth())
        root.addView(diagnostics, matchWidth())
        root.addView(stop, matchWidth())

        return ScrollView(this).apply { addView(root) }
    }

    private fun ensureMeshReady() {
        val missing = buildList {
            addAll(meshGuard.missingBlePermissions())
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.distinct()

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_MESH_PERMISSIONS)
            setDiagnostic("Aguardando permissões de Bluetooth/dispositivos próximos.")
            return
        }

        val radio = meshGuard.snapshot()
        when {
            !radio.bluetoothAvailable -> setDiagnostic("Bluetooth indisponível neste aparelho.")
            !radio.bluetoothEnabled -> {
                startActivityForResult(meshGuard.bluetoothEnableRequestIntent(), REQUEST_ENABLE_BLUETOOTH)
                setDiagnostic("Ative o Bluetooth para entrar na mesh.")
            }
            !radio.bleAdvertisingSupported -> setDiagnostic("Este aparelho não suporta anúncio BLE necessário.")
            else -> startMesh()
        }
    }

    private fun startMesh() {
        val intent = MeshNodeService.startIntent(this, nodeId)
        startForegroundService(intent)
        if (!bound) bindService(intent, connection, Context.BIND_AUTO_CREATE)
        transportLabel.text = "Rádio: iniciando BLE…"
        setDiagnostic("Iniciando descoberta e GATT.")
    }

    private fun attachNode(activeNode: BleMeshNode) {
        if (node === activeNode) return
        detachNode()
        node = activeNode
        transportLabel.text = "Rádio: BLE ativo • procurando peers"
        setDiagnostic("Mesh ativa. Mensagens novas terão flush imediato + retry automático.")

        deliveryJob = scope.launch {
            activeNode.deliveries.collect { envelope ->
                if (envelope.contentType != SecureMeshMessage.OUTER_CONTENT_TYPE) return@collect
                val opened = runCatching { SecureMeshMessage.open(envelope, identity) }.getOrNull()
                    ?: return@collect
                if (opened.contentType != CHAT_CONTENT_TYPE && opened.contentType != LEGACY_CHAT_CONTENT_TYPE) {
                    return@collect
                }
                addIncomingMessage(opened.senderNodeId, opened.payload.decodeToString())
            }
        }

        transportJob = scope.launch {
            activeNode.transportEvents.collect { event ->
                when (event) {
                    is MeshTransportEvent.Forwarded -> {
                        val view = outgoingViews[event.packetId]
                        val state = if (event.finalDestination) {
                            "✓ entregue ao peer por BLE"
                        } else {
                            "↗ encaminhada por relay ${shortId(event.peerNodeId)}"
                        }
                        if (view != null) {
                            view.text = replaceStateLine(view.text.toString(), state)
                        }
                        appendDiagnostic("Pacote ${event.packetId.take(8)}: $state")
                    }
                    is MeshTransportEvent.SendFailed -> {
                        outgoingViews[event.packetId]?.let { view ->
                            view.text = replaceStateLine(view.text.toString(), "⏳ falhou; retry automático")
                        }
                        appendDiagnostic(
                            "Falha GATT para ${shortId(event.peerNodeId)} no pacote ${event.packetId.take(8)}; mantido na fila."
                        )
                    }
                }
            }
        }

        peerJob = scope.launch {
            while (isActive) {
                refreshPeer()
                delay(1_500)
            }
        }

        startUpgradeCoordinator(activeNode)
    }

    private fun detachNode() {
        deliveryJob?.cancel()
        transportJob?.cancel()
        peerJob?.cancel()
        deliveryJob = null
        transportJob = null
        peerJob = null
        node = null
        closeUpgradeCoordinator()
    }

    private fun refreshPeer() {
        val activeNode = node ?: return
        val peer = activeNode.verifiedPeers()
            .filter { it.nodeId != nodeId }
            .maxByOrNull { it.lastVerifiedAtMs }

        if (peer?.nodeId != selectedPeer?.nodeId) {
            selectedPeer = peer
            updatePeerUi()
            if (peer != null) appendDiagnostic("Peer verificado: ${shortId(peer.nodeId)}")
        } else {
            updatePeerUi()
        }
    }

    private fun updatePeerUi() {
        peerLabel.text = selectedPeer?.let {
            "Peer verificado\n${it.nodeId}"
        } ?: "Peer verificado: nenhum"
    }

    private fun sendChatMessage() {
        val activeNode = node ?: return setDiagnostic("Ative a OpenMesh primeiro.")
        val peer = selectedPeer ?: return setDiagnostic("Nenhum peer verificado disponível.")
        val text = messageInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        scope.launch {
            val envelope = runCatching {
                activeNode.sendSecure(
                    payload = text.encodeToByteArray(),
                    contentType = CHAT_CONTENT_TYPE,
                    senderIdentity = identity,
                    recipientNodeId = peer.nodeId,
                    recipientPublicKeyBase64 = peer.publicKeyBase64,
                )
            }.getOrElse {
                setDiagnostic("Falha ao criar mensagem E2E: ${it.message}")
                return@launch
            }

            val view = addOutgoingMessage(text, "⏳ na fila • flush imediato")
            outgoingViews[envelope.packetId] = view
            messageInput.text?.clear()
            appendDiagnostic("Mensagem ${envelope.packetId.take(8)} criada para ${shortId(peer.nodeId)}.")
        }
    }

    private fun addOutgoingMessage(text: String, state: String): TextView {
        val view = TextView(this).apply {
            this.text = "Você\n$text\n$state"
            textSize = 16f
            gravity = Gravity.END
            setPadding(dp(48), dp(10), dp(6), dp(10))
        }
        chatContainer.addView(view, matchWidth())
        scrollChatBottom()
        return view
    }

    private fun addIncomingMessage(senderNodeId: String, text: String) {
        val view = TextView(this).apply {
            this.text = "${shortId(senderNodeId)}\n$text\n🔒 recebida e descriptografada"
            textSize = 16f
            gravity = Gravity.START
            setPadding(dp(6), dp(10), dp(48), dp(10))
        }
        chatContainer.addView(view, matchWidth())
        scrollChatBottom()
        appendDiagnostic("Mensagem recebida e aberta de ${shortId(senderNodeId)}.")
    }

    private fun replaceStateLine(current: String, state: String): String {
        val lines = current.lines().toMutableList()
        if (lines.size >= 3) lines[lines.lastIndex] = state else lines += state
        return lines.joinToString("\n")
    }

    private fun scrollChatBottom() {
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun startUpgradeCoordinator(activeNode: BleMeshNode) {
        closeUpgradeCoordinator()
        val coordinator = WifiDirectUpgradeCoordinator(this, activeNode, identity)
        upgradeCoordinator = coordinator
        coordinator.start()

        coordinatorEventsJob = scope.launch {
            coordinator.events.collect { event ->
                when (event) {
                    is WifiDirectUpgradeEvent.OfferQueued ->
                        appendDiagnostic("Convite Wi‑Fi E2E enviado a ${shortId(event.peerNodeId)}.")
                    is WifiDirectUpgradeEvent.OfferReceived -> {
                        fastButton.isEnabled = false
                        appendDiagnostic("Convite Wi‑Fi recebido de ${shortId(event.peerNodeId)}; entrando no grupo…")
                    }
                    is WifiDirectUpgradeEvent.GroupConnected ->
                        appendDiagnostic("Peer realmente entrou no grupo Wi‑Fi Direct: ${shortId(event.peerNodeId)}.")
                    is WifiDirectUpgradeEvent.SessionOpened -> {
                        transportLabel.text = "Rádio: Wi‑Fi Direct autenticado ⚡ + BLE"
                        fastButton.isEnabled = true
                        fastTestButton.isEnabled = true
                        appendDiagnostic("Canal rápido autenticado com ${shortId(event.peerNodeId)}.")
                    }
                    is WifiDirectUpgradeEvent.Failed -> {
                        fastButton.isEnabled = true
                        appendDiagnostic("Wi‑Fi Direct falhou: ${event.reason}. BLE continua ativo.")
                    }
                }
            }
        }

        coordinatorSessionsJob = scope.launch {
            coordinator.sessions.collect { session ->
                fastSession?.close()
                fastSession = session
                fastTestButton.isEnabled = true
                session.onFrame { frame ->
                    runOnUiThread {
                        appendDiagnostic("Canal rápido recebeu ${frame.size} bytes: ${frame.decodeToString()}")
                    }
                }
            }
        }
    }

    private fun requestFastChannel() {
        val peer = selectedPeer ?: return setDiagnostic("Nenhum peer para o canal rápido.")
        val coordinator = upgradeCoordinator ?: return setDiagnostic("Ative a mesh primeiro.")
        val wifi = wifiGuard.snapshot()

        if (wifi.missingPermissions.isNotEmpty()) {
            requestPermissions(wifi.missingPermissions.toTypedArray(), REQUEST_WIFI_PERMISSIONS)
            return
        }
        if (!wifi.wifiEnabled) {
            openWifiSettings()
            setDiagnostic("Ative o Wi‑Fi e tente novamente.")
            return
        }
        if (!wifi.locationModeEnabled) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            setDiagnostic("Ative a localização exigida por este aparelho para Wi‑Fi Direct.")
            return
        }
        if (!wifi.supported) {
            setDiagnostic("Wi‑Fi Direct não suportado neste aparelho.")
            return
        }

        fastButton.isEnabled = false
        scope.launch {
            appendDiagnostic("Criando grupo rápido para ${shortId(peer.nodeId)}…")
            when (val result = coordinator.offer(peer.nodeId)) {
                WifiDirectUpgradeResult.PeerKeyUnavailable -> setDiagnostic("Chave do peer indisponível.")
                is WifiDirectUpgradeResult.PrepareFailed -> {
                    fastButton.isEnabled = true
                    setDiagnostic("Falha ao preparar Wi‑Fi Direct: ${wifiReason(result.result.toString())}")
                }
                WifiDirectUpgradeResult.DataChannelFailed -> {
                    fastButton.isEnabled = true
                    setDiagnostic("Falha ao abrir socket do canal rápido.")
                }
                WifiDirectUpgradeResult.ControlMessageFailed -> {
                    fastButton.isEnabled = true
                    setDiagnostic("Falha ao colocar convite E2E na mesh.")
                }
                is WifiDirectUpgradeResult.ConnectionFailed -> {
                    fastButton.isEnabled = true
                    setDiagnostic("Peer não entrou no grupo: ${wifiReason(result.result.toString())}")
                }
                is WifiDirectUpgradeResult.Connected ->
                    appendDiagnostic("Peer presente no grupo; autenticando sessão ${result.sessionId.take(8)}…")
            }
        }
    }

    private fun sendFastTest() {
        val session = fastSession ?: return setDiagnostic("Nenhum canal rápido autenticado.")
        scope.launch {
            val payload = "OPENMESH_FAST_TEST|${System.currentTimeMillis()}|$nodeId".encodeToByteArray()
            if (session.sendFrame(payload)) {
                appendDiagnostic("Enviado pelo canal rápido: ${payload.size} bytes.")
            } else {
                setDiagnostic("Falha ao escrever no canal rápido.")
            }
        }
    }

    private fun wifiReason(raw: String): String = when {
        "reason=2" in raw -> "BUSY (rádio ocupado); o SDK já tentou limpeza e retry"
        "reason=1" in raw -> "P2P_UNSUPPORTED"
        "reason=0" in raw -> "ERROR interno"
        else -> raw
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
        fastTestButton.takeIf { ::fastTestButton.isInitialized }?.isEnabled = false
        coordinatorEventsJob?.cancel()
        coordinatorSessionsJob?.cancel()
        coordinatorEventsJob = null
        coordinatorSessionsJob = null
        upgradeCoordinator?.close()
        upgradeCoordinator = null
    }

    private fun stopMesh() {
        detachNode()
        selectedPeer = null
        updatePeerUi()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        meshService = null
        startService(MeshNodeService.stopIntent(this))
        transportLabel.text = "Rádio: parado"
        setDiagnostic("OpenMesh parada.")
    }

    private fun setDiagnostic(text: String) {
        diagnostics.text = text
    }

    private fun appendDiagnostic(text: String) {
        diagnostics.text = "$text\n${diagnostics.text}".take(MAX_DIAGNOSTIC_CHARS)
    }

    private fun shortId(value: String): String =
        if (value.length <= 16) value else "${value.take(8)}…${value.takeLast(4)}"

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_MESH_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    ensureMeshReady()
                } else {
                    setDiagnostic("Permissões BLE negadas; este aparelho não pode participar da mesh.")
                }
            }
            REQUEST_WIFI_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    requestFastChannel()
                } else {
                    setDiagnostic("Permissão Wi‑Fi negada; BLE continua disponível.")
                }
            }
        }
    }

    @Deprecated("Dependency-light test app uses the legacy activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) ensureMeshReady()
    }

    override fun onDestroy() {
        detachNode()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MESH_PERMISSIONS = 2101
        private const val REQUEST_ENABLE_BLUETOOTH = 2102
        private const val REQUEST_WIFI_PERMISSIONS = 2103
        private const val CHAT_CONTENT_TYPE = "text/vnd.openmesh.chat-v1"
        private const val LEGACY_CHAT_CONTENT_TYPE = "text/vnd.openmesh.demo"
        private const val MAX_DIAGNOSTIC_CHARS = 10_000
    }
}
