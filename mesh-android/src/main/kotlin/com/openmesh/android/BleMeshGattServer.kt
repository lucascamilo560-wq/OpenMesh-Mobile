package com.openmesh.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshEnvelopeCodec
import com.openmesh.core.MeshKeyPair
import com.openmesh.core.MeshNodeId
import com.openmesh.core.PeerIdentityProof
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Receives framed bytes and exposes self-certifying peer identity over BLE GATT. */
class BleMeshGattServer private constructor(
    context: Context,
    private val localNodeId: String,
    private val localIdentity: MeshKeyPair? = null,
    private val inboundHandler: BleGattReassembledHandler,
) {
    /** Legacy v1 wrapper: decode remains synchronous before the GATT response. */
    constructor(
        context: Context,
        localNodeId: String,
        localIdentity: MeshKeyPair? = null,
        onEnvelope: suspend (MeshEnvelope) -> Unit,
    ) : this(
        context = context,
        localNodeId = localNodeId,
        localIdentity = localIdentity,
        inboundHandler = legacyBleGattInboundHandler(onEnvelope),
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val assembler = BleFrameAssembler()
    private val identityProofByDevice = ConcurrentHashMap<String, ByteArray>()
    private var server: BluetoothGattServer? = null

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                identityProofByDevice.remove(device.address)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = when (characteristic.uuid) {
                BleMeshProtocol.NODE_ID_CHARACTERISTIC_UUID ->
                    runCatching { MeshNodeId.toAdvertisementBytes(localNodeId) }.getOrNull()

                BleMeshProtocol.PUBLIC_KEY_CHARACTERISTIC_UUID ->
                    localIdentity?.publicKeyBase64?.encodeToByteArray()

                BleMeshProtocol.IDENTITY_PROOF_CHARACTERISTIC_UUID ->
                    identityProofByDevice[device.address]

                else -> null
            }

            if (value == null || offset < 0 || offset > value.size) {
                sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }

            sendResponse(
                device = device,
                requestId = requestId,
                status = BluetoothGatt.GATT_SUCCESS,
                offset = offset,
                value = value.copyOfRange(offset, value.size),
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == BleMeshProtocol.IDENTITY_CHALLENGE_CHARACTERISTIC_UUID) {
                val identity = localIdentity
                val accepted = identity != null &&
                    !preparedWrite &&
                    offset == 0 &&
                    value.size == PeerIdentityProof.CHALLENGE_BYTES &&
                    runCatching {
                        identityProofByDevice[device.address] =
                            PeerIdentityProof.sign(value, identity).encodeToByteArray()
                        true
                    }.getOrDefault(false)

                if (responseNeeded) {
                    sendResponse(
                        device,
                        requestId,
                        if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        0,
                        null,
                    )
                }
                return
            }

            if (characteristic.uuid != BleMeshProtocol.RX_CHARACTERISTIC_UUID || preparedWrite || offset != 0) {
                if (responseNeeded) {
                    sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
                return
            }

            val accepted = runCatching {
                assembler.accept(value)?.let { completed ->
                    val delivery = inboundHandler.prepare(device.address, completed)
                    scope.launch { delivery() }
                }
                true
            }.getOrDefault(false)

            if (responseNeeded) {
                sendResponse(
                    device = device,
                    requestId = requestId,
                    status = if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset = 0,
                    value = null,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (server != null) return true
        if (!MeshNodeId.isValid(localNodeId)) return false
        if (localIdentity != null && localIdentity.nodeId != localNodeId) return false

        val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return false
        val opened = runCatching { manager.openGattServer(appContext, callback) }.getOrNull() ?: return false

        val rx = BluetoothGattCharacteristic(
            BleMeshProtocol.RX_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val nodeId = BluetoothGattCharacteristic(
            BleMeshProtocol.NODE_ID_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        val service = BluetoothGattService(
            BleMeshProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply {
            addCharacteristic(rx)
            addCharacteristic(nodeId)

            if (localIdentity != null) {
                addCharacteristic(
                    BluetoothGattCharacteristic(
                        BleMeshProtocol.PUBLIC_KEY_CHARACTERISTIC_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ,
                    )
                )
                addCharacteristic(
                    BluetoothGattCharacteristic(
                        BleMeshProtocol.IDENTITY_CHALLENGE_CHARACTERISTIC_UUID,
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE,
                    )
                )
                addCharacteristic(
                    BluetoothGattCharacteristic(
                        BleMeshProtocol.IDENTITY_PROOF_CHARACTERISTIC_UUID,
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ,
                    )
                )
            }
        }

        server = opened
        val added = runCatching { opened.addService(service) }.getOrDefault(false)
        if (!added) {
            opened.close()
            server = null
        }
        return added
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        identityProofByDevice.clear()
        server?.close()
        server = null
    }

    fun close() {
        stop()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun sendResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        runCatching {
            server?.sendResponse(device, requestId, status, offset, value)
        }
    }

    companion object {
        internal fun forOpaqueBytes(
            context: Context,
            localNodeId: String,
            localIdentity: MeshKeyPair? = null,
            onBytes: suspend (deviceAddress: String, bytes: ByteArray) -> Unit,
        ): BleMeshGattServer = BleMeshGattServer(
            context = context,
            localNodeId = localNodeId,
            localIdentity = localIdentity,
            inboundHandler = opaqueBleGattInboundHandler(onBytes),
        )
    }
}

/**
 * Prepares a completed reassembly synchronously, then performs delivery later.
 * This preserves legacy fail-fast decode while allowing an opaque raw path.
 */
internal fun interface BleGattReassembledHandler {
    fun prepare(deviceAddress: String, bytes: ByteArray): suspend () -> Unit
}

internal fun legacyBleGattInboundHandler(
    onEnvelope: suspend (MeshEnvelope) -> Unit,
): BleGattReassembledHandler = BleGattReassembledHandler { _, bytes ->
    val envelope = MeshEnvelopeCodec.decode(bytes)
    val delivery: suspend () -> Unit = { onEnvelope(envelope) }
    delivery
}

internal fun opaqueBleGattInboundHandler(
    onBytes: suspend (deviceAddress: String, bytes: ByteArray) -> Unit,
): BleGattReassembledHandler = BleGattReassembledHandler { deviceAddress, bytes ->
    val immutableBytes = bytes.copyOf()
    val delivery: suspend () -> Unit = { onBytes(deviceAddress, immutableBytes) }
    delivery
}
