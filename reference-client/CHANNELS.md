# Torrent Channels

Usenet-style topic subscriptions over BitTorrent DHT.

## Overview

Torrent Channels brings the Usenet newsgroup model to BitTorrent. Publishers announce content to hierarchical topics (like `mp3/electronic` or `software/linux/distros`), and subscribers automatically discover new content from all publishers in topics they follow.

Unlike traditional torrent discovery (searching indexers, following RSS feeds), Channels provides:

- **Decentralized discovery** - No central server or indexer required
- **Hierarchical organization** - Browse topics like a filesystem
- **Publisher identity** - Cryptographically signed announcements
- **Filtering** - Subscribe with keyword filters, size limits, age restrictions
- **Persistence** - Subscriptions survive restarts

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         DHT Network                              │
│                     (BEP 44 Mutable Items)                       │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │ get/put
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      TopicRegistry                               │
│  - Topic addressing: SHA1(pubkey + SHA256("supertorrent:topic:" │
│                            + topicPath))                         │
│  - Publisher registration/discovery                              │
│  - Subtopic hierarchy                                            │
│  - Auto-republishing (45min interval)                            │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         Channel                                  │
│  - High-level publish/subscribe API                              │
│  - Content filtering (keywords, age, size)                       │
│  - Manifest validation                                           │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    SubscriptionStore                             │
│  - JSON persistence                                              │
│  - Filter storage                                                │
│  - Resume from last sequence                                     │
└─────────────────────────────────────────────────────────────────┘
```

### How Topic Addressing Works

Each topic maps to a DHT location using BEP 44 mutable items:

1. **Salt generation**: `salt = SHA256("supertorrent:topic:" + normalizedPath)`
2. **Target ID**: `targetId = SHA1(registryPublicKey + salt)`
3. **Registry entry**: JSON with `{ publishers: [...], subtopics: [...] }`

Topics are normalized (lowercase, deduplicated slashes, no leading/trailing slashes) to ensure consistent addressing.

### BEP 44 Constraints

- Maximum value size: 1000 bytes
- Entry expiration: ~2 hours (requires republishing)
- Sequence numbers must monotonically increase
- CAS (compare-and-swap) prevents race conditions

## CLI Commands

### Browse Topics

```bash
# Browse root level
node index.js channel-browse

# Browse specific topic
node index.js channel-browse mp3
node index.js channel-browse mp3/electronic
```

### Subscribe to a Topic

```bash
# Basic subscription
node index.js channel-subscribe mp3/electronic

# With filters (JSON file)
node index.js channel-subscribe mp3/electronic -f filters.json

# With inline filters
node index.js channel-subscribe mp3/electronic -f '{"keywords":["remix"],"maxAge":"7d"}'
```

Filter options:
- `keywords`: Array of required keywords (OR match)
- `excludeKeywords`: Array of keywords to exclude
- `maxAge`: Maximum content age (`7d`, `24h`, `30m`)
- `maxSize`: Maximum total size in bytes
- `minSeeders`: Minimum seeder count

### Unsubscribe

```bash
node index.js channel-unsubscribe mp3/electronic
```

### Publish to a Topic

```bash
# Requires identity (run gen-key first)
node index.js channel-publish mp3/electronic -i manifest.json -k identity.json
```

The publisher will auto-republish every 45 minutes to keep the DHT entry alive.

### List Subscriptions

```bash
node index.js channel-list
```

## Programmatic API

### TopicRegistry

Low-level DHT operations for topic management.

```javascript
import DHT from 'bittorrent-dht'
import { TopicRegistry, topicToSalt } from './lib/topic-registry.js'

const dht = new DHT()
await new Promise(resolve => dht.on('ready', resolve))

const registry = new TopicRegistry(dht, keypair)

// Register as publisher
await registry.registerPublisher('mp3/electronic', {
  name: 'DJ Example',
  description: 'Electronic music releases'
})

// Subscribe to topic updates
const subscription = await registry.subscribe('mp3/electronic')

subscription.on('publisher-added', (publishers) => {
  console.log('New publishers:', publishers)
})

subscription.on('publisher-removed', (publishers) => {
  console.log('Publishers left:', publishers)
})

// One-shot queries
const publishers = await registry.listPublishers('mp3/electronic')
const subtopics = await registry.listSubtopics('mp3')
const { publishers, subtopics } = await registry.browse('mp3')

// Cleanup
registry.destroy()
```

### Channel

High-level API with filtering and manifest integration.

```javascript
import { createChannel } from './lib/channels.js'

const channel = await createChannel(dht, { keypair })

// Publish content
const manifest = await channel.publish('mp3/electronic', collections, {
  name: 'DJ Example',
  description: 'Weekly releases'
})

// Subscribe with filters
const subscription = await channel.subscribe('mp3/electronic', {
  filters: {
    keywords: ['remix', 'original'],
    maxAge: '7d',
    maxSize: 1024 * 1024 * 500 // 500MB
  }
})

subscription.on('content', ({ publisher, manifest }) => {
  console.log(`New content from ${publisher.name}`)
  manifest.collections.forEach(col => {
    console.log(`  ${col.title}: ${col.items.length} items`)
  })
})

// Update filters dynamically
subscription.setFilters({ keywords: ['house', 'techno'] })

