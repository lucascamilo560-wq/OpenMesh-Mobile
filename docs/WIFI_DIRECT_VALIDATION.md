# Wi-Fi Direct validation milestone

This branch validates the current high-bandwidth transport milestone against `main`.

The baseline BLE mesh remains responsible for discovery, store-and-forward routing, verified device identity and encrypted control messages. For Android 10+ devices that support Wi-Fi Direct, a verified peer can receive temporary group credentials inside an E2E OpenMesh envelope and upgrade to a persistent full-duplex TCP session.

The high-bandwidth socket is not trusted merely because the Wi-Fi passphrase was accepted. Both endpoints exchange a signed OpenMesh session hello bound to the session ID, node ID and public key. The SDK exposes the socket session only after the remote hello matches the already-verified peer identity and its ECDSA signature validates.

If the Wi-Fi Direct preparation, group negotiation, TCP connection or session authentication fails, the BLE/store-and-forward mesh remains available as the fallback transport.
