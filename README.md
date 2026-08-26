# OpenMesh Mobile

Offline-first, delay-tolerant mobile mesh networking for Android.

OpenMesh Mobile is an independent project designed to let nearby smartphones discover each other, exchange packets without Internet access, store messages while a route is unavailable, and forward them later when another node is encountered.

## Current architecture

- `mesh-core`: protocol envelope, canonical cryptographic node IDs, TTL/hop rules, deduplication, store-and-forward router, E2E encryption and peer identity proof primitives.
- `mesh-android`: radio permission supervision, compact BLE advertising/scanning, GATT identity resolution/challenge-response and packet transport, persistent packet/key storage, protected device identity and foreground node service.
- `app`: dependency-light Android demo used to test two or more physical phones.

## Implemented

- BLE presence discovery and connectable advertising that stays within the legacy 31-byte advertising budget by publishing only the OpenMesh service UUID.
- Exact peer identity resolution after discovery through a compact 16-byte GATT node-ID characteristic; the receiver reconstructs the same `om1-...` ID used by routing.
- GATT public-key discovery bound to the self-certifying node ID.
- Fresh 32-byte challenge-response proof of private-key possession before a discovered public key is admitted to the verified peer-key cache.
- Verified peer keys are persisted locally with first/last verification metadata and reused after app/device restarts.
- A conflicting public key for the same self-certifying `nodeId` is rejected instead of silently replacing the stored key.
- Unverified peers may still relay packets, but their public keys are not exposed as verified keys for E2E messaging.
- GATT packet transport with fragmentation/reassembly for MTU-sized frames.
- Store-and-forward routing with TTL, hop limit and priority.
- Durable Android packet queue that survives process/device restarts.
- Duplicate suppression and final-recipient anti-echo routing.
- Per-peer link-write memory to reduce repeated retransmissions during the current process lifetime.
- `MeshRadioGuard` for missing permissions, Bluetooth state and radio capability.
- Foreground `connectedDevice` service for active participation.
- Bluetooth state monitoring: the service pauses transport when Bluetooth is disabled, preserves queued packets, updates the foreground notification and attempts to rejoin automatically after Bluetooth is enabled again.
- EC device identity whose `nodeId` is derived from its public key.
- Android identity persistence with the exported EC private material encrypted by a non-exportable AES key in Android Keystore.
- ECDH shared-secret derivation, AES-GCM authenticated encryption and ECDSA signatures in `mesh-core`.
- High-level E2E envelope API that binds immutable routing metadata to encryption/signatures while allowing relay-only hop metadata to change safely.
- Android `sendSecure()` API for encrypted unicast when the caller possesses an authenticated recipient public key.
- Tests covering alternate routing when an intermediate node is offline, duplicate suppression, anti-echo behavior, canonical node-ID round trips, cryptographic round trips, relay-safe encrypted delivery, routing-header tamper rejection and private-key possession proof.

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

Use the committed wrapper so local and CI builds run the same Gradle distribution:

```bash
./gradlew :mesh-core:testDebugUnitTest :mesh-android:testDebugUnitTest :mesh-android:assembleDebug :app:assembleDebug
```

## Next protocol work

- Persistent delivery acknowledgements and peer-known packet summaries to reduce redundant flooding across restarts.
- User/contact trust layer above the cryptographic device verification layer.
- Wi-Fi Direct / Wi-Fi Aware high-bandwidth transport.
- Capacitor bridge/SDK packaging for future integration into other apps.

## Security model

Relay nodes do not need plaintext access to E2E application payloads. Encryption and signatures are protocol-level concerns. A discovered public key must hash to the resolved self-certifying `nodeId` and successfully sign a fresh challenge before the Android SDK persists it as a verified peer key. Human/contact-level trust remains a separate application concern; proximity alone does not establish identity ownership.

## License

No license has been granted yet. All rights reserved until a license is explicitly added.
