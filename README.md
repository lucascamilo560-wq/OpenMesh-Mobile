# OpenMesh Mobile

Offline-first, delay-tolerant mobile mesh networking for Android.

OpenMesh Mobile is an independent project designed to let nearby smartphones discover each other, exchange packets without Internet access, store messages while a route is unavailable, and forward them later when another node is encountered.

## Current architecture

- `mesh-core`: protocol envelope, TTL/hop rules, deduplication, store-and-forward router and cryptographic primitives. The code is transport-neutral; it is currently packaged as an Android library so every module can use AGP 9.3 built-in Kotlin consistently.
- `mesh-android`: radio permission supervision, BLE advertising/scanning, GATT sender/receiver, persistent packet storage and foreground node service.
- `app`: dependency-light Android demo used to test two or more physical phones.

## Implemented

- BLE peer discovery and connectable advertising.
- GATT packet transport with fragmentation/reassembly for MTU-sized frames.
- Store-and-forward routing with TTL, hop limit and priority.
- Durable Android packet queue that survives process/device restarts.
- Duplicate suppression and final-recipient anti-echo routing.
- Per-peer delivery memory to reduce repeated retransmissions.
- `MeshRadioGuard` for missing permissions, Bluetooth state and radio capability.
- Foreground `connectedDevice` service for active participation.
- Bluetooth state monitoring: the service pauses transport when Bluetooth is disabled, preserves queued packets, updates the foreground notification and attempts to rejoin automatically after Bluetooth is enabled again.
- ECDH shared-secret derivation, AES-GCM authenticated encryption and ECDSA signatures in `mesh-core`.
- Tests covering alternate routing when an intermediate node is offline, duplicate suppression and cryptographic round trips.

## Example resilience scenario

A message addressed from A to C does not depend on B being active:

```text
B offline:
A   X   B       C

Alternative contact:
A -> D -> C
```

If no relay is available, A retains the envelope until another eligible peer appears or the packet TTL expires.

## Build

The project targets Android API 36 with AGP 9.3, Gradle 9.5 and JDK 17. CI builds the demo and Android library and executes the core unit tests on pushes and pull requests.

## Next protocol work

- Authenticated peer/key handshake and trusted public-key directory.
- Delivery acknowledgements and persistent peer-known packet summaries.
- Wi-Fi Direct / Wi-Fi Aware high-bandwidth transport.
- Capacitor bridge/SDK packaging for future integration into other apps.

## Security model

Relay nodes should not require plaintext access to application payloads. Encryption and signatures are protocol-level concerns. Key discovery/authentication is intentionally kept separate from transport and is the next security milestone before encrypted direct messaging is exposed as a stable SDK API.

## License

No license has been granted yet. All rights reserved until a license is explicitly added.
