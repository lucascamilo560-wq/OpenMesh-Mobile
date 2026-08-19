package com.openmesh.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.openmesh.core.MeshNodeId

class BleMeshScanner(
    private val context: Context,
) {
    private val adapter
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var activeCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(onPeer: (PeerAdvertisement) -> Unit): Boolean {
        if (activeCallback != null) return true

        val scanner = adapter?.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder()
            .setServiceUuid(BleMeshProtocol.SERVICE_PARCEL_UUID)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                publish(result, onPeer)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { publish(it, onPeer) }
            }
        }

        activeCallback = callback
        scanner.startScan(listOf(filter), settings, callback)
        return true
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val callback = activeCallback ?: return
        adapter?.bluetoothLeScanner?.stopScan(callback)
        activeCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun publish(result: ScanResult, onPeer: (PeerAdvertisement) -> Unit) {
        val compactNodeId = result.scanRecord
            ?.getServiceData(BleMeshProtocol.SERVICE_PARCEL_UUID)
            ?: return
        if (compactNodeId.size != MeshNodeId.DIGEST_BYTES) return

        val nodeId = runCatching { MeshNodeId.fromAdvertisementBytes(compactNodeId) }
            .getOrNull() ?: return

        onPeer(
            PeerAdvertisement(
                deviceAddress = result.device.address,
                nodeId = nodeId,
                rssi = result.rssi,
                seenAtMs = System.currentTimeMillis(),
            )
        )
    }
}

data class PeerAdvertisement(
    val deviceAddress: String,
    val nodeId: String,
    val rssi: Int,
    val seenAtMs: Long,
)
