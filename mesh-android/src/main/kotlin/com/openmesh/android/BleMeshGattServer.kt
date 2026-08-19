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
import com.openmesh.core.MeshEnvelopeCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Receives OpenMesh envelopes from nearby peers over a writable BLE GATT characteristic. */
class BleMeshGattServer(
    context: Context,
    private val onEnvelope: suspend (com.openmesh.core.MeshEnvelope) -> Unit,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val assembler = BleFrameAssembler()
    private var server: BluetoothGattServer? = null

    private val callback = object : BluetoothGattServerCallback() {
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
                    sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
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
                    device,
                    requestId,
                    if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (server != null) return true
        val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return false
        val opened = runCatching { manager.openGattServer(appContext, callback) }.getOrNull() ?: return false

        val rx = BluetoothGattCharacteristic(
            BleMeshProtocol.RX_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val service = BluetoothGattService(
            BleMeshProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply {
            addCharacteristic(rx)
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
    private fun sendResponse(device: BluetoothDevice, requestId: Int, status: Int) {
        runCatching {
            server?.sendResponse(device, requestId, status, 0, null)
        }
    }
}
