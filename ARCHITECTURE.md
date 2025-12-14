# Reference Successor Protocol Architecture

## Overview
This document outlines the reference implementation for a successor to the BitTorrent protocol, focusing on:
1.  **Mutable Subscriptions ("Megatorrents")**: Authenticated, updateable lists of content.
2.  **Identity**: Strong cryptographic ownership (Ed25519) without central authority.
3.  **Decentralized Tracking**: A hybrid approach where trackers facilitate discovery and update propagation, but are not the sole source of truth.
4.  **Future: Distributed Encrypted Storage**: A vision for obfuscated, sharded, and encrypted data storage (see Future Work).

## 1. Identity
*   **Algorithm**: Ed25519 (Edwards-curve Digital Signature Algorithm).
*   **Keypair**:
    *   `PublicKey` (32 bytes): Acts as the immutable "Channel ID" or "Address".
    *   `SecretKey` (64 bytes): Held by the author to sign updates.
*   **Addressing**: A "subscription" is simply the Hex-encoded Public Key.

## 2. Megatorrent Manifest
A "Megatorrent" is a dynamic collection of standard torrents (or future encrypted chunks).

### Format (JSON)
```json
{
  "publicKey": "<Hex encoded Ed25519 Public Key>",
  "sequence": 1, // Monotonically increasing integer
  "timestamp": 1678886400000,
  "collections": [
    {
      "title": "Season 1",
      "items": [
        "magnet:?xt=urn:btih:hash1&dn=ep1...",
        "magnet:?xt=urn:btih:hash2&dn=ep2..."
      ]
    }
  ],
  "signature": "<Hex encoded Ed25519 Signature of the canonical JSON>"
}
```

### Validation Rules
1.  `signature` must be valid for `publicKey` over the content (excluding the signature field itself).
2.  `sequence` must be greater than the previously known sequence for this key (replay protection).

## 3. Tracker Protocol Extensions
The standard BitTorrent tracker protocol (HTTP/WS) is extended with two new actions.

### Action: `publish` (WebSocket)
*   **Direction**: Client -> Tracker
*   **Params**:
    *   `action`: "publish"
    *   `manifest`: (The JSON object above)
*   **Tracker Logic**:
    *   Validate signature.
    *   Check `sequence` > stored sequence.
    *   Store/Cache manifest.
    *   Broadcast to all active subscribers.

### Action: `subscribe` (WebSocket)
*   **Direction**: Client -> Tracker
*   **Params**:
    *   `action`: "subscribe"
    *   `key`: "<Hex Public Key>"
*   **Tracker Logic**:
    *   Add socket to subscription list for `key`.
    *   Immediately send the latest cached `publish` message (if any).

## 4. Client Implementation ("Reference Client")
The reference client demonstrates:
1.  **Key Generation**: Creating a permanent identity.
2.  **Publishing**: Reading a list of magnet links and pushing an update.
3.  **Subscribing**: Watching a key and receiving real-time updates.
4.  **Anonymity**: SOCKS5 support for running over Tor/I2P.

## 5. Future Work: Distributed Encrypted Storage
The ultimate goal includes a storage layer with:
*   **Muxed Encrypted Chunks**: Files split into chunks, encrypted, and mixed with others.
*   **Plausible Deniability**: Host nodes store opaque blobs without knowing content.
*   **Dynamic Reassembly**: Files reassembled via a manifest of chunk hashes, potentially with random offsets/padding.

*Note: This reference implementation focuses on the Control Plane (Subscriptions) first.*
