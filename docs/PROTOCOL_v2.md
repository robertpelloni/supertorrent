# Megatorrent Protocol v2

## 1. Decentralized Control Plane (Trackerless)

Instead of a central WebSocket tracker, Megatorrent v2 uses the BitTorrent DHT (BEP 44) for all control messages.

### 1.1. Identity & Manifests
*   **Identity:** Ed25519 Keypair.
*   **Manifest:** JSON document describing the collection (same as v1).
*   **Publishing:**
    *   The Author stores the Manifest as a **Mutable Item** in the DHT.
    *   `key`: Author's Public Key.
    *   `salt`: Optional (default empty).
    *   `seq`: Monotonically increasing sequence number (timestamp).
    *   `v`: Bencoded or raw JSON of the Manifest.
    *   `signature`: Ed25519 signature of the packet.
*   **Subscribing:**
    *   Subscribers periodically perform a `dht.get()` on the Author's Public Key.
    *   When a higher `seq` is found, the client downloads and verifies the new Manifest.

### 1.2. Blob Discovery
*   **Blobs:** Encrypted file chunks (same as v1).
*   **Announcement:**
    *   Nodes hosting a blob announce the **Blob Hash (SHA256)** to the DHT.
    *   This is a standard `announce_peer` operation where `info_hash = blob_id`.
*   **Lookup:**
    *   Clients perform `dht.lookup(blob_id)` to find IP:Port of peers.

## 2. Encrypted Data Plane (Anonymity)

To prevent traffic analysis and protect content privacy, the transport layer is fully encrypted.

### 2.1. Handshake (Opportunistic Encryption)
When Peer A connects to Peer B:
1.  **Peer A** generates ephemeral X25519 keypair (`A_pub`, `A_priv`). Sends `A_pub`.
2.  **Peer B** generates ephemeral X25519 keypair (`B_pub`, `B_priv`). Sends `B_pub`.
3.  Both compute shared secret `S = ECDH(Priv, RemotePub)`.
4.  Derive Session Keys:
    *   `Tx_Key_A = Rx_Key_B = BLAKE2b(S || 'C')`
    *   `Rx_Key_A = Tx_Key_B = BLAKE2b(S || 'S')`

### 2.2. Framing
All subsequent messages are framed:
*   `[Length (2 bytes)] [Encrypted Payload (N + 16 bytes)]`
*   Payload is encrypted with `ChaCha20-Poly1305`.
*   Nonce increments per packet.

### 2.3. Data Transfer
*   **Request:** `GET <BlobID>` (Encrypted)
*   **Response:** Raw Blob Data (Encrypted chunks)

## 3. Redundancy
*   Redundancy is achieved via the DHT's natural replication of announcements.
*   The more peers subscribe to a channel, the more replicas of the blobs exist in the swarm.

## 4. Onion Routing (Optional)
*   Peers may announce `.onion` addresses in the DHT (requires Tor support in DHT or specialized separate DHT).
*   The Reference Client supports SOCKS5, allowing it to run behind a Tor proxy.
