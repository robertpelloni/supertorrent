#!/usr/bin/env node

import fs from 'fs'
import path from 'path'
import minimist from 'minimist'
import Client from 'bittorrent-tracker'
import DHT from 'bittorrent-dht'
import { generateKeypair } from './lib/crypto.js'
import { createManifest, validateManifest } from './lib/manifest.js'
import { ingest, reassemble, BlobClient, createBlobClient } from './lib/storage.js'
import { BlobStore } from './lib/blob-store.js'
import { BlobNetwork } from './lib/blob-network.js'
import { BlobTracker } from './lib/blob-tracker.js'
import { Channel, createChannel } from './lib/channels.js'
import { SubscriptionStore } from './lib/subscription-store.js'

const argv = minimist(process.argv.slice(2), {
  alias: {
    k: 'keyfile',
    t: 'tracker',
    i: 'input',
    o: 'output',
    d: 'dir',
    f: 'filters'
  },
  default: {
    keyfile: './identity.json',
    tracker: 'ws://localhost:8000',
    dir: './storage',
    subscriptions: './subscriptions.json'
  }
})

const command = argv._[0]

if (!command) {
  console.error(`Usage:
  gen-key [-k identity.json]
  ingest -i <file> [-d ./storage] -> Returns FileEntry JSON
  publish [-k identity.json] [-t ws://tracker] -i <file_entry.json>
  subscribe [-t ws://tracker] <public_key_hex> [-d ./storage]
  
  Channel Commands:
  channel-browse [topic_path]         - Browse topic hierarchy
  channel-subscribe <topic> [-f filters.json] - Subscribe to a topic
  channel-unsubscribe <topic>         - Unsubscribe from a topic
  channel-publish <topic> -i <manifest.json> [-k identity.json] - Publish to topic
  channel-list                        - List active subscriptions
  
  Blob Commands:
  blob-seed -i <file_entry.json>      - Seed blobs from a file entry
  blob-fetch -i <file_entry.json> -o <output> - Fetch and reassemble file
  blob-status                         - Show blob store and network status
  `)
  process.exit(1)
}

// Ensure storage dir exists
if (!fs.existsSync(argv.dir)) {
  fs.mkdirSync(argv.dir, { recursive: true })
}

// 1. Generate Key
if (command === 'gen-key') {
  const keypair = generateKeypair()
  const data = {
    publicKey: keypair.publicKey.toString('hex'),
    secretKey: keypair.secretKey.toString('hex')
  }
  fs.writeFileSync(argv.keyfile, JSON.stringify(data, null, 2))
  console.log(`Identity generated at ${argv.keyfile}`)
  console.log(`Public Key: ${data.publicKey}`)
  process.exit(0)
}

// 2. Ingest
if (command === 'ingest') {
  if (!argv.input) {
    console.error('Please specify input file with -i')
    process.exit(1)
  }
  const fileBuf = fs.readFileSync(argv.input)
  const result = ingest(fileBuf, path.basename(argv.input))

  // Save Blobs
  result.blobs.forEach(blob => {
    fs.writeFileSync(path.join(argv.dir, blob.id), blob.buffer)
  })

  console.log(`Ingested ${result.blobs.length} blobs to ${argv.dir}`)
  console.log('FileEntry JSON (save this to a file to publish it):')
  console.log(JSON.stringify(result.fileEntry, null, 2))
  process.exit(0)
}

