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

/**
 * Sends one OpenMesh envelope to one nearby BLE peer.
 *
 * For the first real-device transport path we deliberately use the default ATT
 * MTU (23) instead of immediately negotiating a larger MTU. This creates more
 * frames but is considerably more conservative across Android BLE stacks.
 */
class BleMeshGattClient(
    context: Context,
) {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    suspend fun send(
        deviceAddress: String,
        envelope: MeshEnvelope,
        timeoutMs: Long = 30_000,
    ): BleGattSendResult {
        val manager = appContext.getSystemService(BluetoothManager::class.java)
            ?: return BleGattSendResult.Failed(BleGattStage.CONNECT, detail = "BluetoothManager unavailable")
        val adapter = manager.adapter
            ?: return BleGattSendResult.Failed(BleGattStage.CONNECT, detail = "BluetoothAdapter unavailable")
        val device = runCatching { adapter.getRemoteDevice(deviceAddress) }.getOrNull()
            ?: return BleGattSendResult.Failed(BleGattStage.CONNECT, detail = "Invalid/stale device address")

        val completion = CompletableDeferred<BleGattSendResult>()
        val encodedEnvelope = MeshEnvelopeCodec.encode(envelope)

        var gatt: BluetoothGatt? = null
        var rx: BluetoothGattCharacteristic? = null
        var frames: List<ByteArray> = emptyList()
        var nextFrameIndex = 0
        var finished = false
        var stage = BleGattStage.CONNECT

        fun finish(result: BleGattSendResult) {
            if (finished) return
            finished = true
            completion.complete(result)
        }

        fun prepareFrames(): Boolean {
            stage = BleGattStage.PREPARE_FRAMES
            val attPayload = (DEFAULT_MTU - 3).coerceAtLeast(BleFrameCodec.HEADER_SIZE + 1)
            return runCatching {
                frames = BleFrameCodec.chunk(encodedEnvelope, attPayload)
                nextFrameIndex = 0
                true
            }.getOrElse { error ->
                finish(BleGattSendResult.Failed(stage, detail = error.message))
                false
            }
        }

        @SuppressLint("MissingPermission")
        fun writeNext(): Boolean {
            val activeGatt = gatt ?: run {
                finish(BleGattSendResult.Failed(BleGattStage.WRITE_FRAME, detail = "GATT handle missing"))
                return false
            }
            val characteristic = rx ?: run {
                finish(BleGattSendResult.Failed(BleGattStage.WRITE_FRAME, detail = "RX characteristic missing"))
                return false
            }

            if (nextFrameIndex >= frames.size) {
                finish(BleGattSendResult.Success(frames.size))
                return true
            }

            stage = BleGattStage.WRITE_FRAME
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

            if (!accepted) {
                finish(
                    BleGattSendResult.Failed(
                        stage = BleGattStage.WRITE_FRAME,
                        frameIndex = nextFrameIndex,
                        frameCount = frames.size,
                        detail = "writeCharacteristic rejected",
                    )
                )
            }
            return accepted
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(activeGatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(BleGattSendResult.Failed(BleGattStage.CONNECT, status = status))
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        stage = BleGattStage.DISCOVER_SERVICES
                        if (!activeGatt.discoverServices()) {
                            finish(
                                BleGattSendResult.Failed(
                                    stage = BleGattStage.DISCOVER_SERVICES,
                                    detail = "discoverServices returned false",
                                )
                            )
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> if (!finished) {
                        finish(
                            BleGattSendResult.Failed(
                                stage = stage,
                                detail = "Disconnected before transfer completed",
                            )
                        )
                    }
                }
            }

            override fun onServicesDiscovered(activeGatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(BleGattSendResult.Failed(BleGattStage.DISCOVER_SERVICES, status = status))
                    return
                }

                stage = BleGattStage.FIND_SERVICE
                val service: BluetoothGattService = activeGatt.getService(BleMeshProtocol.SERVICE_UUID)
                    ?: run {
                        finish(
                            BleGattSendResult.Failed(
                                stage = BleGattStage.FIND_SERVICE,
                                detail = "OpenMesh service not found",
                            )
                        )
                        return
                    }

                stage = BleGattStage.FIND_RX
                rx = service.getCharacteristic(BleMeshProtocol.RX_CHARACTERISTIC_UUID)
                    ?: run {
                        finish(
                            BleGattSendResult.Failed(
                                stage = BleGattStage.FIND_RX,
                                detail = "OpenMesh RX characteristic not found",
                            )
                        )
                        return
                    }

                if (prepareFrames()) writeNext()
            }

            override fun onCharacteristicWrite(
                activeGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (characteristic.uuid != BleMeshProtocol.RX_CHARACTERISTIC_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(
                        BleGattSendResult.Failed(
                            stage = BleGattStage.WRITE_FRAME,
                            status = status,
                            frameIndex = nextFrameIndex,
                            frameCount = frames.size,
                        )
                    )
                    return
                }
                nextFrameIndex += 1
                writeNext()
            }
        }

        stage = BleGattStage.CONNECT
        gatt = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, callback)
            }
        }.getOrNull() ?: return BleGattSendResult.Failed(
            stage = BleGattStage.CONNECT,
            detail = "connectGatt threw/returned null",
        )

        return try {
            withTimeoutOrNull(timeoutMs) { completion.await() }
                ?: BleGattSendResult.Failed(stage = stage, detail = "timeout ${timeoutMs}ms")
        } finally {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
    }

    companion object {
        private const val DEFAULT_MTU = 23
    }
}

enum class BleGattStage {
    CONNECT,
    DISCOVER_SERVICES,
    FIND_SERVICE,
    FIND_RX,
    PREPARE_FRAMES,
    WRITE_FRAME,
}

sealed interface BleGattSendResult {
    data class Success(val frameCount: Int) : BleGattSendResult

    data class Failed(
        val stage: BleGattStage,
        val status: Int? = null,
        val frameIndex: Int? = null,
        val frameCount: Int? = null,
        val detail: String? = null,
    ) : BleGattSendResult
}
