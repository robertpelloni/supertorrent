import test from 'node:test'
import assert from 'node:assert'
import crypto from 'crypto'
import fs from 'fs'
import path from 'path'
import os from 'os'
import { BlobStore } from '../reference-client/lib/blob-store.js'
import { SupernodeStorage } from '../supernode/storage/index.js'
import { generateEncryptionKey } from '../supernode/storage/mux/index.js'

function createTempDir () {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'supernode-test-'))
}

function cleanup (dir) {
  fs.rmSync(dir, { recursive: true, force: true })
}

test('SupernodeStorage ingest and retrieve small file', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore)
    const masterKey = generateEncryptionKey()
    
    const originalData = Buffer.from('Hello, Supernode! This is a test file.')
    const fileName = 'test.txt'
    
    const { fileId, chunkHashes } = await storage.ingest(originalData, fileName, masterKey)
    
    assert.ok(fileId.startsWith('sha256:'), 'File ID should be sha256 hash')
    assert.strictEqual(chunkHashes.length, 1, 'Small file should have 1 chunk')
    
    const { data, fileName: recoveredName } = await storage.retrieve(fileId, masterKey)
    
    assert.deepStrictEqual(data, originalData, 'Retrieved data should match original')
    assert.strictEqual(recoveredName, fileName, 'File name should be preserved')
  } finally {
    cleanup(tempDir)
  }
})

test('SupernodeStorage ingest and retrieve large file (multi-chunk)', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore)
    const masterKey = generateEncryptionKey()
    
    const originalData = crypto.randomBytes(2.5 * 1024 * 1024)
    const fileName = 'large-file.bin'
    
    const { fileId, chunkHashes } = await storage.ingest(originalData, fileName, masterKey)
    
    assert.strictEqual(chunkHashes.length, 3, 'Should have 3 chunks for 2.5MB file')
    
    const { data } = await storage.retrieve(fileId, masterKey)
    
    assert.deepStrictEqual(data, originalData, 'Large file should roundtrip correctly')
  } finally {
    cleanup(tempDir)
  }
})

test('SupernodeStorage chunks are stored in blob store', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore)
    const masterKey = generateEncryptionKey()
    
    const originalData = Buffer.from('Test data for blob store verification')
    
    const { chunkHashes } = await storage.ingest(originalData, 'test.txt', masterKey)
    
    for (const hash of chunkHashes) {
      assert.ok(blobStore.has(hash), `Blob store should have chunk ${hash}`)
      const data = blobStore.get(hash)
      assert.ok(data, 'Chunk data should be retrievable')
      assert.ok(data.length > 0, 'Chunk should have content')
    }
  } finally {
    cleanup(tempDir)
  }
})

test('SupernodeStorage wrong master key fails retrieval', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore)
    const masterKey = generateEncryptionKey()
    const wrongKey = generateEncryptionKey()
    
    const originalData = Buffer.from('Secret data')
    const { fileId } = await storage.ingest(originalData, 'secret.txt', masterKey)
    
    await assert.rejects(async () => {
      await storage.retrieve(fileId, wrongKey)
    }, 'Wrong master key should fail retrieval')
  } finally {
    cleanup(tempDir)
  }
})

test('SupernodeStorage emits events during ingest', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore)
    const masterKey = generateEncryptionKey()
    
    const events = []
    storage.on('chunk-ingested', (e) => events.push({ type: 'chunk', ...e }))
    storage.on('file-ingested', (e) => events.push({ type: 'file', ...e }))
    
    const originalData = crypto.randomBytes(1.5 * 1024 * 1024)
    await storage.ingest(originalData, 'events-test.bin', masterKey)
    
    const chunkEvents = events.filter(e => e.type === 'chunk')
    const fileEvents = events.filter(e => e.type === 'file')
    
    assert.strictEqual(chunkEvents.length, 2, 'Should emit 2 chunk events')
    assert.strictEqual(fileEvents.length, 1, 'Should emit 1 file event')
    assert.strictEqual(fileEvents[0].chunks, 2, 'File event should report 2 chunks')
  } finally {
    cleanup(tempDir)
  }
})

test('SupernodeStorage stats includes manifest count', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore)
    const masterKey = generateEncryptionKey()
    
    await storage.ingest(Buffer.from('File 1'), 'file1.txt', masterKey)
    await storage.ingest(Buffer.from('File 2'), 'file2.txt', masterKey)
    
    const stats = storage.stats()
    
    assert.strictEqual(stats.manifestCount, 2, 'Should have 2 manifests')
    assert.strictEqual(stats.blobCount, 2, 'Should have 2 blobs')
  } finally {
    cleanup(tempDir)
  }
})

