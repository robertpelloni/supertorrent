# Changelog

All notable changes to this project will be documented in this file.

## [1.5.0] - 2024-05-23

### Added
- **C++ I2P Support:** Implemented `Megatorrent::SamSession` (SAM v3.1 Client) in C++.
- **qBittorrent Integration:** Integrated I2P startup and shutdown logic into `SessionImpl` with safe resource management.

## [1.4.1] - 2024-05-22

### Fixed
- **Critical Protocol Fix:** Updated `lib/secure-transport.js` to correctly use `sodium.crypto_aead_chacha20poly1305_ietf` instead of `crypto_secretbox`. This restores compatibility with standard OpenSSL C++ clients (IETF ChaCha20, 12-byte nonce).

## [1.4.0] - 2024-05-22

### Added
- **WebAPI Integration:** Added `MegatorrentController` to the C++ reference to expose subscription management to the WebUI.
- **Frontend Upgrade:** Updated `megatorrent.js` to use the native qBittorrent WebAPI endpoints (`/api/v2/megatorrent/`) instead of external RPC.

## [1.3.0] - 2024-05-22

### Added
- **I2P Support:** Native I2P integration via SAM v3.1 (`lib/i2p-sam.js`).
- **Safe Mode:** Automatic DHT disabling when I2P is active.

### Fixed
- **I2P Accept Loop:** Fixed a critical bug where the initial handshake packet was discarded by the accept loop logic.

## [1.2.0] - 2024-05-22

### Added
- **C++ Reference Implementation:** Complete set of C++ classes in `cpp-reference/` for integrating Megatorrent into qBittorrent.
    - `SecureSocket`: Implements Noise-IK handshake and ChaCha20-Poly1305 encryption using OpenSSL.
    - `Manifest`: Implements Ed25519 signature verification using OpenSSL.
    - `BlobDownloader`: Manages peer connections and file integrity.
    - `SessionImpl`: Patches for `qbittorrent` to expose `addMegatorrentSubscription`.
- **Dashboard:** New `DASHBOARD.md` to track submodule and project status.
- **Universal Instructions:** `LLM_INSTRUCTIONS.md` for standardized agent behavior.

### Changed
- **Protocol Update:** Switched Node.js client (`lib/secure-transport.js`) to use **IETF ChaCha20-Poly1305** (`sodium.crypto_aead_chacha20poly1305_ietf`) instead of XSalsa20 (`crypto_secretbox`) to ensure compatibility with standard OpenSSL C++ clients.
- **Documentation:** Updated `ROADMAP.md` and `HANDOFF.md` to reflect the completed state of the C++ integration.

## [1.1.0] - 2024-05-21
### Added
- **Subscription Manager (C++):** `Megatorrent::SubscriptionManager` stub for managing feed persistence.
- **API Exposure:** `Session` interface now includes `addMegatorrentSubscription`.

## [1.0.0] - 2024-05-20
### Initial Release
- **Node.js Client:** Full CLI implementation (Ingest, Publish, Subscribe).
- **Protocol:** Defined Version 5 (DHT + Encrypted TCP).
- **Docker:** Swarm simulation setup.