// 3. Publish
if (command === 'publish') {
  if (!fs.existsSync(argv.keyfile)) {
    console.error('Keyfile not found. Run gen-key first.')
    process.exit(1)
  }
  const keyData = JSON.parse(fs.readFileSync(argv.keyfile))
  const keypair = {
    publicKey: Buffer.from(keyData.publicKey, 'hex'),
    secretKey: Buffer.from(keyData.secretKey, 'hex')
  }

  // Read Input
  if (!argv.input) {
    console.error('Please specify input file with -i (json file entry or text list)')
    process.exit(1)
  }

  const content = fs.readFileSync(argv.input, 'utf-8')
  let items
  try {
    // Try parsing as JSON (FileEntry)
    const json = JSON.parse(content)
    // Wrap in our "Items" list.
    // In the future, "Items" can be Magnet Links OR FileEntries.
    items = [json]
  } catch (e) {
    // Fallback: Line-separated magnet links
    items = content.split('\n').map(l => l.trim()).filter(l => l.length > 0)
  }

  // Create Collections structure
  const collections = [{
    title: 'Default Collection',
    items
  }]

  // Create Manifest
  const sequence = Date.now()
  const manifest = createManifest(keypair, sequence, collections)

  console.log('Publishing manifest:', JSON.stringify(manifest, null, 2))

  // Connect to Tracker
  const client = new Client({
    infoHash: Buffer.alloc(20), // Dummy, not used for custom proto
    peerId: Buffer.alloc(20), // Dummy
    announce: [argv.tracker],
    port: 6666
  })

  client.on('error', err => console.error('Client Error:', err.message))

  client.on('update', () => {
    // We don't expect standard updates
  })

  // We need to access the underlying socket to send our custom message
  // bittorrent-tracker abstracts this, so we might need to hook into the `announce` phase or just use the socket directly if exposed.
  // The library exposes `client._trackers` which is a list of Tracker instances.

  // Wait for socket connection
  setTimeout(() => {
    const trackers = client._trackers
    let sent = false

    for (const tracker of trackers) {
      // We only support WebSocket for this custom protocol right now
      if (tracker.socket && tracker.socket.readyState === 1) { // OPEN
        console.log('Sending publish message to ' + tracker.announceUrl)
        tracker.socket.send(JSON.stringify({
          action: 'publish',
          manifest
        }))
        sent = true
      }
    }

    if (sent) console.log('Publish sent!')
    else console.error('No connected websocket trackers found.')

    setTimeout(() => {
      client.destroy()
      process.exit(0)
    }, 1000)
  }, 1000)
}

// 4. Subscribe
if (command === 'subscribe') {
  const pubKeyHex = argv._[1]
  if (!pubKeyHex) {
    console.error('Please provide public key hex')
    process.exit(1)
  }

  console.log(`Subscribing to ${pubKeyHex}...`)

  const client = new Client({
    infoHash: Buffer.alloc(20),
    peerId: Buffer.alloc(20),
    announce: [argv.tracker],
    port: 6667
  })

  client.on('error', err => console.error('Client Error:', err.message))

  // Hook into internal trackers to send subscribe
  setInterval(() => {
    const trackers = client._trackers
    for (const tracker of trackers) {
      if (tracker.socket && tracker.socket.readyState === 1 && !tracker._subscribed) {
        console.log('Sending subscribe to ' + tracker.announceUrl)
        tracker.socket.send(JSON.stringify({
          action: 'subscribe',
          key: pubKeyHex
        }))
        tracker._subscribed = true // simple flag to avoid spamming

        // Listen for responses
        const originalOnMessage = tracker.socket.onmessage
        tracker.socket.onmessage = (event) => {
          let data
          try { data = JSON.parse(event.data) } catch (e) { return }

          if (data.action === 'publish') {
            console.log('\n>>> RECEIVED UPDATE <<<')
            const valid = validateManifest(data.manifest)
            if (valid && data.manifest.publicKey === pubKeyHex) {
              console.log('VERIFIED UPDATE from ' + pubKeyHex)
              console.log('Sequence:', data.manifest.sequence)

              const items = data.manifest.collections[0].items
              console.log(`Received ${items.length} items.`)

              // Auto-Download Logic (Prototype)
              items.forEach(async (item, idx) => {
                if (typeof item === 'object' && item.chunks) {
                  console.log(`Item ${idx}: Detected Megatorrent FileEntry: ${item.name}`)
                  try {
                    const fileBuf = await reassemble(item, async (blobId) => {
                      const p = path.join(argv.dir, blobId)
                      if (fs.existsSync(p)) {
                        return fs.readFileSync(p)
                      }
                      // TODO: Network fetch
                      console.log(`Blob ${blobId} not found locally.`)
                      return null
                    })

                    if (fileBuf) {
                      const outPath = path.join(argv.dir, 'downloaded_' + item.name)
                      fs.writeFileSync(outPath, fileBuf)
                      console.log(`SUCCESS: Reassembled to ${outPath}`)
                    }
                  } catch (err) {
                    console.error('Failed to reassemble:', err.message)
                  }
                } else {
                  console.log(`Item ${idx}: Standard Magnet/Text: ${item}`)
                }
              })
            } else {
              console.error('Invalid signature or wrong key!')
            }
          } else {
            if (originalOnMessage) originalOnMessage(event)
          }
        }
      }
    }
  }, 1000)
}

