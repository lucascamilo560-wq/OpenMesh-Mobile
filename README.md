# OpenMesh Mobile

Offline-first, delay-tolerant mobile mesh networking for Android.

OpenMesh Mobile is an independent project designed to let nearby smartphones discover each other, exchange encrypted packets, store messages while a route is unavailable, and forward them later when another node is encountered.

## Goals

- No Internet required for local mesh operation.
- Android-first implementation using Bluetooth Low Energy (BLE).
- Store-and-forward / delay-tolerant routing.
- Packet TTL, hop limits and deduplication.
- Device identity and end-to-end encryption boundaries.
- Automatic radio-state supervision (`MeshRadioGuard`).
- Foreground service for active mesh sessions.
- Clean SDK boundary for future integration with Capacitor apps such as BordoAi.
- Transport abstraction prepared for Wi-Fi Direct / Wi-Fi Aware.

## Modules

- `mesh-core`: platform-independent packet, routing and queue primitives.
- `mesh-android`: Android radio permissions, BLE discovery/advertising and service lifecycle.
- `app`: minimal demo/test application.

## Status

Initial architecture and Android scaffold in progress.

## Security model

Relay nodes should not need plaintext access to application payloads. Cryptographic identity, key exchange and authenticated encryption are treated as protocol concerns rather than UI concerns.

## License

No license has been granted yet. All rights reserved until a license is explicitly added.