// Cleanup
channel.destroy()
```

### SubscriptionStore

Persist subscriptions across restarts.

```javascript
import { SubscriptionStore } from './lib/subscription-store.js'

const store = new SubscriptionStore('./subscriptions.json')
store.load()

// Add subscription
store.addSubscription('mp3/electronic', {
  filters: { keywords: ['mix'] }
})

// Update progress
store.updateLastSeq('mp3/electronic', 42)

// Export for backup
const backup = store.export()

// Import from backup
store.import(backup, { merge: true })

// Cleanup
store.destroy()
```

## Manifest Format

Manifests now support topic tags:

```javascript
import { createManifest } from './lib/manifest.js'

const manifest = createManifest(keypair, sequence, collections, {
  topics: ['mp3/electronic', 'mp3/house'],
  metadata: {
    name: 'January 2026 Mix',
    description: 'Monthly compilation'
  }
})
```

## Future Ideas

### 1. Multi-Registry Federation

Currently, topics are scoped to a single registry keypair. Federation would allow multiple registries to mirror each other:

- **Cross-registry discovery**: Query multiple well-known registries in parallel
- **Registry reputation**: Track reliability and uptime of different registries
- **Conflict resolution**: Merge publisher lists from multiple sources
- **Delegation**: Registries can delegate subtopics to specialized operators

### 2. Publisher Reputation System

Track publisher quality over time:

- **Seeder ratio**: Do their torrents stay seeded?
- **Validity rate**: How often do their magnets/hashes work?
- **Content quality signals**: Community upvotes/downvotes stored in DHT
- **Web of trust**: Publishers vouch for each other
- **Stake-based reputation**: Publishers bond tokens that can be slashed for bad behavior

### 3. Content Verification Layer

Verify content matches descriptions before downloading:

- **Perceptual hashes**: Audio/video fingerprints stored in manifests
- **File type verification**: Ensure claimed MP3 is actually audio
- **Size consistency**: Warn when file size doesn't match metadata
- **Malware scanning**: Community-reported hash blacklists
- **NSFW detection**: Optional AI-based content classification

### 4. Privacy Enhancements

Protect subscriber privacy:

- **Onion routing for DHT queries**: Route through Tor/I2P
- **Private subscriptions**: Encrypted subscription list
- **Plausible deniability**: Subscribe to decoy topics
- **Anonymous publishing**: Publish through mixnet
- **Subscription aggregators**: Third parties that poll on your behalf

### 5. Smart Filtering

More sophisticated content matching:

- **Regex patterns**: `.*\.(flac|wav)$` for lossless audio
- **Semantic search**: "similar to Daft Punk" using embeddings
- **Collaborative filtering**: "Users who like X also subscribed to Y"
- **Release group preferences**: Prioritize specific release groups
- **Duplicate detection**: Skip content already downloaded under different name

### 6. Incentivized Seeding

Encourage long-term seeding:

- **Proof-of-seeding**: Cryptographic proofs that you're serving data
- **Token rewards**: Earn tokens for bandwidth contributed
- **Reputation bonuses**: Higher reputation for consistent seeders
- **Insurance pools**: Publishers pay into pool, seeders claim from it
- **Bandwidth markets**: Real-time pricing for seeding services

### 7. Moderation and Governance

Community-driven content moderation:

- **Topic moderators**: Designated keys that can remove publishers
- **Voting mechanisms**: Token-weighted or reputation-weighted votes
- **Appeal process**: Multi-sig override for contested removals
- **Topic forking**: Disagreements lead to topic splits (like subreddit forks)
- **Blocklists**: Shareable publisher blocklists

### 8. Cross-Protocol Integration

Bridge to other networks:

- **IPFS pinning**: Auto-pin content to IPFS for redundancy
- **Nostr integration**: Publish announcements to Nostr relays
- **RSS bridge**: Generate RSS feeds from channel subscriptions
- **Matrix/Discord bots**: Notify chat rooms of new content
- **ActivityPub**: Federate with Mastodon/Lemmy

### 9. Hierarchical Caching

Optimize DHT performance:

- **Supernode caches**: Well-connected nodes cache popular topics
- **Regional mirrors**: Geographic distribution of registry data
- **Predictive prefetching**: Pre-fetch likely subtopics
- **Compression**: Bencode or CBOR instead of JSON
- **Batch queries**: Single DHT lookup for multiple topics

### 10. Mobile and Lightweight Clients

Support resource-constrained devices:

- **Gateway nodes**: HTTP API frontends to DHT
- **Push notifications**: WebPush for new content alerts
- **Selective sync**: Only download metadata, not full manifests
- **Offline browsing**: Cache topic hierarchy locally
- **QR code sharing**: Share topic subscriptions via QR

### 11. Versioned Topics

Handle topic evolution:

- **Schema versions**: Topics declare their manifest schema version
- **Migration paths**: Automated upgrades between schema versions
- **Deprecation notices**: Publishers announce topic moves
- **Aliases**: Multiple paths resolve to same topic
- **Canonical URLs**: Permanent identifiers for topics

### 12. Analytics and Insights

Optional, privacy-preserving analytics:

- **Subscriber counts**: Aggregate counts without revealing identities
- **Trend detection**: Identify rising topics
- **Publisher dashboards**: View reach and engagement
- **Network health**: Monitor DHT replication and latency
- **Content popularity**: Track which items get downloaded most