let dht = null
let channel = null
let subscriptionStore = null

async function initChannel () {
  if (channel) return channel
  
  const keyData = fs.existsSync(argv.keyfile)
    ? JSON.parse(fs.readFileSync(argv.keyfile))
    : null
  
  const keypair = keyData ? {
    publicKey: Buffer.from(keyData.publicKey, 'hex'),
    secretKey: Buffer.from(keyData.secretKey, 'hex')
  } : null

  dht = new DHT()
  await new Promise(resolve => dht.on('ready', resolve))
  
  channel = createChannel(dht, { keypair })
  subscriptionStore = new SubscriptionStore(argv.subscriptions)
  subscriptionStore.load()
  
  return channel
}

function parseFilters (filtersArg) {
  if (!filtersArg) return {}
  if (fs.existsSync(filtersArg)) {
    return JSON.parse(fs.readFileSync(filtersArg, 'utf-8'))
  }
  return JSON.parse(filtersArg)
}

if (command === 'channel-browse') {
  const topicPath = argv._[1] || ''
  
  initChannel().then(async (ch) => {
    console.log(`Browsing topic: ${topicPath || '(root)'}`)
    console.log('---')
    
    const result = await ch.browse(topicPath)
    
    if (result.subtopics.length > 0) {
      console.log('Subtopics:')
      result.subtopics.forEach(st => console.log(`  ${topicPath}/${st}`))
    }
    
    if (result.publishers.length > 0) {
      console.log('\nPublishers:')
      result.publishers.forEach(p => {
        console.log(`  ${p.name || 'Unknown'} [${p.pk.slice(0, 16)}...]`)
      })
    }
    
    if (result.subtopics.length === 0 && result.publishers.length === 0) {
      console.log('(No subtopics or publishers found)')
    }
    
    dht.destroy()
    process.exit(0)
  }).catch(err => {
    console.error('Error:', err.message)
    process.exit(1)
  })
}

if (command === 'channel-subscribe') {
  const topicPath = argv._[1]
  if (!topicPath) {
    console.error('Please provide topic path (e.g., mp3/electronic)')
    process.exit(1)
  }
  
  const filters = parseFilters(argv.filters)
  
  initChannel().then(async (ch) => {
    console.log(`Subscribing to: ${topicPath}`)
    if (Object.keys(filters).length > 0) {
      console.log('Filters:', JSON.stringify(filters))
    }
    
    subscriptionStore.addSubscription(topicPath, { filters })
    
    const sub = ch.subscribe(topicPath, filters)
    
    sub.on('content', (manifest) => {
      console.log('\n>>> NEW CONTENT <<<')
      console.log(`From: ${manifest.publicKey.slice(0, 16)}...`)
      console.log(`Timestamp: ${new Date(manifest.timestamp).toISOString()}`)
      console.log(`Collections: ${manifest.collections.length}`)
      manifest.collections.forEach((col, i) => {
        console.log(`  [${i}] ${col.title}: ${col.items.length} items`)
      })
      
      subscriptionStore.updateLastSeq(topicPath, manifest.sequence)
    })
    
    sub.on('error', (err) => {
      console.error('Subscription error:', err.message)
    })
    
    console.log('Listening for updates... (Ctrl+C to stop)')
    
    process.on('SIGINT', () => {
      console.log('\nUnsubscribing...')
      sub.stop()
      subscriptionStore.destroy()
      dht.destroy()
      process.exit(0)
    })
  }).catch(err => {
    console.error('Error:', err.message)
    process.exit(1)
  })
}

