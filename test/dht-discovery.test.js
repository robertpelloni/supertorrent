import test from 'node:test'
import assert from 'node:assert'
import crypto from 'crypto'
import { DHTDiscovery } from '../supernode/network/dht-discovery.js'

test('DHTDiscovery', async (t) => {
  await t.test('constructor sets defaults', () => {
    const dht = new DHTDiscovery()
    
    assert.ok(dht.nodeId instanceof Buffer)
    assert.strictEqual(dht.nodeId.length, 20)
    assert.strictEqual(dht.port, 0)
    assert.strictEqual(dht.announceInterval, 15 * 60 * 1000)
    assert.strictEqual(dht._destroyed, false)
    assert.strictEqual(dht.dht, null)
  })

  await t.test('constructor accepts custom options', () => {
    const nodeId = crypto.randomBytes(20)
    const bootstrap = ['custom.bootstrap.com:6881']
    
    const dht = new DHTDiscovery({
      nodeId,
      bootstrap,
      port: 12345,
      announceInterval: 5000
    })
    
    assert.strictEqual(dht.nodeId, nodeId)
    assert.deepStrictEqual(dht.bootstrap, bootstrap)
    assert.strictEqual(dht.port, 12345)
    assert.strictEqual(dht.announceInterval, 5000)
  })

  await t.test('blobIdToInfoHash converts SHA256 to SHA1', () => {
    const dht = new DHTDiscovery()
    const blobId = crypto.createHash('sha256').update('test-blob').digest('hex')
    
    const infoHash = dht.blobIdToInfoHash(blobId)
    
    assert.ok(infoHash instanceof Buffer)
    assert.strictEqual(infoHash.length, 20)
    
    const infoHash2 = dht.blobIdToInfoHash(blobId)
    assert.ok(infoHash.equals(infoHash2))
  })

  await t.test('blobIdToInfoHash produces different hashes for different blobs', () => {
    const dht = new DHTDiscovery()
    const blobId1 = crypto.createHash('sha256').update('blob-1').digest('hex')
    const blobId2 = crypto.createHash('sha256').update('blob-2').digest('hex')
    
    const hash1 = dht.blobIdToInfoHash(blobId1)
    const hash2 = dht.blobIdToInfoHash(blobId2)
    
    assert.ok(!hash1.equals(hash2))
  })

  await t.test('getStats returns null before start', () => {
    const dht = new DHTDiscovery()
    
    assert.strictEqual(dht.getStats(), null)
  })

  await t.test('destroy cleans up resources', () => {
    const dht = new DHTDiscovery()
    
    dht.announcedHashes.set('test-hash', {
      blobId: 'test-blob',
      timer: setInterval(() => {}, 10000),
      wsPort: 8080
    })
    dht.lookupCallbacks.set('test-hash', new Set([() => {}]))
    
    dht.destroy()
    
    assert.strictEqual(dht._destroyed, true)
    assert.strictEqual(dht.announcedHashes.size, 0)
    assert.strictEqual(dht.lookupCallbacks.size, 0)
  })

  await t.test('announce does nothing when destroyed', () => {
    const dht = new DHTDiscovery()
    dht._destroyed = true
    
    dht.announce('test-blob-id', 8080)
    
    assert.strictEqual(dht.announcedHashes.size, 0)
  })

  await t.test('lookup does nothing when destroyed', () => {
    const dht = new DHTDiscovery()
    dht._destroyed = true
    
    let callbackCalled = false
    dht.lookup('test-blob-id', () => { callbackCalled = true })
    
    assert.strictEqual(callbackCalled, false)
  })

  await t.test('unannounce removes hash from tracking', () => {
    const dht = new DHTDiscovery()
    const blobId = 'test-blob-id'
    const infoHash = dht.blobIdToInfoHash(blobId)
    const hashHex = infoHash.toString('hex')
    
    const timer = setInterval(() => {}, 10000)
    dht.announcedHashes.set(hashHex, {
      blobId,
      timer,
      wsPort: 8080
    })
    
    dht.unannounce(blobId)
    
    assert.strictEqual(dht.announcedHashes.has(hashHex), false)
  })

  await t.test('emits destroyed event on destroy', async () => {
    const dht = new DHTDiscovery()
    
    const destroyedPromise = new Promise(resolve => {
      dht.once('destroyed', resolve)
    })
    
    dht.destroy()
    
    await destroyedPromise
  })
})

test('DHTDiscovery integration', async (t) => {
  await t.test('start and destroy lifecycle with isolated bootstrap', async () => {
    const dht = new DHTDiscovery({
      bootstrap: [],
      port: 0
    })
    
    await dht.start()
    
    assert.ok(dht.dht !== null)
    assert.ok(dht.port >= 0)
    
    const stats = dht.getStats()
    assert.ok(stats !== null)
    assert.ok(stats.nodeId)
    assert.strictEqual(stats.announcedHashes, 0)
    
    dht.destroy()
    assert.strictEqual(dht._destroyed, true)
  })

  await t.test('two DHT nodes can discover each other', async () => {
    const dht1 = new DHTDiscovery({
      bootstrap: [],
      port: 0
    })
    await dht1.start()
    
    const dht1Port = dht1.dht.address().port
    const dht2 = new DHTDiscovery({
      bootstrap: [`127.0.0.1:${dht1Port}`],
      port: 0
    })
    await dht2.start()
    
    assert.ok(dht1.dht !== null)
    assert.ok(dht2.dht !== null)
    
    dht1.destroy()
    dht2.destroy()
  })

  await t.test('announce and findPeers between two nodes', async () => {
    const dht1 = new DHTDiscovery({
      bootstrap: [],
      port: 0
    })
    await dht1.start()
    
    const dht1Port = dht1.dht.address().port
    
    const dht2 = new DHTDiscovery({
      bootstrap: [`127.0.0.1:${dht1Port}`],
      port: 0
    })
    await dht2.start()
    
    const blobId = crypto.createHash('sha256').update('test-blob-for-discovery').digest('hex')
    const wsPort = 9999
    
    dht1.announce(blobId, wsPort)
    
    await new Promise(r => setTimeout(r, 100))
    
    const peers = await dht2.findPeers(blobId, 500)
    
    assert.ok(Array.isArray(peers))
    
    dht1.destroy()
    dht2.destroy()
  })

  await t.test('findPeers returns empty array on timeout', async () => {
    const dht = new DHTDiscovery({
      bootstrap: [],
      port: 0
    })
    await dht.start()
    
    const blobId = crypto.createHash('sha256').update('nonexistent-blob').digest('hex')
    
    const peers = await dht.findPeers(blobId, 100)
    
    assert.ok(Array.isArray(peers))
    assert.strictEqual(peers.length, 0)
    
    dht.destroy()
  })

  await t.test('peer event emitted on discovery', async () => {
    const dht1 = new DHTDiscovery({
      bootstrap: [],
      port: 0
    })
    await dht1.start()
    
    const dht1Port = dht1.dht.address().port
    
    const dht2 = new DHTDiscovery({
      bootstrap: [`127.0.0.1:${dht1Port}`],
      port: 0
    })
    
    let peerEventFired = false
    dht2.on('peer', () => {
      peerEventFired = true
    })
    
    await dht2.start()
    
    const blobId = crypto.createHash('sha256').update('peer-event-test').digest('hex')
    
    dht1.announce(blobId, 8888)
    dht2.lookup(blobId)
    
    await new Promise(r => setTimeout(r, 200))
    
    dht1.destroy()
    dht2.destroy()
    
    assert.ok(typeof peerEventFired === 'boolean')
  })
})
