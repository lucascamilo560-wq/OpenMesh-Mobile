# OpenMesh Mobile

Offline-first, delay-tolerant mobile mesh networking for Android.

OpenMesh Mobile is an independent project designed to let nearby smartphones discover each other, exchange packets without Internet access, store messages while a route is unavailable, and forward them later when another node is encountered.

## Current architecture

- `mesh-core`: protocol envelope, canonical cryptographic node IDs, TTL/hop rules, deduplication, store-and-forward router, E2E encryption and peer identity proof primitives.
- `mesh-android`: BLE control/mesh transport plus optional Wi-Fi Direct high-bandwidth upgrade, radio supervision, persistent packet/key storage, protected identity and foreground service.
- `app`: dependency-light Android demo used to test two or more physical phones.

## Implemented

- BLE presence discovery with compact advertising and GATT identity resolution.
- Self-certifying `om1-...` node IDs derived from public keys.
- Fresh challenge-response proof of private-key possession before peer keys become trusted for E2E messaging.
- Persistent verified peer-key directory and persistent store-and-forward packet queue.
- GATT packet fragmentation/reassembly, TTL, hop limits, priority, deduplication and anti-echo routing.
- `MeshRadioGuard` with Bluetooth state monitoring and foreground `connectedDevice` service.
- ECDH, AES-GCM and ECDSA protocol primitives plus high-level E2E secure envelopes.
- Android `sendSecure()` API for encrypted unicast through arbitrary relays.
- Optional Android 10+ Wi-Fi Direct high-bandwidth upgrade negotiated through the existing encrypted BLE mesh.
- Temporary Wi-Fi Direct group credentials generated per upgrade and delivered only inside the E2E control envelope.
- Credential-based P2P group formation avoids depending on a visible peer MAC address.
- Persistent full-duplex TCP data session over the formed Wi-Fi Direct group, with generic binary framing and direct MeshEnvelope support.
- Wi-Fi Direct readiness guard for capability, Wi-Fi state, location mode and nearby-device permission.
- CI executes both `mesh-core` and `mesh-android` unit tests and builds the Android library plus demo APK.

## Transport strategy

```text
BLE mesh/control
  discovery + identity + routing + encrypted control
                 |
                 | secure upgrade offer
                 v
Wi-Fi Direct session (when supported/needed)
  large messages + files + future audio/video streams
```

BLE remains the baseline transport. Wi-Fi Direct is an opportunistic bandwidth upgrade; failure to establish it does not remove the device from the mesh.

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

The project targets Android API 36 with AGP 9.3, Gradle 9.5 and JDK 17. Wi-Fi Direct uses the Android 10+ credential-based group APIs and falls back to BLE when unavailable.

## Next protocol work

- Authenticate the first frame of each high-bandwidth socket session to the already-verified OpenMesh device identity.
- Persistent delivery acknowledgements and peer-known packet summaries.
- Wi-Fi Aware transport where hardware support is available.
- Media framing for offline voice/video calling.
- Capacitor bridge/SDK packaging for future integration into other apps.

## Security model

Relay nodes do not need plaintext access to E2E application payloads. A discovered public key must hash to the resolved self-certifying node ID and prove private-key possession before it is trusted. Wi-Fi Direct group credentials are ephemeral and are sent inside an E2E envelope; application payload encryption remains independent of the radio link.

## License

No license has been granted yet. All rights reserved until a license is explicitly added.
