package com.openmesh.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import com.openmesh.core.MeshCrypto
import com.openmesh.core.MeshNodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Resolves the exact OpenMesh node ID, and public key when exposed, after BLE discovery. */
class BlePeerIdentityClient(
    context: Context,
) {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    suspend fun resolve(
        deviceAddress: String,
        timeoutMs: Long = 10_000,
    ): ResolvedPeerIdentity? {
        val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        val device = runCatching { adapter.getRemoteDevice(deviceAddress) }.getOrNull() ?: return null
        val completion = CompletableDeferred<ResolvedPeerIdentity?>()

        var gatt: BluetoothGatt? = null
        var nodeIdCharacteristic: BluetoothGattCharacteristic? = null
        var publicKeyCharacteristic: BluetoothGattCharacteristic? = null
        var resolvedNodeId: String? = null
        var finished = false

        fun finish(result: ResolvedPeerIdentity?) {
            if (finished) return
            finished = true
            completion.complete(result)
        }

        @SuppressLint("MissingPermission")
        fun readNodeId(activeGatt: BluetoothGatt): Boolean {
            val characteristic = nodeIdCharacteristic ?: return false
            return activeGatt.readCharacteristic(characteristic).also { accepted ->
                if (!accepted) finish(null)
            }
        }

        @SuppressLint("MissingPermission")
        fun readPublicKeyOrFinish(activeGatt: BluetoothGatt, nodeId: String) {
            val characteristic = publicKeyCharacteristic
            if (characteristic == null) {
                finish(ResolvedPeerIdentity(nodeId = nodeId, publicKeyBase64 = null))
                return
            }
            if (!activeGatt.readCharacteristic(characteristic)) finish(null)
        }

        fun handleRead(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(null)
                return
            }

            when (characteristic.uuid) {
                BleMeshProtocol.NODE_ID_CHARACTERISTIC_UUID -> {
                    if (value.size != MeshNodeId.DIGEST_BYTES) {
                        finish(null)
                        return
                    }
                    val nodeId = runCatching { MeshNodeId.fromAdvertisementBytes(value) }
                        .getOrNull() ?: run {
                        finish(null)
                        return
                    }
                    resolvedNodeId = nodeId
                    readPublicKeyOrFinish(activeGatt, nodeId)
                }

                BleMeshProtocol.PUBLIC_KEY_CHARACTERISTIC_UUID -> {
                    val nodeId = resolvedNodeId ?: run {
                        finish(null)
                        return
                    }
                    val publicKey = runCatching { value.decodeToString() }.getOrNull()
                    if (publicKey.isNullOrBlank()) {
                        finish(null)
                        return
                    }
                    val keyNodeId = runCatching { MeshCrypto.nodeId(publicKey) }.getOrNull()
                    if (keyNodeId != nodeId) {
                        finish(null)
                        return
                    }
                    finish(ResolvedPeerIdentity(nodeId, publicKey))
                }
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(activeGatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(null)
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (!activeGatt.discoverServices()) finish(null)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> if (!finished) finish(null)
                }
            }

            override fun onServicesDiscovered(activeGatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(null)
                    return
                }
                val service = activeGatt.getService(BleMeshProtocol.SERVICE_UUID) ?: run {
                    finish(null)
                    return
                }
                nodeIdCharacteristic = service.getCharacteristic(BleMeshProtocol.NODE_ID_CHARACTERISTIC_UUID)
                    ?: run {
                        finish(null)
                        return
                    }
                publicKeyCharacteristic = service.getCharacteristic(BleMeshProtocol.PUBLIC_KEY_CHARACTERISTIC_UUID)

                if (!activeGatt.requestMtu(PREFERRED_MTU)) {
                    readNodeId(activeGatt)
                }
            }

            override fun onMtuChanged(activeGatt: BluetoothGatt, mtu: Int, status: Int) {
                readNodeId(activeGatt)
            }

            @Deprecated("Used for Android versions below API 33")
            override fun onCharacteristicRead(
                activeGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: byteArrayOf()
                handleRead(activeGatt, characteristic, value, status)
            }

            override fun onCharacteristicRead(
                activeGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                handleRead(activeGatt, characteristic, value, status)
            }
        }

        gatt = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(appContext, false, callback)
            }
        }.getOrNull() ?: return null

        return try {
            withTimeoutOrNull(timeoutMs) { completion.await() }
        } finally {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }
    }

    companion object {
        private const val PREFERRED_MTU = 247
    }
}

data class ResolvedPeerIdentity(
    val nodeId: String,
    val publicKeyBase64: String?,
)