if (command === 'channel-unsubscribe') {
  const topicPath = argv._[1]
  if (!topicPath) {
    console.error('Please provide topic path')
    process.exit(1)
  }
  
  subscriptionStore = new SubscriptionStore(argv.subscriptions)
  subscriptionStore.load()
  
  if (subscriptionStore.removeSubscription(topicPath)) {
    console.log(`Unsubscribed from: ${topicPath}`)
  } else {
    console.log(`Not subscribed to: ${topicPath}`)
  }
  
  subscriptionStore.destroy()
  process.exit(0)
}

if (command === 'channel-publish') {
  const topicPath = argv._[1]
  if (!topicPath) {
    console.error('Please provide topic path')
    process.exit(1)
  }
  
  if (!argv.input) {
    console.error('Please specify manifest with -i')
    process.exit(1)
  }
  
  if (!fs.existsSync(argv.keyfile)) {
    console.error('Keyfile not found. Run gen-key first.')
    process.exit(1)
  }
  
  const manifestData = JSON.parse(fs.readFileSync(argv.input, 'utf-8'))
  
  initChannel().then(async (ch) => {
    console.log(`Publishing to topic: ${topicPath}`)
    
    await ch.publish(topicPath, manifestData.collections, {
      metadata: manifestData.metadata
    })
    
    console.log('Published successfully!')
    console.log('(Will auto-republish every 45 minutes while running)')
    console.log('Press Ctrl+C to stop publishing')
    
    process.on('SIGINT', () => {
      console.log('\nStopping...')
      dht.destroy()
      process.exit(0)
    })
  }).catch(err => {
    console.error('Error:', err.message)
    process.exit(1)
  })
}

if (command === 'channel-list') {
  subscriptionStore = new SubscriptionStore(argv.subscriptions)
  subscriptionStore.load()
  
  const subs = subscriptionStore.getAllSubscriptions()
  
  if (subs.length === 0) {
    console.log('No subscriptions.')
  } else {
    console.log(`${subs.length} subscription(s):\n`)
    subs.forEach(sub => {
      const status = sub.enabled ? '✓' : '✗'
      const lastPoll = sub.lastPollTime 
        ? new Date(sub.lastPollTime).toLocaleString() 
        : 'never'
      console.log(`[${status}] ${sub.topicPath}`)
      console.log(`    Added: ${new Date(sub.addedAt).toLocaleString()}`)
      console.log(`    Last poll: ${lastPoll}`)
      if (Object.keys(sub.filters).length > 0) {
        console.log(`    Filters: ${JSON.stringify(sub.filters)}`)
      }
      console.log('')
    })
  }
  
  process.exit(0)
}

let blobStore = null
let blobNetwork = null
let blobTracker = null
let blobClient = null

async function initBlobNetwork () {
  if (blobClient) return blobClient
  
  blobStore = new BlobStore(argv.dir)
  
  if (!dht) {
    dht = new DHT()
    await new Promise(resolve => dht.on('ready', resolve))
  }
  
  blobTracker = new BlobTracker(dht, { port: 6881 })
  blobNetwork = new BlobNetwork(blobStore, { port: 0 })
  
  await blobNetwork.listen()
  console.log(`Blob network listening on port ${blobNetwork.port}`)
  
  blobClient = createBlobClient(blobStore, blobNetwork, blobTracker)
  return blobClient
}

