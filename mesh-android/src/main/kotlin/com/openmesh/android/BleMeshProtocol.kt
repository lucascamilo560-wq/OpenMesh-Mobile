package com.openmesh.android

import android.os.ParcelUuid
import java.util.UUID

object BleMeshProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("8d53d7a0-6f4b-4f78-a6b6-2ecb49ce6b01")
    val RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("8d53d7a1-6f4b-4f78-a6b6-2ecb49ce6b01")
    val TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("8d53d7a2-6f4b-4f78-a6b6-2ecb49ce6b01")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val SERVICE_PARCEL_UUID = ParcelUuid(SERVICE_UUID)
}
