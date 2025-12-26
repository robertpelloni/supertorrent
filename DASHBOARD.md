# Project Dashboard

## 🏗 Directory Structure

*   **`/` (Root):** Node.js Reference Client (The "Mesh Node").
    *   `lib/`: Core Node.js implementation (`secure-transport`, `dht-real`, `storage`).
    *   `index.js`: CLI entry point.
*   **`qbittorrent/` (Submodule):** Fork of qBittorrent.
    *   *Status:* Points to `release-5.1.0beta1` (approx).
    *   *Modifications:* Contains untracked files in `src/base/` implementing Megatorrent.
*   **`cpp-reference/`:** The Canonical Source of Truth for the C++ integration.
    *   `megatorrent/`: Core C++ classes (`DHTClient`, `SecureSocket`, `Manifest`, `BlobDownloader`).
    *   `qbittorrent-patches/`: Modified qBittorrent files (`sessionimpl`, `CMakeLists.txt`).
*   **`webui-reference/`:** JavaScript/HTML assets for the qBittorrent WebUI.
*   **`docs/`:** Documentation (`PROTOCOL.md`, `ROADMAP.md`).

## 📦 Submodules

| Submodule | Path | Branch/Commit | Status |
| :--- | :--- | :--- | :--- |
| **qBittorrent** | `qbittorrent/` | `5abf458e6` | **Dirty** (Contains integration stubs) |

## 🛠 Feature Matrix

| Feature | Node.js Client | C++ Reference (qBt) |
| :--- | :---: | :---: |
| **DHT Control Plane** | ✅ | ✅ (Wraps libtorrent) |
| **Manifest Parsing** | ✅ | ✅ (JSON + Ed25519) |
| **Encryption** | ✅ (ChaCha20-Poly1305) | ✅ (OpenSSL EVP) |
| **Transport Handshake**| ✅ (Noise-IK) | ✅ (OpenSSL X25519) |
| **Blob Storage** | ✅ (Encrypted+Padded) | ✅ (Direct Write) |
| **Subscription Mgr** | ✅ | ✅ (Persisted JSON) |
| **GUI/WebUI** | N/A (CLI) | ✅ (API Exposed) |

## 🚀 Version Information

**Current Version:** `1.3.0`
**Build Date:** 2024-05-22 (Simulated)
