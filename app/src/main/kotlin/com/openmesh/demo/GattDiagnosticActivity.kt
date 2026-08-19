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

class GattDiagnosticActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var meshGuard: MeshRadioGuard
    private lateinit var wifiGuard: WifiDirectRadioGuard
    private lateinit var nodeLabel: TextView
    private lateinit var peerLabel: TextView
    private lateinit var radioLabel: TextView
    private lateinit var chatBox: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var diagnostics: TextView
    private lateinit var fastButton: Button
    private lateinit var fastTestButton: Button

    private var bound = false
    private var service: MeshNodeService? = null
    private var node: BleMeshNode? = null
    private var selectedPeer: VerifiedPeerMetadata? = null
    private var deliveryJob: Job? = null
    private var transportJob: Job? = null
    private var peerJob: Job? = null
    private var upgradeEventJob: Job? = null
    private var upgradeSessionJob: Job? = null
    private var coordinator: WifiDirectUpgradeCoordinator? = null
    private var fastSession: WifiDirectSocketSession? = null

    private val outgoing = ConcurrentHashMap<String, TextView>()
    private val identity by lazy { AndroidMeshIdentityStore(this).loadOrCreate() }
    private val nodeId by lazy { identity.nodeId }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            service = (binder as? MeshNodeService.LocalBinder)?.service()
            scope.launch {
                repeat(30) {
                    service?.currentNode()?.let {
                        attachNode(it)
                        return@launch
                    }
                    delay(200)
                }
                log("ERRO: serviço iniciou, mas o nó BLE não ficou disponível")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            detachNode()
            log("Serviço OpenMesh desconectado")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        meshGuard = MeshRadioGuard(this)
        wifiGuard = WifiDirectRadioGuard(this)
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "OpenMesh 0.3.1 • GATT diagnóstico"
            textSize = 22f
        }, matchWidth())

        nodeLabel = TextView(this).apply {
            text = "Meu nó:\n$nodeId"
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(6))
        }
        peerLabel = TextView(this).apply {
            text = "Peer verificado: nenhum"
            textSize = 14f
            setTextIsSelectable(true)
        }
        radioLabel = TextView(this).apply {
            text = "Rádio: parado"
            textSize = 15f
            setPadding(0, dp(6), 0, dp(8))
        }
        root.addView(nodeLabel, matchWidth())
        root.addView(peerLabel, matchWidth())
        root.addView(radioLabel, matchWidth())

        root.addView(Button(this).apply {
            text = "Ativar OpenMesh"
            setOnClickListener { ensureMeshReady() }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Atualizar peer"
            setOnClickListener { refreshPeer() }
        }, matchWidth())

        root.addView(TextView(this).apply {
            text = "Conversa"
            textSize = 19f
            setPadding(0, dp(14), 0, dp(4))
        }, matchWidth())
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        chatScroll = ScrollView(this).apply { addView(chatBox) }
        root.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)))

        input = EditText(this).apply {
            hint = "Digite uma mensagem"
            setText("oi pai")
            maxLines = 3
        }
        root.addView(input, matchWidth())
        root.addView(Button(this).apply {
            text = "Enviar mensagem segura"
            setOnClickListener { sendMessage() }
        }, matchWidth())

        fastButton = Button(this).apply {
            text = "Abrir canal rápido Wi‑Fi Direct"
            setOnClickListener { requestFastChannel() }
        }
        fastTestButton = Button(this).apply {
            text = "Testar canal rápido"
            isEnabled = false
            setOnClickListener { sendFastTest() }
        }
        root.addView(fastButton, matchWidth())
        root.addView(fastTestButton, matchWidth())

        root.addView(TextView(this).apply {
            text = "Diagnóstico detalhado"
            textSize = 18f
            setPadding(0, dp(14), 0, dp(4))
        }, matchWidth())
        diagnostics = TextView(this).apply {
            text = "Aguardando ativação"
            textSize = 12f
            setTextIsSelectable(true)
        }
        root.addView(diagnostics, matchWidth())
        root.addView(Button(this).apply {
            text = "Limpar diagnóstico"
            setOnClickListener { diagnostics.text = "" }
        }, matchWidth())
        root.addView(Button(this).apply {
            text = "Parar OpenMesh"
            setOnClickListener { stopMesh() }
        }, matchWidth())

        return ScrollView(this).apply { addView(root) }
    }

    private fun ensureMeshReady() {
        val missing = buildList {
            addAll(meshGuard.missingBlePermissions())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }.distinct()

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_MESH)
            log("Aguardando permissões BLE")
            return
        }
        val snapshot = meshGuard.snapshot()
        when {
            !snapshot.bluetoothAvailable -> log("Bluetooth indisponível")
            !snapshot.bluetoothEnabled -> {
                startActivityForResult(meshGuard.bluetoothEnableRequestIntent(), REQ_BT)
                log("Aguardando ativação do Bluetooth")
            }
            !snapshot.bleAdvertisingSupported -> log("Advertising BLE não suportado")
            else -> startMesh()
        }
    }

    private fun startMesh() {
        val intent = MeshNodeService.startIntent(this, nodeId)
        startForegroundService(intent)
        if (!bound) bindService(intent, connection, Context.BIND_AUTO_CREATE)
        radioLabel.text = "Rádio: iniciando BLE…"
        log("Iniciando descoberta + GATT")
    }

    private fun attachNode(active: BleMeshNode) {
        if (node === active) return
        detachNode()
        node = active
        radioLabel.text = "Rádio: BLE ativo"
        log("Nó BLE anexado. Transporte usa MTU padrão 23 nesta versão de diagnóstico.")

        deliveryJob = scope.launch {
            active.deliveries.collect { envelope ->
                if (envelope.contentType != SecureMeshMessage.OUTER_CONTENT_TYPE) return@collect
                val opened = runCatching { SecureMeshMessage.open(envelope, identity) }
                    .getOrElse {
                        log("RECEBIDO mas falhou ao abrir E2E: ${it.message}")
                        return@collect
                    }
                if (opened.contentType != CHAT_TYPE && opened.contentType != LEGACY_CHAT_TYPE) return@collect
                addIncoming(opened.senderNodeId, opened.payload.decodeToString())
            }
        }

        transportJob = scope.launch {
            active.transportEvents.collect { event ->
                when (event) {
                    is MeshTransportEvent.Forwarded -> {
                        outgoing[event.packetId]?.let { view ->
                            view.text = replaceState(
                                view.text.toString(),
                                "✓ GATT OK • ${event.frameCount} frames"
                            )
                        }
                        log("OK ${event.packetId.take(8)} → ${shortId(event.peerNodeId)}; frames=${event.frameCount}; final=${event.finalDestination}")
                    }
                    is MeshTransportEvent.SendFailed -> {
                        val frameIndex = event.frameIndex
                        val frameCount = event.frameCount
                        val frame = if (frameIndex != null && frameCount != null) {
                            "${frameIndex + 1}/$frameCount"
                        } else "-"
                        val failure = "${event.stage} status=${event.status ?: "-"} frame=$frame detail=${event.detail ?: "-"}"
                        outgoing[event.packetId]?.let { view ->
                            view.text = replaceState(view.text.toString(), "⏳ $failure • retry")
                        }
                        log("FALHA ${event.packetId.take(8)} → ${shortId(event.peerNodeId)} | $failure | mantido na fila")
                    }
                }
            }
        }

        peerJob = scope.launch {
            while (isActive) {
                refreshPeer()
                delay(1_200)
            }
        }
        startCoordinator(active)
    }

    private fun refreshPeer() {
        val p = node?.verifiedPeers()
            ?.filter { it.nodeId != nodeId }
            ?.maxByOrNull { it.lastVerifiedAtMs }
        if (p?.nodeId != selectedPeer?.nodeId) {
            selectedPeer = p
            peerLabel.text = p?.let { "Peer verificado:\n${it.nodeId}" } ?: "Peer verificado: nenhum"
            if (p != null) log("Peer verificado ${shortId(p.nodeId)}")
        }
    }

    private fun sendMessage() {
        val active = node ?: return log("Ative a OpenMesh primeiro")
        val peer = selectedPeer ?: return log("Nenhum peer verificado")
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        scope.launch {
            val envelope = runCatching {
                active.sendSecure(
                    payload = text.encodeToByteArray(),
                    contentType = CHAT_TYPE,
                    senderIdentity = identity,
                    recipientNodeId = peer.nodeId,
                    recipientPublicKeyBase64 = peer.publicKeyBase64,
                )
            }.getOrElse {
                log("Erro criando E2E: ${it.message}")
                return@launch
            }
            outgoing[envelope.packetId] = addOutgoing(text, "⏳ criado • aguardando GATT")
            log("Criado ${envelope.packetId.take(8)} para ${shortId(peer.nodeId)}")
        }
    }

    private fun addOutgoing(text: String, state: String): TextView {
        val view = TextView(this).apply {
            this.text = "Você\n$text\n$state"
            textSize = 15f
            gravity = Gravity.END
            setPadding(dp(40), dp(8), dp(4), dp(8))
        }
        chatBox.addView(view, matchWidth())
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return view
    }

    private fun addIncoming(sender: String, text: String) {
        val view = TextView(this).apply {
            this.text = "${shortId(sender)}\n$text\n🔒 recebida + descriptografada"
            textSize = 15f
            gravity = Gravity.START
            setPadding(dp(4), dp(8), dp(40), dp(8))
        }
        chatBox.addView(view, matchWidth())
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        log("CHAT recebido de ${shortId(sender)}")
    }

    private fun replaceState(current: String, state: String): String {
        val lines = current.lines().toMutableList()
        if (lines.size >= 3) lines[lines.lastIndex] = state else lines += state
        return lines.joinToString("\n")
    }

    private fun startCoordinator(active: BleMeshNode) {
        closeCoordinator()
        val c = WifiDirectUpgradeCoordinator(this, active, identity)
        coordinator = c
        c.start()
        upgradeEventJob = scope.launch {
            c.events.collect { event ->
                when (event) {
                    is WifiDirectUpgradeEvent.OfferQueued -> log("Wi‑Fi: convite E2E enfileirado para ${shortId(event.peerNodeId)}")
                    is WifiDirectUpgradeEvent.OfferReceived -> {
                        fastButton.isEnabled = false
                        log("Wi‑Fi: convite RECEBIDO de ${shortId(event.peerNodeId)}")
                    }
                    is WifiDirectUpgradeEvent.GroupConnected -> log("Wi‑Fi: peer entrou no grupo ${shortId(event.peerNodeId)}")
                    is WifiDirectUpgradeEvent.SessionOpened -> {
                        radioLabel.text = "Rádio: BLE + Wi‑Fi Direct autenticado ⚡"
                        fastButton.isEnabled = true
                        fastTestButton.isEnabled = true
                        log("Wi‑Fi: sessão autenticada com ${shortId(event.peerNodeId)}")
                    }
                    is WifiDirectUpgradeEvent.Failed -> {
                        fastButton.isEnabled = true
                        log("Wi‑Fi FALHA: ${event.reason}; BLE permanece ativo")
                    }
                }
            }
        }
        upgradeSessionJob = scope.launch {
            c.sessions.collect { session ->
                fastSession?.close()
                fastSession = session
                fastTestButton.isEnabled = true
                session.onFrame { bytes ->
                    runOnUiThread { log("FAST RX ${bytes.size} bytes: ${bytes.decodeToString()}") }
                }
            }
        }
    }

    private fun requestFastChannel() {
        val peer = selectedPeer ?: return log("Nenhum peer para Wi‑Fi Direct")
        val c = coordinator ?: return log("Ative a mesh primeiro")
        val wifi = wifiGuard.snapshot()
        if (wifi.missingPermissions.isNotEmpty()) {
            requestPermissions(wifi.missingPermissions.toTypedArray(), REQ_WIFI)
            return
        }
        if (!wifi.wifiEnabled) {
            openWifiSettings()
            log("Ative o Wi‑Fi e tente novamente")
            return
        }
        if (!wifi.locationModeEnabled) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            log("Ative a localização exigida por este aparelho")
            return
        }
        if (!wifi.supported) return log("Wi‑Fi Direct não suportado")

        fastButton.isEnabled = false
        scope.launch {
            log("Wi‑Fi: preparando grupo para ${shortId(peer.nodeId)}")
            when (val result = c.offer(peer.nodeId)) {
                WifiDirectUpgradeResult.PeerKeyUnavailable -> { fastButton.isEnabled = true; log("Wi‑Fi: chave do peer indisponível") }
                is WifiDirectUpgradeResult.PrepareFailed -> { fastButton.isEnabled = true; log("Wi‑Fi prepare falhou: ${result.result}") }
                WifiDirectUpgradeResult.DataChannelFailed -> { fastButton.isEnabled = true; log("Wi‑Fi socket servidor falhou") }
                WifiDirectUpgradeResult.ControlMessageFailed -> { fastButton.isEnabled = true; log("Wi‑Fi convite E2E não entrou na mesh") }
                is WifiDirectUpgradeResult.ConnectionFailed -> { fastButton.isEnabled = true; log("Wi‑Fi conexão falhou: ${result.result}") }
                is WifiDirectUpgradeResult.Connected -> log("Wi‑Fi peer presente; autenticando ${result.sessionId.take(8)}")
            }
        }
    }

    private fun sendFastTest() {
        val session = fastSession ?: return log("Nenhum canal rápido autenticado")
        scope.launch {
            val bytes = "FAST_TEST|${System.currentTimeMillis()}|$nodeId".encodeToByteArray()
            log(if (session.sendFrame(bytes)) "FAST TX ${bytes.size} bytes OK" else "FAST TX falhou")
        }
    }

    private fun openWifiSettings() {
        startActivity(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
            else Intent(Settings.ACTION_WIFI_SETTINGS)
        )
    }

    private fun log(text: String) {
        diagnostics.text = "$text\n${diagnostics.text}".take(12_000)
    }

    private fun stopMesh() {
        detachNode()
        selectedPeer = null
        peerLabel.text = "Peer verificado: nenhum"
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
        startService(MeshNodeService.stopIntent(this))
        radioLabel.text = "Rádio: parado"
        log("OpenMesh parada")
    }

    private fun detachNode() {
        deliveryJob?.cancel(); transportJob?.cancel(); peerJob?.cancel()
        deliveryJob = null; transportJob = null; peerJob = null
        node = null
        closeCoordinator()
    }

    private fun closeCoordinator() {
        fastSession?.close(); fastSession = null
        upgradeEventJob?.cancel(); upgradeSessionJob?.cancel()
        upgradeEventJob = null; upgradeSessionJob = null
        coordinator?.close(); coordinator = null
        if (::fastTestButton.isInitialized) fastTestButton.isEnabled = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_MESH -> if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) ensureMeshReady() else log("Permissão BLE negada")
            REQ_WIFI -> if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) requestFastChannel() else log("Permissão Wi‑Fi negada")
        }
    }

    @Deprecated("Diagnostic app keeps dependencies minimal")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_BT) ensureMeshReady()
    }

    override fun onDestroy() {
        detachNode()
        if (bound) unbindService(connection)
        bound = false
        scope.cancel()
        super.onDestroy()
    }

    private fun shortId(v: String) = if (v.length <= 16) v else "${v.take(8)}…${v.takeLast(4)}"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object {
        private const val REQ_MESH = 3101
        private const val REQ_BT = 3102
        private const val REQ_WIFI = 3103
        private const val CHAT_TYPE = "text/vnd.openmesh.chat"
        private const val LEGACY_CHAT_TYPE = "text/vnd.openmesh.demo"
    }
}
