import { test, describe, before, after } from 'node:test'
import assert from 'node:assert'
import crypto from 'crypto'
import fs from 'fs'
import path from 'path'
import os from 'os'
import { BobcoinBridge, SupernodeWithBobcoin } from '../supernode/blockchain/index.js'
import { SupernodeNetwork } from '../supernode/network/index.js'

describe('BobcoinBridge', () => {
  test('connect and disconnect', async () => {
    const bridge = new BobcoinBridge()
    
    const events = []
    bridge.on('connected', (info) => events.push({ type: 'connected', ...info }))
    bridge.on('disconnected', () => events.push({ type: 'disconnected' }))
    
    await bridge.connect()
    assert.strictEqual(bridge.connected, true)
    
    await bridge.disconnect()
    assert.strictEqual(bridge.connected, false)
    
    assert.strictEqual(events.length, 2)
    assert.strictEqual(events[0].type, 'connected')
    assert.strictEqual(events[1].type, 'disconnected')
  })

  test('register storage provider', async () => {
    const bridge = new BobcoinBridge()
    await bridge.connect()
    
    const result = await bridge.registerStorageProvider(1024 * 1024 * 1024 * 100, 0.01)
    
    assert.ok(result.providerId)
    assert.ok(result.txHash.startsWith('0x'))
  })

  test('create storage deal', async () => {
    const bridge = new BobcoinBridge()
    await bridge.connect()
    
    const result = await bridge.createStorageDeal({
      fileId: 'sha256:abc123',
      size: 1024 * 1024 * 100,
      duration: 24 * 3600000,
      redundancy: 3
    })
    
    assert.ok(result.dealId)
    assert.ok(result.txHash.startsWith('0x'))
    assert.ok(result.expiresAt > Date.now())
  })

  test('submit and verify storage proof', async () => {
    const bridge = new BobcoinBridge()
    await bridge.connect()
    
    const proofResult = await bridge.submitStorageProof(
      'deal123',
      ['hash1', 'hash2', 'hash3'],
      'merkleRoot123'
    )
    
    assert.ok(proofResult.proofId)
    
    const verifyResult = await bridge.verifyStorageProof(proofResult.proofId)
    assert.strictEqual(verifyResult.isValid, true)
  })

  test('get balance', async () => {
    const bridge = new BobcoinBridge()
    await bridge.connect()
    
    const balance = await bridge.getBalance()
    
    assert.ok(typeof balance.bob === 'number')
    assert.ok(typeof balance.staked === 'number')
    assert.ok(typeof balance.pending === 'number')
  })

  test('throws when not connected', async () => {
    const bridge = new BobcoinBridge()
    
    await assert.rejects(
      () => bridge.getBalance(),
      /Not connected to blockchain/
    )
  })
})

describe('SupernodeWithBobcoin', () => {
  const tempDirs = []
  const instances = []
  
  function createTempDir () {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bobcoin-test-'))
    tempDirs.push(dir)
    return dir
  }
  
  function cleanup () {
    for (const instance of instances) {
      try { instance.destroy() } catch {}
    }
    instances.length = 0
    
    for (const dir of tempDirs) {
      try { fs.rmSync(dir, { recursive: true, force: true }) } catch {}
    }
    tempDirs.length = 0
  }
  
  after(() => cleanup())

  test('ingest file with storage deal', async () => {
    cleanup()
    const masterKey = crypto.randomBytes(32)
    
    const network = new SupernodeNetwork({
      storageDir: createTempDir(),
      masterKey
    })
    
    const supernode = new SupernodeWithBobcoin(network, {
      proofIntervalMs: 1000000
    })
    instances.push(supernode)
    
    await supernode.connectBlockchain()
    
    const events = []
    supernode.on('deal-created', (info) => events.push({ type: 'deal-created', ...info }))
    
    const testData = crypto.randomBytes(2048)
    const result = await supernode.ingestWithDeal(testData, 'paid-file.bin', masterKey, {
      duration: 7 * 24 * 3600000,
      redundancy: 5
    })
    
    assert.ok(result.fileId)
    assert.ok(result.deal)
    assert.ok(result.deal.dealId)
    
    const dealEvent = events.find(e => e.type === 'deal-created')
    assert.ok(dealEvent)
    assert.strictEqual(dealEvent.redundancy, 5)
  })

  test('get combined stats', async () => {
    cleanup()
    const masterKey = crypto.randomBytes(32)
    
    const network = new SupernodeNetwork({
      storageDir: createTempDir(),
      masterKey
    })
    
    const supernode = new SupernodeWithBobcoin(network, {
      proofIntervalMs: 1000000
    })
    instances.push(supernode)
    
    await supernode.connectBlockchain()
    
    const testData = crypto.randomBytes(1024)
    await supernode.ingestWithDeal(testData, 'stats-test.bin', masterKey)
    
    const stats = await supernode.getStats()
    
    assert.ok(stats.blobCount >= 1)
    assert.ok(stats.blockchain)
    assert.strictEqual(stats.blockchain.connected, true)
    assert.strictEqual(stats.blockchain.activeDeals, 1)
  })
})
