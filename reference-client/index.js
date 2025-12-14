#!/usr/bin/env node

import fs from 'fs'
import path from 'path'
import minimist from 'minimist'
import Client from 'bittorrent-tracker'
import { generateKeypair } from './lib/crypto.js'
import { createManifest, validateManifest } from './lib/manifest.js'

const argv = minimist(process.argv.slice(2), {
  alias: {
    k: 'keyfile',
    t: 'tracker',
    i: 'input',
    o: 'output'
  },
  default: {
    keyfile: './identity.json',
    tracker: 'ws://localhost:8000' // Default to local WS tracker
  }
})

const command = argv._[0]

if (!command) {
  console.error(`Usage:
  gen-key [-k identity.json]
  publish [-k identity.json] [-t ws://tracker] -i magnet_list.txt
  subscribe [-t ws://tracker] <public_key_hex>
  `)
  process.exit(1)
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

// 2. Publish
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

  // Read magnet links
  if (!argv.input) {
    console.error('Please specify input file with -i (list of magnet links)')
    process.exit(1)
  }
  const content = fs.readFileSync(argv.input, 'utf-8')
  const lines = content.split('\n').map(l => l.trim()).filter(l => l.length > 0)

  // Create Collections structure (flat for now)
  const collections = [{
    title: 'Default Collection',
    items: lines
  }]

  // Create Manifest
  // TODO: Retrieve last sequence from somewhere or store it?
  // For now, we use timestamp as sequence to be simple and monotonic
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

// 3. Subscribe
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
              console.log('Items:', data.manifest.collections[0].items)
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