test('SupernodeStorage verifyChunkAsISO basic check', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore)
    const masterKey = generateEncryptionKey()
    
    const originalData = Buffer.from('Test data for ISO verification')
    const { chunkHashes } = await storage.ingest(originalData, 'iso-test.txt', masterKey)
    
    const isValid = storage.verifyChunkAsISO(chunkHashes[0])
    assert.ok(isValid, 'Chunk should pass basic ISO verification')
    
    const nonExistent = storage.verifyChunkAsISO('nonexistent-hash')
    assert.strictEqual(nonExistent, false, 'Non-existent chunk should fail')
  } finally {
    cleanup(tempDir)
  }
})

test('SupernodeStorage with erasure coding enabled', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore, {
      enableErasure: true,
      dataShards: 4,
      parityShards: 2
    })
    const masterKey = generateEncryptionKey()
    
    const originalData = Buffer.from('Erasure coding test data for redundancy verification')
    const fileName = 'erasure-test.txt'
    
    const { fileId, chunkHashes } = await storage.ingest(originalData, fileName, masterKey)
    
    assert.strictEqual(chunkHashes.length, 6, 'Should have 6 shards (4 data + 2 parity)')
    
    const { data, fileName: recoveredName } = await storage.retrieve(fileId, masterKey)
    
    assert.deepStrictEqual(data, originalData, 'Retrieved data should match original')
    assert.strictEqual(recoveredName, fileName, 'File name should be preserved')
  } finally {
    cleanup(tempDir)
  }
})

test('SupernodeStorage erasure coding recovers from missing shards', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore, {
      enableErasure: true,
      dataShards: 4,
      parityShards: 2
    })
    const masterKey = generateEncryptionKey()
    
    const originalData = Buffer.from('Recovery test data - this should survive shard loss')
    const { fileId, chunkHashes } = await storage.ingest(originalData, 'recovery.txt', masterKey)
    
    blobStore.delete(chunkHashes[0])
    blobStore.delete(chunkHashes[1])
    
    const { data } = await storage.retrieve(fileId, masterKey)
    
    assert.deepStrictEqual(data, originalData, 'Should recover data with 2 missing shards')
  } finally {
    cleanup(tempDir)
  }
})

test('SupernodeStorage erasure coding fails with too many missing shards', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore, {
      enableErasure: true,
      dataShards: 4,
      parityShards: 2
    })
    const masterKey = generateEncryptionKey()
    
    const originalData = Buffer.from('This will fail to recover')
    const { fileId, chunkHashes } = await storage.ingest(originalData, 'fail.txt', masterKey)
    
    blobStore.delete(chunkHashes[0])
    blobStore.delete(chunkHashes[1])
    blobStore.delete(chunkHashes[2])
    
    await assert.rejects(
      async () => storage.retrieve(fileId, masterKey),
      /Not enough shards/,
      'Should fail with 3 missing shards'
    )
  } finally {
    cleanup(tempDir)
  }
})

test('SupernodeStorage stats includes erasure config when enabled', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore, {
      enableErasure: true,
      dataShards: 8,
      parityShards: 4
    })
    
    const stats = storage.stats()
    
    assert.ok(stats.erasure, 'Stats should include erasure info')
    assert.strictEqual(stats.erasure.dataShards, 8)
    assert.strictEqual(stats.erasure.parityShards, 4)
    assert.strictEqual(stats.erasure.totalShards, 12)
  } finally {
    cleanup(tempDir)
  }
})

test('SupernodeStorage emits erasure events', async () => {
  const tempDir = createTempDir()
  try {
    const blobStore = new BlobStore(tempDir)
    const storage = new SupernodeStorage(blobStore, {
      enableErasure: true,
      dataShards: 4,
      parityShards: 2
    })
    const masterKey = generateEncryptionKey()
    
    const events = []
    storage.on('erasure-encoded', (e) => events.push({ type: 'encoded', ...e }))
    storage.on('erasure-decoded', (e) => events.push({ type: 'decoded', ...e }))
    
    const originalData = Buffer.from('Event test data')
    const { fileId } = await storage.ingest(originalData, 'events.txt', masterKey)
    
    assert.strictEqual(events.length, 1, 'Should emit erasure-encoded event')
    assert.strictEqual(events[0].type, 'encoded')
    assert.strictEqual(events[0].dataShards, 4)
    assert.strictEqual(events[0].parityShards, 2)
    
    await storage.retrieve(fileId, masterKey)
    
    assert.strictEqual(events.length, 2, 'Should emit erasure-decoded event')
    assert.strictEqual(events[1].type, 'decoded')
    assert.strictEqual(events[1].presentShards, 6)
  } finally {
    cleanup(tempDir)
  }
})
