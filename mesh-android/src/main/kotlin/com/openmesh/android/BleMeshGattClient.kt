package com.openmesh.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.openmesh.core.MeshEnvelope
import com.openmesh.core.MeshEnvelopeCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Sends one OpenMesh envelope to one nearby BLE peer. */
class BleMeshGattClient(
    context: Context,
) {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    suspend fun send(
        deviceAddress: String,
        envelope: MeshEnvelope,
        timeoutMs: Long = 15_000,
    ): Boolean {
        val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        val device = runCatching { adapter.getRemoteDevice(deviceAddress) }.getOrNull() ?: return false
        val completion = CompletableDeferred<Boolean>()
        val encodedEnvelope = MeshEnvelopeCodec.encode(envelope)

        var gatt: BluetoothGatt? = null
        var rx: BluetoothGattCharacteristic? = null
        var frames: List<ByteArray> = emptyList()
        var nextFrameIndex = 0
        var finished = false

        fun finish(success: Boolean) {
            if (finished) return
            finished = true
            completion.complete(success)
        }

        fun prepareFrames(mtu: Int) {
            val attPayload = (mtu - 3).coerceAtLeast(BleFrameCodec.HEADER_SIZE + 1)
            frames = BleFrameCodec.chunk(encodedEnvelope, attPayload)
            nextFrameIndex = 0
        }

        @SuppressLint("MissingPermission")
        fun writeNext(): Boolean {
            val activeGatt = gatt ?: return false
            val characteristic = rx ?: return false
            if (nextFrameIndex >= frames.size) {
                finish(true)
                return true
            }

            val value = frames[nextFrameIndex]
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activeGatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                activeGatt.writeCharacteristic(characteristic)
            }

            if (!accepted) finish(false)
            return accepted
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gattCallback: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(false)
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (!gattCallback.discoverServices()) finish(false)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> if (!finished) finish(false)
                }
            }

            override fun onServicesDiscovered(gattCallback: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(false)
                    return
                }
                val service: BluetoothGattService = gattCallback.getService(BleMeshProtocol.SERVICE_UUID)
                    ?: run {
                        finish(false)
                        return
                    }
                rx = service.getCharacteristic(BleMeshProtocol.RX_CHARACTERISTIC_UUID)
                    ?: run {
                        finish(false)
                        return
                    }

                if (!gattCallback.requestMtu(PREFERRED_MTU)) {
                    prepareFrames(DEFAULT_MTU)
                    writeNext()
                }
            }

            override fun onMtuChanged(gattCallback: BluetoothGatt, mtu: Int, status: Int) {
                prepareFrames(if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU)
                writeNext()
            }

            override fun onCharacteristicWrite(
                gattCallback: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (characteristic.uuid != BleMeshProtocol.RX_CHARACTERISTIC_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(false)
                    return
                }
                nextFrameIndex += 1
                writeNext()
            }
        }

        gatt = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, callback)
            }
        }.getOrNull() ?: return false

        return try {
            withTimeoutOrNull(timeoutMs) { completion.await() } ?: false
        } finally {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
    }

    companion object {
        private const val DEFAULT_MTU = 23
        private const val PREFERRED_MTU = 247
    }
}
