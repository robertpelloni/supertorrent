import test from 'tape'
import { randomBytes } from 'crypto'
import { mkdtempSync, rmSync } from 'fs'
import { tmpdir } from 'os'
import { join } from 'path'

import { ingest, reassemble, BlobClient } from '../reference-client/lib/storage.js'
import { BlobStore } from '../reference-client/lib/blob-store.js'
import { BlobNetwork } from '../reference-client/lib/blob-network.js'

const createTempDir = (prefix) => mkdtempSync(join(tmpdir(), prefix))

const cleanupDir = (dir) => {
  try {
    rmSync(dir, { recursive: true, force: true })
  } catch {
    // ignore
  }
}

const blobsToMap = (blobs) => {
  const map = new Map()
  for (const blob of blobs) {
    map.set(blob.id, blob.buffer)
  }
  return map
}

test('blob: ingest and reassemble locally', async (t) => {
  t.plan(5)

  const originalData = randomBytes(3 * 1024 * 1024 + 12345)
  const fileName = 'test-file.bin'

  const { fileEntry, blobs } = ingest(originalData, fileName)

  t.ok(fileEntry, 'fileEntry created')
  t.equal(fileEntry.name, fileName, 'fileName matches')
  t.equal(fileEntry.size, originalData.length, 'size matches')
  t.equal(blobs.length, 4, 'should have 4 blobs (3 full + 1 partial)')

  const blobMap = blobsToMap(blobs)
  const getBlobFn = async (blobId) => {
    const blob = blobMap.get(blobId)
    if (!blob) throw new Error(`Blob not found: ${blobId}`)
    return blob
  }

  const reassembled = await reassemble(fileEntry, getBlobFn)
  t.ok(originalData.equals(reassembled), 'reassembled data matches original')
})

test('blob-store: put, get, verify integrity', async (t) => {
  const storageDir = createTempDir('blob-store-test-')

  try {
    const store = new BlobStore(storageDir, { maxSize: 100 * 1024 * 1024 })

    const testData = randomBytes(1024 * 1024)
    const { fileEntry, blobs } = ingest(testData, 'test.bin')

    for (const blob of blobs) {
      store.put(blob.id, blob.buffer)
      t.ok(store.has(blob.id), `blob ${blob.id.slice(0, 8)}... exists`)
    }

    const firstBlobId = fileEntry.chunks[0].blobId
    const retrieved = store.get(firstBlobId)
    t.ok(retrieved, 'retrieved blob')

    const blobMap = blobsToMap(blobs)
    t.ok(blobMap.get(firstBlobId).equals(retrieved), 'retrieved blob matches original')
    t.end()
  } catch (err) {
    t.fail(err.message)
    t.end()
  } finally {
    cleanupDir(storageDir)
  }
})

test('blob-store: LRU eviction works', async (t) => {
  t.plan(3)

  const storageDir = createTempDir('blob-store-evict-')

  try {
    const store = new BlobStore(storageDir, { maxSize: 3 * 1024 * 1024 })

    const blobIds = []
    for (let i = 0; i < 4; i++) {
      const data = randomBytes(1024 * 1024)
      const { blobs } = ingest(data, `test-${i}.bin`)

      for (const blob of blobs) {
        store.put(blob.id, blob.buffer)
        blobIds.push(blob.id)
      }
    }

    const stats = store.stats()
    t.ok(stats.currentSize <= 3 * 1024 * 1024, `total size (${stats.currentSize}) under limit`)
    t.ok(stats.blobCount < 4, `blob count (${stats.blobCount}) less than 4 due to eviction`)

    const firstExists = store.has(blobIds[0])
    t.notOk(firstExists, 'first blob evicted')
  } finally {
    cleanupDir(storageDir)
  }
})

test('blob-store: pinned blobs not evicted', async (t) => {
  t.plan(2)

  const storageDir = createTempDir('blob-store-pin-')

  try {
    const store = new BlobStore(storageDir, { maxSize: 2 * 1024 * 1024 })

    const data1 = randomBytes(1024 * 1024)
    const { blobs: blobs1 } = ingest(data1, 'pinned.bin')
    const pinnedId = blobs1[0].id
    store.put(pinnedId, blobs1[0].buffer)
    store.pin(pinnedId)

    for (let i = 0; i < 3; i++) {
      const data = randomBytes(512 * 1024)
      const { blobs } = ingest(data, `fill-${i}.bin`)
      for (const blob of blobs) {
        store.put(blob.id, blob.buffer)
      }
    }

    t.ok(store.has(pinnedId), 'pinned blob still exists')

    const pinnedCount = store.list().filter(b => b.pinned).length
    t.ok(pinnedCount >= 1, 'at least 1 pinned blob')
  } finally {
    cleanupDir(storageDir)
  }
})

