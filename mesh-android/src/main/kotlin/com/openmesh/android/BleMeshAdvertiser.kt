package com.openmesh.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import java.security.MessageDigest

class BleMeshAdvertiser(
    private val context: Context,
) {
    private val adapter
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var activeCallback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun start(nodeId: String, onResult: (AdvertiseState) -> Unit = {}): Boolean {
        if (activeCallback != null) return true

        val advertiser = adapter?.bluetoothLeAdvertiser ?: return false

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(BleMeshProtocol.SERVICE_PARCEL_UUID)
            .addServiceData(
                BleMeshProtocol.SERVICE_PARCEL_UUID,
                nodeFingerprint(nodeId),
            )
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                onResult(AdvertiseState.ACTIVE)
            }

            override fun onStartFailure(errorCode: Int) {
                activeCallback = null
                onResult(AdvertiseState.FAILED(errorCode))
            }
        }

        activeCallback = callback
        advertiser.startAdvertising(settings, data, callback)
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val callback = activeCallback ?: return
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
        activeCallback = null
    }

    private fun nodeFingerprint(nodeId: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(nodeId.encodeToByteArray())
            .copyOfRange(0, 8)
}

sealed interface AdvertiseState {
    data object ACTIVE : AdvertiseState
    data class FAILED(val errorCode: Int) : AdvertiseState
}
