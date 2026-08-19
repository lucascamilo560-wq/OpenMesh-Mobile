package com.openmesh.android

import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshEnvelopeCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent full-duplex TCP channel running over a formed Wi-Fi Direct group.
 *
 * The framing layer is payload-agnostic. Mesh envelopes are supported directly,
 * while future media/file protocols can use [WifiDirectSocketSession.sendFrame].
 */
class WifiDirectDataChannel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    suspend fun host(
        port: Int = DEFAULT_PORT,
        onSession: (WifiDirectSocketSession) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (serverSocket != null) return@withContext true
        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        }.getOrNull() ?: return@withContext false

        serverSocket = server
        acceptJob = scope.launch {
            while (isActive && !server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val session = WifiDirectSocketSession(socket, scope)
                onSession(session)
                session.startReading()
            }
        }
        true
    }

    suspend fun join(
        groupOwnerAddress: String,
        port: Int = DEFAULT_PORT,
        connectTimeoutMs: Int = 12_000,
    ): WifiDirectSocketSession? = withContext(Dispatchers.IO) {
        val socket = Socket()
        val connected = runCatching {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(groupOwnerAddress, port), connectTimeoutMs)
            true
        }.getOrDefault(false)
        if (!connected) {
            runCatching { socket.close() }
            return@withContext null
        }
        WifiDirectSocketSession(socket, scope).also { it.startReading() }
    }

    override fun close() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    companion object {
        const val DEFAULT_PORT = 38_651
    }
}

class WifiDirectSocketSession internal constructor(
    private val socket: Socket,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val listeners = mutableListOf<(ByteArray) -> Unit>()
    private val listenerLock = Any()
    private val writeLock = Any()
    private var readJob: Job? = null

    val remoteAddress: String
        get() = socket.inetAddress?.hostAddress.orEmpty()

    fun onFrame(listener: (ByteArray) -> Unit) {
        synchronized(listenerLock) { listeners += listener }
    }

    fun onEnvelope(listener: (MeshEnvelope) -> Unit) {
        onFrame { frame ->
            runCatching { MeshEnvelopeCodec.decode(frame) }
                .getOrNull()
                ?.let(listener)
        }
    }

    fun startReading() {
        if (!started.compareAndSet(false, true)) return
        readJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !closed.get()) {
                    val magic = input.readInt()
                    if (magic != FRAME_MAGIC) break
                    val version = input.readUnsignedByte()
                    if (version != FRAME_VERSION) break
                    val size = input.readInt()
                    if (size !in 0..MAX_FRAME_BYTES) break
                    val frame = ByteArray(size)
                    input.readFully(frame)
                    val snapshot = synchronized(listenerLock) { listeners.toList() }
                    snapshot.forEach { listener -> runCatching { listener(frame) } }
                }
            } catch (_: Exception) {
                // Normal when a peer disappears or the P2P group is torn down.
            } finally {
                close()
            }
        }
    }

    suspend fun sendFrame(frame: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (closed.get() || frame.size > MAX_FRAME_BYTES) return@withContext false
        synchronized(writeLock) {
            runCatching {
                output.writeInt(FRAME_MAGIC)
                output.writeByte(FRAME_VERSION)
                output.writeInt(frame.size)
                output.write(frame)
                output.flush()
                true
            }.getOrDefault(false)
        }
    }

    suspend fun sendEnvelope(envelope: MeshEnvelope): Boolean =
        sendFrame(MeshEnvelopeCodec.encode(envelope))

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        readJob?.cancel()
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }

    companion object {
        private const val FRAME_MAGIC = 0x4F4D5744 // OMWD
        private const val FRAME_VERSION = 1
        private const val MAX_FRAME_BYTES = 32 * 1024 * 1024
    }
}
