# Blob Distribution

Distributed file hosting using encrypted blob storage over P2P.

## Overview

Files are split into encrypted chunks called "blobs" that are distributed across the network. Each blob is:
- Encrypted with a unique ChaCha20-Poly1305 key
- Identified by its SHA256 hash
- Stored and transferred independently
- Meaningless without the FileEntry metadata

This provides **plausible deniability** - hosts only store random-looking encrypted data and cannot know what content they're serving.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         DHT Network                              │
│                    (Blob peer discovery)                         │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │ announce/lookup
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       BlobTracker                                │
│  - Announce blobs to DHT                                         │
│  - Look up peers that have specific blobs                        │
│  - Auto-reannounce every 15 minutes                              │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       BlobNetwork                                │
│  - WebSocket-based P2P connections                               │
│  - Binary protocol for blob transfer                             │
│  - Request/response with verification                            │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        BlobStore                                 │
│  - Local blob storage with LRU eviction                          │
│  - SHA256 verification on put                                    │
│  - Pinning support for important blobs                           │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        BlobClient                                │
│  - High-level seed/fetch API                                     │
│  - Coordinates tracker, network, and store                       │
│  - Progress callbacks                                            │
└─────────────────────────────────────────────────────────────────┘
```

## Wire Protocol

BlobNetwork uses a simple binary protocol over WebSocket:

### Message Types

| Type | ID | Direction | Description |
|------|-----|-----------|-------------|
| HANDSHAKE | 0 | Both | Initial connection setup |
| HANDSHAKE_ACK | 1 | Both | Handshake response |
| REQUEST | 2 | Client→Server | Request blob by ID |
| RESPONSE | 3 | Server→Client | Blob data (binary) |
| HAVE | 4 | Both | Announce available blobs |
| WANT | 5 | Both | Query for specific blobs |
| NOT_FOUND | 6 | Server→Client | Blob not available |
| ERROR | 7 | Both | Error message |

### Binary Format (RESPONSE)

```
┌────────┬────────────┬──────────────────┬─────────────┐
│ Type   │ Request ID │ Blob ID          │ Data        │
│ 1 byte │ 4 bytes    │ 32 bytes         │ N bytes     │
└────────┴────────────┴──────────────────┴─────────────┘
```

## CLI Commands

### Seed Blobs

Make blobs available to the network:

```bash
# Ingest a file first
node index.js ingest -i movie.mkv > file_entry.json

# Start seeding
node index.js blob-seed -i file_entry.json
```

The seeder will:
1. Load blobs from the store
2. Announce each blob to the DHT
3. Accept incoming peer connections
4. Serve blob requests

### Fetch File

Download and reassemble a file from its FileEntry:

```bash
node index.js blob-fetch -i file_entry.json -o downloaded_movie.mkv
```

The fetcher will:
1. Look up peers for each blob in DHT
2. Connect to peers
3. Download and verify each blob
4. Decrypt and reassemble the file

### Check Status

View blob store statistics:

```bash
node index.js blob-status
```

## Programmatic API

### BlobStore

Local storage with LRU eviction:

```javascript
import { BlobStore } from './lib/blob-store.js'

const store = new BlobStore('./storage', {
  maxSize: 1024 * 1024 * 1024 * 50  // 50GB
})

// Store blob (verifies SHA256)
store.put(blobId, buffer)

// Retrieve blob
const data = store.get(blobId)

// Check existence
if (store.has(blobId)) { ... }

// Pin blob (prevent eviction)
store.pin(blobId)

// Get stats
const { blobCount, currentSize, maxSize } = store.stats()
```

### BlobNetwork

P2P blob transfer:

```javascript
import { BlobNetwork } from './lib/blob-network.js'

const network = new BlobNetwork(store, { port: 6881 })
await network.listen()

// Connect to peer
await network.connect('ws://192.168.1.5:6881')

// Request specific blob
const blob = await network.requestBlob(blobId)

// Announce blobs we have
network.announceBlob(blobId)

// Query network for blob
network.queryBlob(blobId)

