package com.openmesh.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import com.openmesh.core.MeshCrypto
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshEnvelopeCodec
import com.openmesh.core.MeshNodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Receives OpenMesh envelopes and exposes compact peer identity over BLE GATT. */
class BleMeshGattServer(
    context: Context,
    private val localNodeId: String,
    private val localPublicKeyBase64: String? = null,
    private val onEnvelope: suspend (MeshEnvelope) -> Unit,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val assembler = BleFrameAssembler()
    private var server: BluetoothGattServer? = null

    private val callback = object : BluetoothGattServerCallback() {
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
                    localPublicKeyBase64?.encodeToByteArray()

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
            if (characteristic.uuid != BleMeshProtocol.RX_CHARACTERISTIC_UUID || preparedWrite || offset != 0) {
                if (responseNeeded) {
                    sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
                return
            }

            val accepted = runCatching {
                assembler.accept(value)?.let { completed ->
                    val envelope = MeshEnvelopeCodec.decode(completed)
                    scope.launch { onEnvelope(envelope) }
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
        if (localPublicKeyBase64 != null && MeshCrypto.nodeId(localPublicKeyBase64) != localNodeId) return false

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
        val publicKey = localPublicKeyBase64?.let {
            BluetoothGattCharacteristic(
                BleMeshProtocol.PUBLIC_KEY_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        }

        val service = BluetoothGattService(
            BleMeshProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply {
            addCharacteristic(rx)
            addCharacteristic(nodeId)
            publicKey?.let(::addCharacteristic)
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
}
