package com.openmesh.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.openmesh.core.MeshCrypto
import com.openmesh.core.MeshNodeId
import com.openmesh.core.PeerIdentityProof
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolves the exact OpenMesh node ID after BLE discovery and, when the peer
 * exposes a public key, verifies private-key possession with a fresh signed
 * challenge before marking that key as trusted for secure transport.
 */
class BlePeerIdentityClient(
    context: Context,
) {
    private val appContext = context.applicationContext

    @SuppressLint("MissingPermission")
    suspend fun resolve(
        deviceAddress: String,
        timeoutMs: Long = 12_000,
    ): ResolvedPeerIdentity? {
        val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        val device = runCatching { adapter.getRemoteDevice(deviceAddress) }.getOrNull() ?: return null
        val completion = CompletableDeferred<ResolvedPeerIdentity?>()

        var gatt: BluetoothGatt? = null
        var nodeIdCharacteristic: BluetoothGattCharacteristic? = null
        var publicKeyCharacteristic: BluetoothGattCharacteristic? = null
        var challengeCharacteristic: BluetoothGattCharacteristic? = null
        var proofCharacteristic: BluetoothGattCharacteristic? = null
        var resolvedNodeId: String? = null
        var resolvedPublicKey: String? = null
        var pendingChallenge: ByteArray? = null
        var finished = false

        fun finish(result: ResolvedPeerIdentity?) {
            if (finished) return
            finished = true
            completion.complete(result)
        }

        fun finishUnverified() {
            val nodeId = resolvedNodeId
            if (nodeId == null) {
                finish(null)
            } else {
                finish(
                    ResolvedPeerIdentity(
                        nodeId = nodeId,
                        publicKeyBase64 = resolvedPublicKey,
                        possessionVerified = false,
                    )
                )
            }
        }

        @SuppressLint("MissingPermission")
        fun readNodeId(activeGatt: BluetoothGatt): Boolean {
            val characteristic = nodeIdCharacteristic ?: return false
            return activeGatt.readCharacteristic(characteristic).also { accepted ->
                if (!accepted) finish(null)
            }
        }

        @SuppressLint("MissingPermission")
        fun readPublicKeyOrFinish(activeGatt: BluetoothGatt) {
            val characteristic = publicKeyCharacteristic
            if (characteristic == null) {
                finishUnverified()
                return
            }
            if (!activeGatt.readCharacteristic(characteristic)) finishUnverified()
        }

        @SuppressLint("MissingPermission")
        fun writeChallenge(activeGatt: BluetoothGatt): Boolean {
            val characteristic = challengeCharacteristic ?: return false
            if (proofCharacteristic == null) return false

            val challenge = PeerIdentityProof.newChallenge()
            pendingChallenge = challenge
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activeGatt.writeCharacteristic(
                    characteristic,
                    challenge,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = challenge
                @Suppress("DEPRECATION")
                activeGatt.writeCharacteristic(characteristic)
            }

            if (!accepted) finishUnverified()
            return accepted
        }

        @SuppressLint("MissingPermission")
        fun readProof(activeGatt: BluetoothGatt) {
            val characteristic = proofCharacteristic
            if (characteristic == null || !activeGatt.readCharacteristic(characteristic)) {
                finishUnverified()
            }
        }

        fun handleRead(
            activeGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishUnverified()
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
                    readPublicKeyOrFinish(activeGatt)
                }

                BleMeshProtocol.PUBLIC_KEY_CHARACTERISTIC_UUID -> {
                    val nodeId = resolvedNodeId ?: run {
                        finish(null)
                        return
                    }
                    val publicKey = runCatching { value.decodeToString() }.getOrNull()
                    if (publicKey.isNullOrBlank()) {
                        finishUnverified()
                        return
                    }

                    val keyNodeId = runCatching { MeshCrypto.nodeId(publicKey) }.getOrNull()
                    if (keyNodeId != nodeId) {
                        // The advertised/resolved routing identity and key disagree.
                        // Keep routing possible, but never expose the mismatched key.
                        resolvedPublicKey = null
                        finishUnverified()
                        return
                    }

                    resolvedPublicKey = publicKey
                    if (!writeChallenge(activeGatt)) {
                        finishUnverified()
                    }
                }

                BleMeshProtocol.IDENTITY_PROOF_CHARACTERISTIC_UUID -> {
                    val nodeId = resolvedNodeId ?: run {
                        finish(null)
                        return
                    }
                    val publicKey = resolvedPublicKey ?: run {
                        finishUnverified()
                        return
                    }
                    val challenge = pendingChallenge ?: run {
                        finishUnverified()
                        return
                    }
                    val signature = runCatching { value.decodeToString() }.getOrNull()
                    val verified = !signature.isNullOrBlank() && PeerIdentityProof.verify(
                        challenge = challenge,
                        nodeId = nodeId,
                        publicKeyBase64 = publicKey,
                        signatureBase64 = signature,
                    )

                    finish(
                        ResolvedPeerIdentity(
                            nodeId = nodeId,
                            publicKeyBase64 = publicKey,
                            possessionVerified = verified,
                        )
                    )
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
                challengeCharacteristic = service.getCharacteristic(BleMeshProtocol.IDENTITY_CHALLENGE_CHARACTERISTIC_UUID)
                proofCharacteristic = service.getCharacteristic(BleMeshProtocol.IDENTITY_PROOF_CHARACTERISTIC_UUID)

                if (!activeGatt.requestMtu(PREFERRED_MTU)) {
                    readNodeId(activeGatt)
                }
            }

            override fun onMtuChanged(activeGatt: BluetoothGatt, mtu: Int, status: Int) {
                readNodeId(activeGatt)
            }

            override fun onCharacteristicWrite(
                activeGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (characteristic.uuid != BleMeshProtocol.IDENTITY_CHALLENGE_CHARACTERISTIC_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishUnverified()
                    return
                }
                readProof(activeGatt)
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
    val possessionVerified: Boolean,
)
