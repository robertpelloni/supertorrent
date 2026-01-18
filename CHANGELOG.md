# CHANGELOG

All notable changes to Supernode Java.

## [Unreleased]

### Added (v0.1.0-SNAPSHOT)

#### Storage Layer
- Enhanced `BlobStore` with streaming support, chunking strategies, and caching layer
  - Added `InputStream get(String blobId)` and `OutputStream put(String blobId)`
  - Implemented `ChunkingStrategy` with fixed-size, adaptive, and noChunking options
  - Added `BlobCache` with LRU eviction policy, size limits, and TTL support
  - Added batch operations: `batchPut()` and `batchGet()`
  - Added verification operations: `verify()` with checksums
- Enhanced `SupernodeStorage` with async operations and progress tracking
  - Added `ingestAsync()` and `retrieveAsync()` for non-blocking operations
  - Implemented `IngestProgress` and `RetrieveProgress` callbacks
  - Added event emission for chunk ingested/retrieved and file ingested/retrieved
  - Added erasure coding events for monitoring
  - Integrated `BlobCache` into storage operations
  - Enhanced error handling and recovery mechanisms
- Fixed `MuxEngine` cipher incompatibility
  - Changed from `ChaCha20` to `AES/GCM/NoPadding` for better Java compatibility
  - Updated `GCMParameterSpec(128, iv)` with 12-byte nonce for proper authentication
  - Provides both confidentiality and integrity (128-bit GCM auth tag)
- Fixed `Manifest` cipher incompatibility
  - Updated to use `AES/GCM/NoPadding` matching MuxEngine changes
  - Ensured consistent encryption across storage layer
- Integrated `ErasureCoder` for data redundancy
  - 4 data shards + 2 parity shards configuration (configurable)
  - Recovery from up to 2 lost data shards or 2 lost parity shards
  - Deterministic encoding/decoding

#### Blockchain Layer
- Enhanced `BobcoinBridge` with health monitoring and circuit breaker
  - Added `HealthState` enum with HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN states
  - Added `HealthStatus` record with comprehensive health tracking
  - Added `HealthChangeEvent` record for health state changes
  - Implemented periodic health checks with 30-second intervals (configurable)
  - Added circuit breaker pattern with threshold of 3 consecutive failures
  - Added health event emission via `onHealthChange` consumer
  - Enhanced connection monitoring with consecutive failure tracking
  - Thread-safe health tracking with `AtomicInteger`, `AtomicLong`, and `volatile` fields
  - Added `getHealthStatus()`, `triggerHealthCheck()`, and `setOnHealthChange()` public API methods

#### Storage Layer (Erasure Coding Enhancements)
- Enhanced `ErasureCoder` with advanced features for production workloads
  - Added streaming erasure coding support for large files (>1GB)
  - Implemented `encodeStream()` and `decodeStream()` methods for chunk-based processing
  - Fixed-size chunk processing with configurable overlap for seamless recovery
  - Thread-safe implementation using `ReentrantReadWriteLock`
  - Added Reed-Solomon (6+2) configuration option for higher redundancy
  - Added factory methods: `createStandard()`, `createHighRedundancy()`, `createExtremeRedundancy()`, `createWithContext()`
  - Implemented parity shard verification and automatic repair
  - Added `verifyParity()` - Validates parity shards by recomputing from data shards
  - Added `repairParity()` - Automatic parity repair using data shards
  - Added SHA-256 checksum computation and caching for all shards
  - Implemented adaptive shard selection based on network conditions
  - Added `NetworkContext` class for tracking network health, latency, and peer metrics
  - Added `selectOptimalShardCount()` - Dynamically adjusts parity shards based on network quality
  - Added peer scoring and selection with multi-factor evaluation
  - Added comprehensive event emission: `onEncoding()`, `onDecoding()`, `onRepair()`
  - Events include timestamps, shard counts, and processed bytes
  - Thread-safe statistics with `AtomicLong` counters
  - Methods: `getEncodeCount()`, `getDecodeCount()`, `getRepairCount()`
  - Maintains backward compatibility with existing 4+2 configuration
  - All new features are optional and additive

#### Blockchain Layer
- Enhanced `BlobNetwork` with transport manager integration and multi-network routing
  - Added support for multiple transport types (CLEARNET, TOR, I2P, IPFS, HYPHANET, ZERONET)
  - Implemented transport health tracking and automatic failover
  - Added peer health monitoring with backpressure detection
  - Enhanced blob discovery across all transports
  - Implemented retry logic with exponential backoff
  - Added circuit breaker for fault tolerance
