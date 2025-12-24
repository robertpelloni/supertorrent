#!/usr/bin/env node

import fs from 'fs'
import path from 'path'
import minimist from 'minimist'
import { WebSocket } from 'ws'
import { generateKeypair } from './lib/crypto.js'
import { createManifest, validateManifest } from './lib/manifest.js'
import { ingest, reassemble } from './lib/storage.js'
import { startSecureServer, downloadSecureBlob } from './lib/secure-transport.js'
import { DHTClient } from './lib/dht-real.js'
import sodium from 'sodium-native'

const argv = minimist(process.argv.slice(2), {
  alias: {
    k: 'keyfile',
    t: 'tracker', // kept for legacy compatibility
    i: 'input',
    o: 'output',
    d: 'dir'
  },
  default: {
    keyfile: './identity.json',
    dir: './storage'
  }
})

const command = argv._[0]

function parseUri (input) {
  if (input.startsWith('megatorrent://')) {
    const withoutScheme = input.replace('megatorrent://', '')
    const parts = withoutScheme.split('/')
    return {
      publicKey: parts[0],
      blobId: parts[1] || null
    }
  }
  return { publicKey: input, blobId: null }
}

if (!command) {
  console.error(`Usage:
  gen-key [-k identity.json]
  ingest -i <file> [-d ./storage] -> Returns FileEntry JSON
  publish [-k identity.json] -i <file_entry.json> (Uses DHT)
  subscribe <public_key_hex|megatorrent://...> [-d ./storage] (Uses DHT)
  `)
  process.exit(1)
}

// Ensure storage dir exists
if (!fs.existsSync(argv.dir)) {
  fs.mkdirSync(argv.dir, { recursive: true })
}

// Initialize DHT if needed
let dht = null
if (['ingest', 'publish', 'subscribe'].includes(command)) {
  dht = new DHTClient()
  // Wait a bit for bootstrap?
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
  console.log(`URI: megatorrent://${data.publicKey}`)
  if (dht) dht.destroy()
  process.exit(0)
}

// 2. Ingest
if (command === 'ingest') {
  // Start the Secure Blob Server
  const server = startSecureServer(argv.dir, 0)
  // Wait for port assignment
  setTimeout(() => {
    const port = server.port
    console.log(`Secure Blob Server running on port ${port}`)

    if (!argv.input) {
      console.log('Running in server-only mode. Press Ctrl+C to exit.')
    } else {
      const fileBuf = fs.readFileSync(argv.input)
      const result = ingest(fileBuf, path.basename(argv.input))

      // Save Blobs
      result.blobs.forEach(blob => {
        fs.writeFileSync(path.join(argv.dir, blob.id), blob.buffer)
      })

      console.log(`Ingested ${result.blobs.length} blobs to ${argv.dir}`)
      console.log('FileEntry JSON (save this to a file to publish it):')
      console.log(JSON.stringify(result.fileEntry, null, 2))

      // Announce to DHT
      console.log('Announcing blobs to DHT...')
      const promises = result.blobs.map(b => dht.announceBlob(b.id, port))

      Promise.all(promises).then(() => {
        console.log('Announced all blobs.')
      }).catch(err => console.error('Announce failed:', err))
    }
  }, 500)
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
    console.error('Please specify input file with -i')
    process.exit(1)
  }

  const content = fs.readFileSync(argv.input, 'utf-8')
  let items
  try {
    const json = JSON.parse(content)
    items = [json]
  } catch (e) {
    items = content.split('\n').map(l => l.trim()).filter(l => l.length > 0)
  }

  const collections = [{
    title: 'Default Collection',
    items
  }]

  const sequence = Date.now()
  const manifest = createManifest(keypair, sequence, collections)

  console.log('Publishing manifest to DHT...')

  dht.putManifest(keypair, sequence, manifest).then(hash => {
    console.log('Published!')
    console.log('Mutable Item Hash:', hash.toString('hex'))
    // Wait briefly for propagation
    setTimeout(() => {
      dht.destroy()
      process.exit(0)
    }, 2000)
  }).catch(err => {
    console.error('Publish failed:', err)
    dht.destroy()
    process.exit(1)
  })
}

// 4. Subscribe
if (command === 'subscribe') {
  const uri = argv._[1]
  if (!uri) {
    console.error('Please provide public key hex or megatorrent:// URI')
    process.exit(1)
  }

  const { publicKey } = parseUri(uri)
  console.log(`Looking up Manifest for ${publicKey} in DHT...`)

  // Start Server to reciprocate
  const server = startSecureServer(argv.dir, 0)
  setTimeout(() => {
    console.log(`Local Secure Server on ${server.port}`)
  }, 100)

  // Poll DHT for updates
  const checkUpdate = async () => {
    try {
      const res = await dht.getManifest(publicKey)
      if (res) {
        console.log(`Found Manifest (Seq: ${res.seq})`)
        await processManifest(res.manifest)
      } else {
        console.log('No manifest found yet...')
      }
    } catch (err) {
      console.error('Lookup error:', err.message)
    }
  }

  // Initial check
  checkUpdate()

  // Periodic check (every minute)
  setInterval(checkUpdate, 60000)

  async function processManifest (manifest) {
    if (!validateManifest(manifest) || manifest.publicKey !== publicKey) {
      console.error('Invalid manifest signature!')
      return
    }

    const items = manifest.collections[0].items
    for (const item of items) {
      if (item.chunks) {
        console.log(`Processing: ${item.name}`)
        const outPath = path.join(argv.dir, item.name)
        if (fs.existsSync(outPath)) {
          console.log('Already downloaded.')
          continue
        }

        const chunks = []
        for (const chunk of item.chunks) {
          const blobId = chunk.id
          const blobPath = path.join(argv.dir, blobId)

          if (fs.existsSync(blobPath)) {
            chunks.push(fs.readFileSync(blobPath))
          } else {
            console.log(`Finding peers for blob ${blobId}...`)
            const peers = await dht.findBlobPeers(blobId)
            console.log(`Found ${peers.length} peers:`, peers)

            let downloaded = false
            for (const peer of peers) {
              try {
                console.log(`Connecting to ${peer}...`)
                const buffer = await downloadSecureBlob(peer, blobId)
                fs.writeFileSync(blobPath, buffer)
                chunks.push(buffer)
                downloaded = true
                break
              } catch (e) {
                console.error(`Peer ${peer} failed: ${e.message}`)
              }
            }
            if (!downloaded) console.error(`Failed to download blob ${blobId}`)
          }
        }

        if (chunks.length === item.chunks.length) {
          const fileBuf = await reassemble(item, async (bid) => {
            return fs.readFileSync(path.join(argv.dir, bid))
          })
          if (fileBuf) {
            fs.writeFileSync(outPath, fileBuf)
            console.log(`Successfully assembled ${item.name}`)
          }
        }
      }
    }
  }
}