if (command === 'blob-seed') {
  if (!argv.input) {
    console.error('Please specify file entry with -i')
    process.exit(1)
  }
  
  const fileEntry = JSON.parse(fs.readFileSync(argv.input, 'utf-8'))
  
  initBlobNetwork().then(async (client) => {
    const blobIds = fileEntry.chunks.map(c => c.blobId)
    let found = 0
    
    for (const blobId of blobIds) {
      if (blobStore.has(blobId)) {
        found++
      } else {
        const legacyPath = path.join(argv.dir, blobId)
        if (fs.existsSync(legacyPath)) {
          const data = fs.readFileSync(legacyPath)
          blobStore.put(blobId, data)
          found++
        }
      }
    }
    
    if (found < blobIds.length) {
      console.error(`Missing ${blobIds.length - found} of ${blobIds.length} blobs`)
      console.error('Run ingest first to create blobs locally')
      process.exit(1)
    }
    
    await client.seed(fileEntry)
    
    console.log(`Seeding ${blobIds.length} blobs for: ${fileEntry.name}`)
    console.log('Press Ctrl+C to stop')
    
    blobNetwork.on('upload', ({ blobId, size, peer }) => {
      console.log(`Uploaded ${blobId.slice(0, 16)}... (${size} bytes) to ${peer}`)
    })
    
    blobNetwork.on('peer', ({ address }) => {
      console.log(`Peer connected: ${address}`)
    })
    
    process.on('SIGINT', () => {
      console.log('\nStopping...')
      blobNetwork.destroy()
      blobTracker.destroy()
      if (dht) dht.destroy()
      process.exit(0)
    })
  }).catch(err => {
    console.error('Error:', err.message)
    process.exit(1)
  })
}

if (command === 'blob-fetch') {
  if (!argv.input) {
    console.error('Please specify file entry with -i')
    process.exit(1)
  }
  
  if (!argv.output) {
    console.error('Please specify output file with -o')
    process.exit(1)
  }
  
  const fileEntry = JSON.parse(fs.readFileSync(argv.input, 'utf-8'))
  
  initBlobNetwork().then(async (client) => {
    console.log(`Fetching: ${fileEntry.name}`)
    console.log(`Chunks: ${fileEntry.chunks.length}`)
    
    const fileBuf = await client.fetch(fileEntry, {
      onProgress: ({ completed, total, blobId }) => {
        const pct = Math.round(completed / total * 100)
        console.log(`[${pct}%] Downloaded ${blobId.slice(0, 16)}...`)
      }
    })
    
    fs.writeFileSync(argv.output, fileBuf)
    console.log(`Saved to: ${argv.output}`)
    
    blobNetwork.destroy()
    blobTracker.destroy()
    if (dht) dht.destroy()
    process.exit(0)
  }).catch(err => {
    console.error('Error:', err.message)
    process.exit(1)
  })
}

if (command === 'blob-status') {
  blobStore = new BlobStore(argv.dir)
  const stats = blobStore.stats()
  
  console.log('Blob Store Status')
  console.log('---')
  console.log(`Blobs: ${stats.blobCount}`)
  console.log(`Size: ${(stats.currentSize / 1024 / 1024).toFixed(2)} MB`)
  console.log(`Max: ${(stats.maxSize / 1024 / 1024 / 1024).toFixed(2)} GB`)
  console.log(`Utilization: ${(stats.utilization * 100).toFixed(1)}%`)
  
  const blobs = blobStore.list()
  if (blobs.length > 0) {
    console.log('\nRecent blobs:')
    blobs.slice(0, 10).forEach(b => {
      const age = Math.round((Date.now() - b.addedAt) / 1000 / 60)
      console.log(`  ${b.blobId.slice(0, 32)}... (${b.size} bytes, ${age}m ago)`)
    })
    if (blobs.length > 10) {
      console.log(`  ... and ${blobs.length - 10} more`)
    }
  }
  
  process.exit(0)
}