// Events
network.on('peer', ({ address, peerId }) => { ... })
network.on('upload', ({ blobId, size, peer }) => { ... })
network.on('download', ({ blobId, size, peer }) => { ... })
network.on('have', ({ peer, blobIds }) => { ... })
```

### BlobTracker

DHT-based peer discovery:

```javascript
import { BlobTracker } from './lib/blob-tracker.js'
import DHT from 'bittorrent-dht'

const dht = new DHT()
const tracker = new BlobTracker(dht, { port: 6881 })

// Announce blob location
tracker.announce(blobId)

// Find peers with blob
const peers = await tracker.lookup(blobId)
// [{ host: '192.168.1.5', port: 6881, address: 'ws://...' }]

// Announce multiple blobs
tracker.announceAll(blobIds)
```

### BlobClient

High-level operations:

```javascript
import { createBlobClient } from './lib/storage.js'

const client = createBlobClient(store, network, tracker)

// Seed a file entry
await client.seed(fileEntry)

// Fetch and reassemble
const buffer = await client.fetch(fileEntry, {
  onProgress: ({ completed, total }) => {
    console.log(`${completed}/${total} chunks`)
  }
})
```

## Security Model

### What Hosts Know

| Information | Visible to Host? |
|-------------|------------------|
| Blob content (encrypted) | Yes |
| Blob IDs (SHA256 hashes) | Yes |
| What file a blob belongs to | No |
| Decryption keys | No |
| File names | No |
| Who requested the blob | Yes (IP address) |

### What Attackers Need

To reconstruct a file, an attacker needs:
1. All blob IDs for the file
2. Access to peers hosting those blobs
3. The FileEntry with decryption keys

The FileEntry is only shared via signed manifests to subscribers.

## Data Flow

### Publishing Content

```
1. ingest -i file.mkv
   └─> Split into 1MB chunks
   └─> Encrypt each chunk (random key per chunk)
   └─> Store blobs locally
   └─> Generate FileEntry JSON

2. blob-seed -i file_entry.json
   └─> Announce blobs to DHT
   └─> Listen for peer connections
   └─> Serve blob requests

3. channel-publish topic -i manifest.json
   └─> Publish manifest with FileEntry
   └─> Subscribers receive FileEntry
```

### Consuming Content

```
1. Subscribe to channel
   └─> Receive manifest with FileEntry

2. blob-fetch -i file_entry.json -o output
   └─> For each chunk:
       └─> Look up blob ID in DHT
       └─> Connect to peers
       └─> Download blob
       └─> Verify SHA256
       └─> Store locally
   └─> Decrypt and reassemble file
```

## Configuration

### Storage Limits

```javascript
const store = new BlobStore('./storage', {
  maxSize: 1024 * 1024 * 1024 * 100  // 100GB limit
})
```

When the limit is reached, least-recently-accessed blobs are evicted (unless pinned).

### Network Options

```javascript
const network = new BlobNetwork(store, {
  port: 6881,           // Listen port (0 = random)
  maxConnections: 50,   // Max simultaneous peers
  peerId: 'custom-id'   // Custom peer identifier
})
```

### Tracker Options

```javascript
const tracker = new BlobTracker(dht, {
  port: 6881,                        // Port to announce
  announceInterval: 15 * 60 * 1000   // Reannounce every 15min
})
```

## Limitations

Current implementation limitations:

1. **No NAT traversal** - Peers behind NAT may not be reachable
2. **No encryption in transit** - Blobs are encrypted at rest but WebSocket can be plain
3. **No bandwidth limiting** - No rate limits on upload/download
4. **Single-threaded** - Downloads are sequential per file
5. **No resume** - Partial downloads must restart

## Future Improvements

See CHANNELS.md for broader roadmap. Blob-specific improvements:

- **WebRTC data channels** for browser-to-browser transfer
- **UTP protocol** for better NAT traversal
- **Merkle proofs** for partial blob verification
- **Erasure coding** for redundancy without full replication
- **Bandwidth accounting** for fair sharing
- **Blob marketplace** for paid hosting
