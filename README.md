# OpenMesh Mobile

Offline-first, delay-tolerant mobile mesh networking for Android.

OpenMesh Mobile is an independent project designed to let nearby smartphones discover each other, exchange packets without Internet access, store messages while a route is unavailable, and forward them later when another node is encountered.

## Current architecture

- `mesh-core`: protocol envelope, canonical cryptographic node IDs, TTL/hop rules, deduplication, store-and-forward router and cryptographic primitives. The code is transport-neutral; it is currently packaged as an Android library so every module can use AGP 9.3 built-in Kotlin consistently.
- `mesh-android`: radio permission supervision, compact BLE advertising/scanning, GATT identity resolution and packet transport, persistent packet storage, protected device identity and foreground node service.
- `app`: dependency-light Android demo used to test two or more physical phones.

## Implemented

- BLE presence discovery and connectable advertising that stays within the legacy 31-byte advertising budget by publishing only the OpenMesh service UUID.
- Exact peer identity resolution after discovery through a compact 16-byte GATT node-ID characteristic; the receiver reconstructs the same `om1-...` ID used by routing.
- Optional GATT public-key discovery, accepted only when the public key hashes back to the resolved node ID.
- GATT packet transport with fragmentation/reassembly for MTU-sized frames.
- Store-and-forward routing with TTL, hop limit and priority.
- Durable Android packet queue that survives process/device restarts.
- Duplicate suppression and final-recipient anti-echo routing.
- Per-peer delivery memory to reduce repeated retransmissions.
- `MeshRadioGuard` for missing permissions, Bluetooth state and radio capability.
- Foreground `connectedDevice` service for active participation.
- Bluetooth state monitoring: the service pauses transport when Bluetooth is disabled, preserves queued packets, updates the foreground notification and attempts to rejoin automatically after Bluetooth is enabled again.
- EC device identity whose `nodeId` is derived from its public key.
- Android identity persistence with the exported EC private material encrypted by a non-exportable AES key in Android Keystore.
- ECDH shared-secret derivation, AES-GCM authenticated encryption and ECDSA signatures in `mesh-core`.
- High-level E2E envelope API that binds immutable routing metadata to encryption/signatures while allowing relay-only hop metadata to change safely.
- Android `sendSecure()` API for encrypted unicast when the caller already possesses an authenticated recipient public key.
- Tests covering alternate routing when an intermediate node is offline, duplicate suppression, anti-echo behavior, canonical node-ID round trips, cryptographic round trips, relay-safe encrypted delivery and routing-header tamper rejection.

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

- Challenge-response proof of private-key possession and persistent peer-key directory.
- Delivery acknowledgements and persistent peer-known packet summaries.
- Wi-Fi Direct / Wi-Fi Aware high-bandwidth transport.
- Capacitor bridge/SDK packaging for future integration into other apps.

## Security model

Relay nodes do not need plaintext access to E2E application payloads. Encryption and signatures are protocol-level concerns. A public key learned over GATT is cryptographically bound to its self-certifying `nodeId`, but private-key possession proof and human/contact-level trust are separate concerns. Automatic trust will not be granted merely because a nearby BLE device claims an identity.

## License

No license has been granted yet. All rights reserved until a license is explicitly added.