test('blob-network: P2P transfer between two nodes', async (t) => {
  t.plan(5)

  const storageDir1 = createTempDir('blob-net-seeder-')
  const storageDir2 = createTempDir('blob-net-fetcher-')

  let seederNet, fetcherNet

  try {
    const seederStore = new BlobStore(storageDir1)
    seederNet = new BlobNetwork(seederStore)

    const fetcherStore = new BlobStore(storageDir2)
    fetcherNet = new BlobNetwork(fetcherStore)

    const seederPort = await seederNet.listen(0)
    t.ok(seederPort, `seeder listening on port ${seederPort}`)

    const originalData = randomBytes(2 * 1024 * 1024 + 5000)
    const { fileEntry, blobs } = ingest(originalData, 'transfer-test.bin')

    for (const blob of blobs) {
      seederStore.put(blob.id, blob.buffer)
    }
    t.pass(`seeder has ${blobs.length} blobs`)

    const haveReceived = new Promise((resolve) => {
      fetcherNet.once('have', resolve)
    })

    const seederAddr = `ws://127.0.0.1:${seederPort}`
    await fetcherNet.connect(seederAddr)
    t.pass('fetcher connected to seeder')

    await haveReceived

    for (const chunk of fileEntry.chunks) {
      const blob = await fetcherNet.requestBlob(chunk.blobId)
      fetcherStore.put(chunk.blobId, blob)
    }
    t.pass('all blobs fetched')

    const getBlobFn = async (blobId) => fetcherStore.get(blobId)
    const reassembled = await reassemble(fileEntry, getBlobFn)
    t.ok(originalData.equals(reassembled), 'reassembled data matches original')
  } finally {
    if (seederNet) seederNet.destroy()
    if (fetcherNet) fetcherNet.destroy()
    cleanupDir(storageDir1)
    cleanupDir(storageDir2)
  }
})

test('blob: encryption keys correctly applied', async (t) => {
  t.plan(4)

  const originalData = Buffer.from('This is sensitive test data that must be encrypted!')
  const { fileEntry, blobs } = ingest(originalData, 'secret.txt')

  for (const blob of blobs) {
    const containsPlaintext = blob.buffer.includes(Buffer.from('sensitive'))
    t.notOk(containsPlaintext, 'encrypted blob should not contain plaintext')
  }

  t.ok(fileEntry.chunks[0].key, 'chunk has encryption key')
  t.equal(fileEntry.chunks[0].key.length, 64, 'key is 32 bytes hex (64 chars)')

  const blobMap = blobsToMap(blobs)
  const getBlobFn = async (blobId) => blobMap.get(blobId)
  const decrypted = await reassemble(fileEntry, getBlobFn)
  t.ok(originalData.equals(decrypted), 'decrypted data matches original')
})

test('blob-network: handles NOT_FOUND correctly', async (t) => {
  t.plan(2)

  const storageDir = createTempDir('blob-net-notfound-')
  let network

  try {
    const store = new BlobStore(storageDir)
    network = new BlobNetwork(store)

    const port = await network.listen(0)
    t.ok(port, `network listening on port ${port}`)

    const fakeBlobId = 'deadbeef'.repeat(8)

    try {
      await network.requestBlob(fakeBlobId)
      t.fail('should have thrown for missing blob')
    } catch (err) {
      t.ok(
        err.message.includes('not found') ||
        err.message.includes('not available') ||
        err.message.includes('No peers'),
        'errors on missing blob'
      )
    }
  } finally {
    if (network) network.destroy()
    cleanupDir(storageDir)
  }
})

test('blob-network: handles concurrent requests', async (t) => {
  t.plan(3)

  const storageDir1 = createTempDir('blob-concurrent-seeder-')
  const storageDir2 = createTempDir('blob-concurrent-fetcher-')

  let seederNet, fetcherNet

  try {
    const seederStore = new BlobStore(storageDir1)
    seederNet = new BlobNetwork(seederStore)

    const seederPort = await seederNet.listen(0)

    const blobEntries = []
    for (let i = 0; i < 5; i++) {
      const data = randomBytes(256 * 1024)
      const { blobs } = ingest(data, `concurrent-${i}.bin`)
      for (const blob of blobs) {
        seederStore.put(blob.id, blob.buffer)
        blobEntries.push({ blobId: blob.id, expected: blob.buffer })
      }
    }
    t.pass(`seeder has ${blobEntries.length} blobs`)

    const fetcherStore = new BlobStore(storageDir2)
    fetcherNet = new BlobNetwork(fetcherStore)

    const haveReceived = new Promise((resolve) => {
      fetcherNet.once('have', resolve)
    })

    await fetcherNet.connect(`ws://127.0.0.1:${seederPort}`)
    await haveReceived

    const fetchPromises = blobEntries.map(({ blobId }) =>
      fetcherNet.requestBlob(blobId)
    )

    const results = await Promise.all(fetchPromises)
    t.equal(results.length, blobEntries.length, 'fetched all blobs')

    let allMatch = true
    for (let i = 0; i < results.length; i++) {
      if (!results[i].equals(blobEntries[i].expected)) {
        allMatch = false
        break
      }
    }
    t.ok(allMatch, 'all concurrent fetches returned correct data')
  } finally {
    if (seederNet) seederNet.destroy()
    if (fetcherNet) fetcherNet.destroy()
    cleanupDir(storageDir1)
    cleanupDir(storageDir2)
  }
})