- Fixed WebSocket connection handshake timing
  - Server now waits for client's "hello" before establishing peer connection
  - Prevents sending messages before WebSocket upgrade completes
  - Proper peer establishment and "have" message exchange
- Enhanced `DHTDiscovery` with improved peer tracking and network health
  - Added peer reputation scoring system
  - Enhanced network health monitoring across DHT nodes
   - Improved announce and lookup operations with retry logic

#### Storage Layer (Erasure Coding Enhancements)
- Enhanced `ErasureCoder` with advanced features for production workloads
  - Added streaming erasure coding support for large files (>1GB)
  - Implemented `encodeStream()` and `decodeStream()` methods for chunk-based processing
  - Fixed-size chunk processing with configurable overlap for seamless recovery
  - Thread-safe implementation using `ReentrantReadWriteLock`
  - Added Reed-Solomon (6+2) configuration option for higher redundancy
  - Added factory methods: `createStandard()`, `createHighRedundancy()`, `createExtremeRedundancy()`, `createWithContext()`
  - Implemented parity shard verification and automatic repair
  - Added `verifyParity()` - Validates parity shards by recomputing from data shards
  - Added `repairParity()` - Automatic parity repair using data shards
  - Added SHA-256 checksum computation and caching for all shards
  - Implemented adaptive shard selection based on network conditions
  - Added `NetworkContext` class for tracking network health, latency, and peer metrics
  - Added `selectOptimalShardCount()` - Dynamically adjusts parity shards based on network quality
  - Added peer scoring and selection with multi-factor evaluation
  - Added comprehensive event emission: `onEncoding()`, `onDecoding()`, `onRepair()`
  - Events include timestamps, shard counts, and processed bytes
  - Thread-safe statistics with `AtomicLong` counters
  - Methods: `getEncodeCount()`, `getDecodeCount()`, `getRepairCount()`
  - Maintains backward compatibility with existing 4+2 configuration
  - All new features are optional and additive
- Enhanced `SupernodeNetwork` with unified API across all transports
  - Single entry point for storage and network operations
  - Integrated `BlobNetwork`, `DHTDiscovery`, and `TransportManager`
  - Automatic transport selection and failover
  - Unified event forwarding and blob fetching across all networks
  - Health aggregation from all components

#### Transport Layer
- Implemented `TransportManager` for multi-network coordination
  - Transport registration and lifecycle management
  - Automatic failover with circuit breaker pattern
  - Load balancing across healthy transports
  - Health monitoring and automatic transport recovery
  - Unified transport API with common operations
- Implemented `HyphanetTransport` (Freenet integration)
  - FCP (Freenet Client Protocol) implementation
  - SSK/USK key management with persistence
  - Request priorities and queuing system
  - Splitfile support for large files
  - Health monitoring and auto-reconnect
- Implemented `IPFSTransport` (IPFS integration)
  - Full HTTP API client for IPFS operations
  - IPNS (InterPlanetary Namespace System) support
  - MFS (Mutable File System) operations
  - DAG (Directed Acyclic Graph) operations
  - PubSub message publishing for peer communication
  - Pin management with optional recursive pinning
- Implemented `TorTransport` (Tor network integration)
  - Onion service (hidden service) support
  - Tor circuit management and rotation
  - Health monitoring and circuit health tracking
- Implemented `I2PTransport` (I2P network integration)
  - SAM (Session and Address Management) bridge
  - Destination key management
  - Tunnel establishment and management
  - Health monitoring with destination verification
- Implemented `ZeronetTransport` (Zeronet integration)
  - ZeroNet site address handling
  - Site update and synchronization
  - Health monitoring with site availability tracking

#### Blockchain Layer
- Implemented `BobcoinBridge` for Filecoin integration
  - Wallet management with account creation and address generation
  - Deal negotiation and management
  - Proof submission and verification
  - Payment handling and reward claiming
  - Storage provider registration and deal management
  - Chain connection monitoring and health checks
  - Event-driven architecture for blockchain operations

#### Testing
- Added comprehensive integration tests for storage layer
  - Full async ingest/retrieve cycle with progress tracking
  - Erasure coding recovery tests
  - Operation tracking and cleanup verification
