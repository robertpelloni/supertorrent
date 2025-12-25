# Megatorrent Project Roadmap & Status

**Current Version:** v1.0.0
**Protocol Version:** v5

## ✅ Accomplished Features

### 1. Core Architecture
*   **Monorepo Structure:** Root Node.js project, `qbittorrent` submodule.
*   **Documentation:** `PROTOCOL_FINAL.md`, `HANDOFF.md`, `README.md`.
*   **Docker:** `Dockerfile`, `docker-compose.yml` for swarm simulation.

### 2. Decentralized Control Plane (DHT)
*   **Library:** `bittorrent-dht` (BEP 44).
*   **Manifests:** Mutable Items signed by Ed25519 keys.
*   **Blobs:** SHA256 InfoHash announcements.
*   **Persistence:** `dht_state.json`.

### 3. Data Plane & Anonymity
*   **Encryption:** ChaCha20-Poly1305 + Ephemeral ECDH (X25519) Handshake.
*   **Transport:** Custom Binary Protocol (`secure-transport.js`).
    *   `MSG_HELLO (0x01)`: Handshake + Gossip.
    *   `MSG_REQUEST (0x02)`: Blob Request.
    *   `MSG_DATA (0x03)`: Encrypted Data.
    *   `MSG_FIND_PEERS (0x04)`: PEX Request (TCP).
    *   `MSG_PEERS (0x05)`: PEX Response.
    *   `MSG_PUBLISH (0x06)`: Gateway Publishing.
    *   `MSG_ANNOUNCE (0x07)`: Hidden Service Announcing.
*   **Tor Support:** SOCKS5 client integration.
*   **Traffic Analysis Resistance:** Fixed-size 1MB padding.

### 4. Resilience
*   **Active Seeding:** Periodic re-announcement of held content.
*   **Gossip:** Push updates for subscription sequence numbers.
*   **Integrity:** SHA256 hash verification of downloaded blobs.
*   **Blacklisting:** Automatic ban of peers sending corrupt data.

### 5. Usability
*   **CLI:** `megatorrent` command (`ingest`, `publish`, `subscribe`, `serve`).
*   **Daemon:** JSON-RPC Server (`/api/rpc`).
*   **WebUI:** qBittorrent WebUI integration (`megatorrent.js`).
*   **Streaming:** Memory-efficient ingestion and reassembly.

---

## 🚧 Pending / In-Progress

### 1. Anonymity Safety (Critical)
*   **Issue:** `bittorrent-dht` uses UDP. If a SOCKS5 proxy is configured, UDP traffic may either fail or leak the real IP.
*   **Fix:** "Safe Mode". If proxy is enabled, disable local DHT instance. Rely entirely on TCP PEX (`MSG_FIND_PEERS`) and Gateway features via the Bootstrap node.

### 2. PEX Propagation
*   **Issue:** Currently, PEX is a simple query-response.
*   **Enhancement:** Active propagation of `MSG_ANNOUNCE` to neighbors (Gossip) to ensure Hidden Services are discoverable across the swarm without DHT.

### 3. C++ Reference
*   **Status:** Stubs created (`dht_client`, `secure_socket`).
*   **Next:** Implement actual OpenSSL/Libtorrent logic (requires C++ dev environment).

---

## 📅 Roadmap

1.  **v1.1 (Immediate):** Fix UDP Leakage in Proxy Mode (Disable DHT, Force Gateway Mode).
2.  **v1.2:** Enhanced PEX Gossip (Flood-fill for Hidden Services).
3.  **v2.0:** Full C++ Integration in qBittorrent Core.
