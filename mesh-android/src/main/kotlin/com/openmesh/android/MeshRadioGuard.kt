package com.openmesh.android

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

class MeshRadioGuard(private val context: Context) {

    fun snapshot(): RadioSnapshot {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter

        val bluetoothEnabled = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }

        val advertisingSupported = try {
            adapter?.isMultipleAdvertisementSupported == true
        } catch (_: SecurityException) {
            false
        }

        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

        return RadioSnapshot(
            bluetoothAvailable = adapter != null,
            bluetoothEnabled = bluetoothEnabled,
            bleAdvertisingSupported = advertisingSupported,
            wifiEnabled = wifiManager?.isWifiEnabled == true,
            missingBlePermissions = missingBlePermissions(),
            missingWifiPermissions = missingWifiPermissions(),
        )
    }

    fun missingBlePermissions(): List<String> = requiredBleRuntimePermissions().filterNot(::granted)

    fun missingWifiPermissions(): List<String> = requiredWifiRuntimePermissions().filterNot(::granted)

    fun bluetoothEnableRequestIntent(): Intent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)

    fun wifiPanelIntent(): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(Settings.Panel.ACTION_WIFI)
    } else {
        Intent(Settings.ACTION_WIFI_SETTINGS)
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        fun requiredBleRuntimePermissions(): List<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
            else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun requiredWifiRuntimePermissions(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                emptyList()
            }
    }
}

data class RadioSnapshot(
    val bluetoothAvailable: Boolean,
    val bluetoothEnabled: Boolean,
    val bleAdvertisingSupported: Boolean,
    val wifiEnabled: Boolean,
    val missingBlePermissions: List<String>,
    val missingWifiPermissions: List<String>,
) {
    val bleReady: Boolean
        get() = bluetoothAvailable &&
            bluetoothEnabled &&
            bleAdvertisingSupported &&
            missingBlePermissions.isEmpty()
}
