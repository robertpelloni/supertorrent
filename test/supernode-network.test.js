import { test, describe, before, after } from 'node:test'
import assert from 'node:assert'
import crypto from 'crypto'
import fs from 'fs'
import path from 'path'
import os from 'os'
import { SupernodeNetwork } from '../supernode/network/index.js'

describe('SupernodeNetwork', () => {
  const tempDirs = []
  const networks = []
  
  function createTempDir () {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'supernode-test-'))
    tempDirs.push(dir)
    return dir
  }
  
  function cleanup () {
    for (const network of networks) {
      try { network.destroy() } catch {}
    }
    networks.length = 0
    
    for (const dir of tempDirs) {
      try { fs.rmSync(dir, { recursive: true, force: true }) } catch {}
    }
    tempDirs.length = 0
  }
  
  after(() => cleanup())

  test('ingest and retrieve file locally', async () => {
    cleanup()
    const masterKey = crypto.randomBytes(32)
    const network = new SupernodeNetwork({
      storageDir: createTempDir(),
      masterKey
    })
    networks.push(network)
    
    const testData = crypto.randomBytes(1024)
    const result = await network.ingestFile(testData, 'test.bin')
    
    assert.ok(result.fileId.startsWith('sha256:'))
    assert.strictEqual(result.chunkHashes.length, 1)
    
    const retrieved = await network.retrieveFile(result.fileId)
    assert.strictEqual(retrieved.fileName, 'test.bin')
    assert.deepStrictEqual(retrieved.data, testData)
  })

  test('two nodes can share files', async (t) => {
    cleanup()
    const masterKey = crypto.randomBytes(32)
    
    const node1 = new SupernodeNetwork({
      storageDir: createTempDir(),
      masterKey
    })
    networks.push(node1)
    
    const node2 = new SupernodeNetwork({
      storageDir: createTempDir(),
      masterKey
    })
    networks.push(node2)
    
    const port1 = await node1.listen()
    await node2.listen()
    
    await node2.connect(`ws://127.0.0.1:${port1}`)
    
    await new Promise(r => setTimeout(r, 200))
    
    const testData = crypto.randomBytes(2048)
    const result = await node1.ingestFile(testData, 'shared.bin')
    
    await new Promise(r => setTimeout(r, 200))
    
    const { encryptedManifest } = await node1.shareManifest(result.fileId)
    
    const retrieved = await node2.fetchFile(result.fileId, encryptedManifest)
    assert.deepStrictEqual(retrieved.data, testData)
  })

  test('stats returns correct info', async () => {
    cleanup()
    const masterKey = crypto.randomBytes(32)
    const network = new SupernodeNetwork({
      storageDir: createTempDir(),
      masterKey
    })
    networks.push(network)
    
    const testData = crypto.randomBytes(512)
    await network.ingestFile(testData, 'stats-test.bin')
    
    const stats = network.stats()
    assert.strictEqual(stats.blobCount, 1)
    assert.strictEqual(stats.manifestCount, 1)
    assert.ok(stats.currentSize > 0)
  })

  test('emits events during operations', async () => {
    cleanup()
    const masterKey = crypto.randomBytes(32)
    const network = new SupernodeNetwork({
      storageDir: createTempDir(),
      masterKey
    })
    networks.push(network)
    
    const events = []
    network.on('chunk-ingested', (info) => events.push({ type: 'chunk-ingested', ...info }))
    network.on('file-ingested', (info) => events.push({ type: 'file-ingested', ...info }))
    
    const testData = crypto.randomBytes(1024)
    await network.ingestFile(testData, 'events.bin')
    
    const chunkEvent = events.find(e => e.type === 'chunk-ingested')
    const fileEvent = events.find(e => e.type === 'file-ingested')
    
    assert.ok(chunkEvent)
    assert.ok(fileEvent)
    assert.strictEqual(fileEvent.fileName, 'events.bin')
  })

  test('DHT can be disabled', async () => {
    cleanup()
    const masterKey = crypto.randomBytes(32)
    const network = new SupernodeNetwork({
      storageDir: createTempDir(),
      masterKey,
      enableDHT: false
    })
    networks.push(network)
    
    assert.strictEqual(network.dht, null)
    
    const stats = network.stats()
    assert.strictEqual(stats.dht, undefined)
  })

  test('DHT enabled by default and starts on listen', async () => {
    cleanup()
    const masterKey = crypto.randomBytes(32)
    const network = new SupernodeNetwork({
      storageDir: createTempDir(),
      masterKey,
      dht: { bootstrap: [] }
    })
    networks.push(network)
    
    assert.ok(network.dht !== null)
    
    await network.listen()
    
    const stats = network.stats()
    assert.ok(stats.dht !== null)
    assert.ok(stats.dht.nodeId)
  })

  test('DHT announces chunks on ingest', async () => {
    cleanup()
    const masterKey = crypto.randomBytes(32)
    const network = new SupernodeNetwork({
      storageDir: createTempDir(),
      masterKey,
      dht: { bootstrap: [] }
    })
    networks.push(network)
    
    await network.listen()
    
    const testData = crypto.randomBytes(1024)
    const result = await network.ingestFile(testData, 'dht-test.bin')
    
    const stats = network.stats()
    assert.ok(stats.dht)
    assert.ok(stats.dht.announcedHashes >= 1)
    assert.ok(result.chunkHashes.length > 0)
  })

  test('stats includes DHT info when enabled', async () => {
    cleanup()
    const masterKey = crypto.randomBytes(32)
    const network = new SupernodeNetwork({
      storageDir: createTempDir(),
      masterKey,
      dht: { bootstrap: [] }
    })
    networks.push(network)
    
    await network.listen()
    
    const testData = crypto.randomBytes(512)
    await network.ingestFile(testData, 'dht-stats.bin')
    
    await new Promise(r => setTimeout(r, 100))
    
    const stats = network.stats()
    assert.ok(stats.dht)
    assert.ok(stats.dht.nodeId)
    assert.ok(stats.dht.announcedHashes >= 1)
  })
})
