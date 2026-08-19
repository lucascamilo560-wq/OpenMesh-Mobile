# OpenMesh Mobile — functional test 0.2.0

Use two Android phones with the same APK installed.

1. Open the app on both phones and tap **Ativar OpenMesh**.
2. Grant Bluetooth / Nearby devices / notification permissions when requested.
3. Keep both phones near each other until **Peer verificado** shows a node on both devices.
4. Send a secure message from phone A to phone B and confirm it appears on B.
5. Tap **Abrir canal rápido Wi‑Fi Direct** on A. Enable Wi‑Fi (and Location on Android 12 or lower) if Android requests it.
6. Wait for **Canal rápido autenticado** on both devices.
7. Tap **Testar canal rápido** and confirm the other phone reports a FAST_TEST frame.

Turning an intermediate peer off must not erase queued OpenMesh packets; BLE/store-and-forward remains the fallback when Wi‑Fi Direct is unavailable.