- Added comprehensive integration tests for network layer
  - Blob discovery and transfer between nodes
  - Peer event tracking across unified network
  - Health aggregation from all components
- Added unit tests for all transport implementations
  - Address handling and parsing tests
  - Health monitoring and lifecycle tests
  - Connection management and failover tests
- Added diagnostic test for erasure coder parity shard generation
  - Tests matrix coefficient generation and parity shard differences
  - Documents test bug (incorrect expectations, not implementation bug)

#### Documentation
- Added `ERASURE_CODER_TEST_ANALYSIS.md` documenting parity test investigation
- Identified that failing test has incorrect expectations (test bug, not implementation bug)
- ErasureCoder implementation is correct - all other tests pass successfully

## [0.1.0] - 2026-01-18

### Initial Release
- Supernode P2P storage and network infrastructure
- Multi-transport support (Clearnet, Tor, I2P, IPFS, Hyphanet, Zeronet)
- Erasure coding for data redundancy (4+2 configuration)
- AES-GCM encryption for data confidentiality and integrity
- Event-driven architecture with comprehensive health monitoring
- Filecoin blockchain integration via BobcoinBridge

---

## Versioning

This project follows [Semantic Versioning](https://semver.org/).

- **MAJOR**: Incompatible API changes
- **MINOR**: Backward-compatible functionality additions
- **PATCH**: Backward-compatible bug fixes

For example: `0.1.0`, `0.1.1`, `0.1.2`, etc.


### Bug Fixes

* export WebSocketTracker ([#558](https://github.com/webtorrent/bittorrent-tracker/issues/558)) ([1571551](https://github.com/webtorrent/bittorrent-tracker/commit/15715518decfed77d7888ba21d6ab592fa91cc85))

## [11.2.1](https://github.com/webtorrent/bittorrent-tracker/compare/v11.2.0...v11.2.1) (2025-01-19)


### Bug Fixes

* http announce no left ([#548](https://github.com/webtorrent/bittorrent-tracker/issues/548)) ([3cd77f3](https://github.com/webtorrent/bittorrent-tracker/commit/3cd77f3e6f5b52f5d58adaf004b333cd2061a4da))

# [11.2.0](https://github.com/webtorrent/bittorrent-tracker/compare/v11.1.2...v11.2.0) (2024-12-28)


### Features

* release [#539](https://github.com/webtorrent/bittorrent-tracker/issues/539) and [#535](https://github.com/webtorrent/bittorrent-tracker/issues/535) ([#544](https://github.com/webtorrent/bittorrent-tracker/issues/544)) ([e7de90c](https://github.com/webtorrent/bittorrent-tracker/commit/e7de90c0cbcfb41c9c53c5caf69cc37c6d3ef1e8))

## [11.1.2](https://github.com/webtorrent/bittorrent-tracker/compare/v11.1.1...v11.1.2) (2024-08-13)


### Bug Fixes

* statuscode ([#526](https://github.com/webtorrent/bittorrent-tracker/issues/526)) ([e9d8f8c](https://github.com/webtorrent/bittorrent-tracker/commit/e9d8f8cd754ba26d86f32f9b8da0c0c4a3dcd646))

## [11.1.1](https://github.com/webtorrent/bittorrent-tracker/compare/v11.1.0...v11.1.1) (2024-07-01)


### Performance Improvements

* drop clone ([#523](https://github.com/webtorrent/bittorrent-tracker/issues/523)) ([83a24ce](https://github.com/webtorrent/bittorrent-tracker/commit/83a24ce77fb1a96b7fe4c383ce92d7c28fc165a7))

# [11.1.0](https://github.com/webtorrent/bittorrent-tracker/compare/v11.0.2...v11.1.0) (2024-05-22)


### Bug Fixes

* semantic release ([#520](https://github.com/webtorrent/bittorrent-tracker/issues/520)) ([428fb22](https://github.com/webtorrent/bittorrent-tracker/commit/428fb224f5666731332738032649f4448b2e1e4f))


### Features

* updated webrtc implementation ([#519](https://github.com/webtorrent/bittorrent-tracker/issues/519)) ([633d68a](https://github.com/webtorrent/bittorrent-tracker/commit/633d68a32c2c143fec0182317a9801dd1b64faef))

## [11.0.2](https://github.com/webtorrent/bittorrent-tracker/compare/v11.0.1...v11.0.2) (2024-03-12)


### Bug Fixes

* **parse-http:** ignore announcements from peers with invalid announcement ports. ([#513](https://github.com/webtorrent/bittorrent-tracker/issues/513)) ([fe75272](https://github.com/webtorrent/bittorrent-tracker/commit/fe75272d51653e626583689081afb0b7aeadb84f))

## [11.0.1](https://github.com/webtorrent/bittorrent-tracker/compare/v11.0.0...v11.0.1) (2024-01-16)


### Bug Fixes

* update build badge url ([#506](https://github.com/webtorrent/bittorrent-tracker/issues/506)) ([3f59b58](https://github.com/webtorrent/bittorrent-tracker/commit/3f59b58a020ea8c0926be135471a6666fe8e8b21))

# [11.0.0](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.12...v11.0.0) (2023-10-31)


### Features

* **major:** drop simple-get ([#443](https://github.com/webtorrent/bittorrent-tracker/issues/443)) ([bce64e1](https://github.com/webtorrent/bittorrent-tracker/commit/bce64e155df6ff9fa605898cbf7498bf76188d8b))


### BREAKING CHANGES

* **major:** drop simple-get

* perf: drop simple-get

* feat: undici agent and socks

* fix: undici as dev dependency

* feat: require user passed proxy objects for http and ws

* chore: include undici for tests

## [10.0.12](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.11...v10.0.12) (2023-08-09)


### Bug Fixes

* **deps:** update dependency bencode to v4 ([#487](https://github.com/webtorrent/bittorrent-tracker/issues/487)) ([aeccf9c](https://github.com/webtorrent/bittorrent-tracker/commit/aeccf9c1c4b9115fd23b4fe1a0ab990b5add0f17))

## [10.0.11](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.10...v10.0.11) (2023-08-01)


### Bug Fixes

* mangled scrape infohashes ([#486](https://github.com/webtorrent/bittorrent-tracker/issues/486)) ([11cce83](https://github.com/webtorrent/bittorrent-tracker/commit/11cce83ddd858813f5684da8a116de4bee6e518b))

## [10.0.10](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.9...v10.0.10) (2023-06-16)


### Performance Improvements

* use simple-peer/lite ([#475](https://github.com/webtorrent/bittorrent-tracker/issues/475)) ([5b8db06](https://github.com/webtorrent/bittorrent-tracker/commit/5b8db067e48cc81796728ff538d7ff6efafc59b8))

## [10.0.9](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.8...v10.0.9) (2023-06-16)


### Performance Improvements

* use peer/lite ([#474](https://github.com/webtorrent/bittorrent-tracker/issues/474)) ([7c845f0](https://github.com/webtorrent/bittorrent-tracker/commit/7c845f030d07b1bf7060ab880b790ee85a8c7ac0))

## [10.0.8](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.7...v10.0.8) (2023-06-07)


### Bug Fixes

* bigInt ([#472](https://github.com/webtorrent/bittorrent-tracker/issues/472)) ([d7061f7](https://github.com/webtorrent/bittorrent-tracker/commit/d7061f73b2ebff072e064971a5960749a7335bae))

## [10.0.7](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.6...v10.0.7) (2023-06-05)


### Bug Fixes

* imports ([#471](https://github.com/webtorrent/bittorrent-tracker/issues/471)) ([a12022a](https://github.com/webtorrent/bittorrent-tracker/commit/a12022ac2c81d7fa3ecb81163852161e64199cf4))

## [10.0.6](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.5...v10.0.6) (2023-05-27)


### Bug Fixes

* replace simple-peer with maintained one ([#466](https://github.com/webtorrent/bittorrent-tracker/issues/466)) ([3b2dedb](https://github.com/webtorrent/bittorrent-tracker/commit/3b2dedb4151615831ca12d3d0a830354b1c04e68))

## [10.0.5](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.4...v10.0.5) (2023-05-27)


### Bug Fixes

* only stringify views ([#467](https://github.com/webtorrent/bittorrent-tracker/issues/467)) ([52f5502](https://github.com/webtorrent/bittorrent-tracker/commit/52f55020f38894e4d45e12c87184540d8b0acad3))

## [10.0.4](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.3...v10.0.4) (2023-05-26)


### Bug Fixes

* drop buffer ([#465](https://github.com/webtorrent/bittorrent-tracker/issues/465)) ([c99eb89](https://github.com/webtorrent/bittorrent-tracker/commit/c99eb892088ef3c67ea5bf014dfdd86799251a7e))

## [10.0.3](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.2...v10.0.3) (2023-05-25)


### Performance Improvements

* replace simple websocket with maintained one ([#464](https://github.com/webtorrent/bittorrent-tracker/issues/464)) ([3f01c29](https://github.com/webtorrent/bittorrent-tracker/commit/3f01c29122efd726d805673da82f43ce5592b793))

## [10.0.2](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.1...v10.0.2) (2023-02-01)


### Bug Fixes

* **deps:** update dependency ws to v8 ([#448](https://github.com/webtorrent/bittorrent-tracker/issues/448)) ([2209d4f](https://github.com/webtorrent/bittorrent-tracker/commit/2209d4f21bdee10e575c1728c3accf7bd34380c9)), closes [#449](https://github.com/webtorrent/bittorrent-tracker/issues/449)

## [10.0.1](https://github.com/webtorrent/bittorrent-tracker/compare/v10.0.0...v10.0.1) (2022-12-07)


### Bug Fixes

* **deps:** update dependency bencode to v3 ([#434](https://github.com/webtorrent/bittorrent-tracker/issues/434)) [skip ci] ([926ceee](https://github.com/webtorrent/bittorrent-tracker/commit/926ceee0bac6dfe49877566aaa3cf645689492d1))
* **deps:** update dependency string2compact to v2 ([#437](https://github.com/webtorrent/bittorrent-tracker/issues/437)) ([9be843c](https://github.com/webtorrent/bittorrent-tracker/commit/9be843c5e46ac2ab518187bf0d348e1e69e8633d))

# [10.0.0](https://github.com/webtorrent/bittorrent-tracker/compare/v9.19.0...v10.0.0) (2022-12-05)


### Features

* esm ([#431](https://github.com/webtorrent/bittorrent-tracker/issues/431)) ([e6d3189](https://github.com/webtorrent/bittorrent-tracker/commit/e6d3189edf1a170197a799b97d84c632692b394f))


### BREAKING CHANGES

* ESM only

* feat: esm

* fix: linter oops

# [9.19.0](https://github.com/webtorrent/bittorrent-tracker/compare/v9.18.6...v9.19.0) (2022-06-01)


### Features

* **events:** Support of `paused` client event ([#411](https://github.com/webtorrent/bittorrent-tracker/issues/411)) ([ef76b3f](https://github.com/webtorrent/bittorrent-tracker/commit/ef76b3f3b6beee87f57d74addd0ca2ef2c517b6d))

## [9.18.6](https://github.com/webtorrent/bittorrent-tracker/compare/v9.18.5...v9.18.6) (2022-05-11)


### Bug Fixes

* revert [#420](https://github.com/webtorrent/bittorrent-tracker/issues/420) ([8d54938](https://github.com/webtorrent/bittorrent-tracker/commit/8d54938f164347d57a7991268d191e44b752de7f))

## [9.18.5](https://github.com/webtorrent/bittorrent-tracker/compare/v9.18.4...v9.18.5) (2022-03-25)


### Bug Fixes

* connection leaks ([#420](https://github.com/webtorrent/bittorrent-tracker/issues/420)) ([f7928cf](https://github.com/webtorrent/bittorrent-tracker/commit/f7928cfcc646cd95556549b64e61228892314682))

## [9.18.4](https://github.com/webtorrent/bittorrent-tracker/compare/v9.18.3...v9.18.4) (2022-03-06)


### Bug Fixes

* typo in ws example ([#417](https://github.com/webtorrent/bittorrent-tracker/issues/417)) ([023afb9](https://github.com/webtorrent/bittorrent-tracker/commit/023afb9a3228d60392a18e70f85cdb6af5fa79fb))

## [9.18.3](https://github.com/webtorrent/bittorrent-tracker/compare/v9.18.2...v9.18.3) (2021-10-29)


### Bug Fixes

* **deps:** update dependency clone to v2 ([#393](https://github.com/webtorrent/bittorrent-tracker/issues/393)) ([dc6f796](https://github.com/webtorrent/bittorrent-tracker/commit/dc6f7966844216c39491d6623dd412d5ca65d4c4))

## [9.18.2](https://github.com/webtorrent/bittorrent-tracker/compare/v9.18.1...v9.18.2) (2021-09-02)


### Bug Fixes

* **deps:** update dependency socks to v2 ([#394](https://github.com/webtorrent/bittorrent-tracker/issues/394)) ([353e1f4](https://github.com/webtorrent/bittorrent-tracker/commit/353e1f40093a5e74cb54219abbae8ef0cc3d9e0b))

## [9.18.1](https://github.com/webtorrent/bittorrent-tracker/compare/v9.18.0...v9.18.1) (2021-09-01)


### Bug Fixes

* disable socks in chromeapp ([#398](https://github.com/webtorrent/bittorrent-tracker/issues/398)) ([7fd5877](https://github.com/webtorrent/bittorrent-tracker/commit/7fd587789548453a852ea01e54900a5e9155db67))

# [9.18.0](https://github.com/webtorrent/bittorrent-tracker/compare/v9.17.4...v9.18.0) (2021-08-20)


### Features

* add proxy support for tracker clients ([#356](https://github.com/webtorrent/bittorrent-tracker/issues/356)) ([ad64dc3](https://github.com/webtorrent/bittorrent-tracker/commit/ad64dc3a68cddccc2c1f05d0d8bb833f2c4860b2))

## [9.17.4](https://github.com/webtorrent/bittorrent-tracker/compare/v9.17.3...v9.17.4) (2021-07-22)


### Bug Fixes

* if websocket closed, don't produce a response ([ca88435](https://github.com/webtorrent/bittorrent-tracker/commit/ca88435617e59714a456031c75b3a329897d97bd))

## [9.17.3](https://github.com/webtorrent/bittorrent-tracker/compare/v9.17.2...v9.17.3) (2021-07-02)


### Bug Fixes

* auto update authors on version ([b5ffc70](https://github.com/webtorrent/bittorrent-tracker/commit/b5ffc708ada0bef66e7fa0cd1872527ea6dd8d53))

## [9.17.2](https://github.com/webtorrent/bittorrent-tracker/compare/v9.17.1...v9.17.2) (2021-06-15)


### Bug Fixes

* modernize ([e5994d2](https://github.com/webtorrent/bittorrent-tracker/commit/e5994d2ebdec10fe2165e31f5b498382eeeaaf5f))

## [9.17.1](https://github.com/webtorrent/bittorrent-tracker/compare/v9.17.0...v9.17.1) (2021-06-15)


### Bug Fixes

* add package-lock ([0e486b0](https://github.com/webtorrent/bittorrent-tracker/commit/0e486b09d80d30e1c13d4624e29c4251000d4092))
* **deps:** update dependency bn.js to ^5.2.0 ([2d36e4a](https://github.com/webtorrent/bittorrent-tracker/commit/2d36e4ae60b1bac51773f2dca81c1a158b51cb28))
* **deps:** update dependency chrome-dgram to ^3.0.6 ([a82aaaa](https://github.com/webtorrent/bittorrent-tracker/commit/a82aaaa31963a0d9adb640166f417142c5d7b970))
* **deps:** update dependency run-parallel to ^1.2.0 ([fcf25ed](https://github.com/webtorrent/bittorrent-tracker/commit/fcf25ed40e1fd64e630b10a0281bc09604b901d3))
* **deps:** update dependency run-series to ^1.1.9 ([fa2c33f](https://github.com/webtorrent/bittorrent-tracker/commit/fa2c33fc91f8ef0a47d0f40b7a046ae179ee328a))
* **deps:** update dependency simple-websocket to ^9.1.0 ([96fedbd](https://github.com/webtorrent/bittorrent-tracker/commit/96fedbdf56ddcf6627eb373a33589db885cb4fb7))
* **deps:** update dependency ws to ^7.4.5 ([6ad7ead](https://github.com/webtorrent/bittorrent-tracker/commit/6ad7ead994e5cb99980a406aea908e4b9ff6151c))
* **deps:** update webtorrent ([1e8d47d](https://github.com/webtorrent/bittorrent-tracker/commit/1e8d47dcd8f5f53b42aa75265a129f950d16feef))
* UDP url parsing ([8e24a8c](https://github.com/webtorrent/bittorrent-tracker/commit/8e24a8c97b55bbaaf2c92a496d1cd30b0c008934))
