package com.openmesh.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build

/** Runtime readiness checks for the optional high-bandwidth Wi-Fi Direct transport. */
class WifiDirectRadioGuard(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun snapshot(): WifiDirectRadioSnapshot {
        val packageManager = appContext.packageManager
        val supported = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
        val locationManager = appContext.getSystemService(LocationManager::class.java)
        val locationRequired = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2

        return WifiDirectRadioSnapshot(
            supported = supported,
            wifiEnabled = wifiManager?.isWifiEnabled == true,
            locationModeEnabled = !locationRequired || locationManager?.isLocationEnabled == true,
            missingPermissions = missingPermissions(),
        )
    }

    fun missingPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                appContext.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else if (
            appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}

data class WifiDirectRadioSnapshot(
    val supported: Boolean,
    val wifiEnabled: Boolean,
    val locationModeEnabled: Boolean,
    val missingPermissions: List<String>,
) {
    val ready: Boolean
        get() = supported && wifiEnabled && locationModeEnabled && missingPermissions.isEmpty()
}
