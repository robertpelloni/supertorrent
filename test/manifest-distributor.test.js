import test from 'node:test'
import assert from 'node:assert'
import crypto from 'crypto'
import { ManifestDistributor } from '../supernode/network/manifest-distributor.js'

function createMockDHT () {
  const listeners = new Map()
  return {
    dht: {
      announce: (infoHash, port, cb) => {
        setTimeout(() => cb && cb(null), 10)
      },
      lookup: (infoHash, cb) => {
        setTimeout(() => cb && cb(null, 0), 10)
      },
      on: (event, handler) => {
        if (!listeners.has(event)) listeners.set(event, [])
        listeners.get(event).push(handler)
      },
      removeListener: (event, handler) => {
        const handlers = listeners.get(event)
        if (handlers) {
          const idx = handlers.indexOf(handler)
          if (idx >= 0) handlers.splice(idx, 1)
        }
      },
      emit: (event, ...args) => {
        const handlers = listeners.get(event)
        if (handlers) handlers.forEach(h => h(...args))
      }
    },
    _listeners: listeners
  }
}

function createMockStorage () {
  const manifests = new Map()
  return {
    getManifest: (fileId) => manifests.get(fileId),
    storeManifest: (fileId, manifest) => manifests.set(fileId, manifest),
    _manifests: manifests
  }
}

test('ManifestDistributor', async (t) => {
  await t.test('constructor initializes correctly', () => {
    const dht = createMockDHT()
    const storage = createMockStorage()
    
    const distributor = new ManifestDistributor({ dht, storage, wsPort: 8080 })
    
    assert.strictEqual(distributor.wsPort, 8080)
    assert.strictEqual(distributor._destroyed, false)
    assert.strictEqual(distributor.manifestCache.size, 0)
    assert.strictEqual(distributor.pendingRequests.size, 0)
  })

  await t.test('setPort updates wsPort', () => {
    const distributor = new ManifestDistributor({})
    
    distributor.setPort(9999)
    
    assert.strictEqual(distributor.wsPort, 9999)
  })

  await t.test('fileIdToInfoHash produces consistent SHA1 hashes', () => {
    const distributor = new ManifestDistributor({})
    const fileId = 'test-file-id-12345'
    
    const hash1 = distributor.fileIdToInfoHash(fileId)
    const hash2 = distributor.fileIdToInfoHash(fileId)
    
    assert.ok(hash1 instanceof Buffer)
    assert.strictEqual(hash1.length, 20)
    assert.ok(hash1.equals(hash2))
  })

  await t.test('fileIdToInfoHash produces different hashes for different fileIds', () => {
    const distributor = new ManifestDistributor({})
    
    const hash1 = distributor.fileIdToInfoHash('file-1')
    const hash2 = distributor.fileIdToInfoHash('file-2')
    
    assert.ok(!hash1.equals(hash2))
  })

  await t.test('announceManifest adds to cache and calls DHT', async () => {
    const dht = createMockDHT()
    const storage = createMockStorage()
    const distributor = new ManifestDistributor({ dht, storage, wsPort: 8080 })
    
    const events = []
    distributor.on('manifest-announced', (info) => events.push(info))
    
    distributor.announceManifest('test-file-id')
    
    await new Promise(r => setTimeout(r, 50))
    
    assert.strictEqual(distributor.manifestCache.has('test-file-id'), true)
    assert.strictEqual(events.length, 1)
    assert.strictEqual(events[0].fileId, 'test-file-id')
  })

  await t.test('announceManifest does nothing when destroyed', () => {
    const dht = createMockDHT()
    const storage = createMockStorage()
    const distributor = new ManifestDistributor({ dht, storage, wsPort: 8080 })
    distributor._destroyed = true
    
    distributor.announceManifest('test-file-id')
    
    assert.strictEqual(distributor.manifestCache.size, 0)
  })

  await t.test('createManifestRequest returns valid request object', () => {
    const distributor = new ManifestDistributor({})
    
    const request = distributor.createManifestRequest('my-file-id')
    
    assert.strictEqual(request.type, 'manifest-request')
    assert.strictEqual(request.fileId, 'my-file-id')
    assert.ok(request.requestId)
    assert.ok(request.timestamp)
  })

  await t.test('createManifestResponse with manifest', () => {
    const distributor = new ManifestDistributor({})
    const manifest = Buffer.from('encrypted-manifest-data')
    
    const response = distributor.createManifestResponse('req-123', 'file-abc', manifest)
    
    assert.strictEqual(response.type, 'manifest-response')
    assert.strictEqual(response.requestId, 'req-123')
    assert.strictEqual(response.fileId, 'file-abc')
    assert.strictEqual(response.found, true)
    assert.strictEqual(response.manifest, manifest.toString('base64'))
  })

  await t.test('createManifestResponse without manifest', () => {
    const distributor = new ManifestDistributor({})
    
    const response = distributor.createManifestResponse('req-123', 'file-abc', null)
    
    assert.strictEqual(response.found, false)
    assert.strictEqual(response.manifest, null)
  })

  await t.test('handleMessage processes manifest-request', () => {
    const storage = createMockStorage()
    const testManifest = Buffer.from('test-manifest-content')
    storage.storeManifest('file-123', testManifest)
    
    const distributor = new ManifestDistributor({ storage })
    
    const request = {
      type: 'manifest-request',
      fileId: 'file-123',
      requestId: 'req-abc'
    }
    
    let sentResponse = null
    const result = distributor.handleMessage(request, (resp) => { sentResponse = resp })
    
    assert.strictEqual(result, true)
    assert.ok(sentResponse)
    assert.strictEqual(sentResponse.type, 'manifest-response')
    assert.strictEqual(sentResponse.requestId, 'req-abc')
    assert.strictEqual(sentResponse.found, true)
  })

  await t.test('handleMessage returns false for unknown message types', () => {
    const distributor = new ManifestDistributor({})
    
    const result = distributor.handleMessage({ type: 'unknown' }, () => {})
    
    assert.strictEqual(result, false)
  })

  await t.test('handleMessage processes manifest-response for pending request', async () => {
    const distributor = new ManifestDistributor({})
    
    const testManifest = Buffer.from('my-manifest-data')
    const requestId = 'pending-req-123'
    
    let resolvedResult = null
    const promise = new Promise((resolve) => {
      distributor.pendingRequests.set(requestId, {
        fileId: 'file-xyz',
        resolve: (result) => {
          resolvedResult = result
          resolve()
        },
        reject: () => {},
        timer: setTimeout(() => {}, 10000)
      })
    })
    
    const response = {
      type: 'manifest-response',
      requestId,
      fileId: 'file-xyz',
      manifest: testManifest.toString('base64'),
      found: true
    }
    
    const result = distributor.handleMessage(response, () => {})
    
    assert.strictEqual(result, true)
    await promise
    
    assert.ok(resolvedResult)
    assert.strictEqual(resolvedResult.fileId, 'file-xyz')
    assert.ok(resolvedResult.manifest.equals(testManifest))
    assert.strictEqual(distributor.pendingRequests.has(requestId), false)
  })

  await t.test('requestManifest times out when no response', async () => {
    const distributor = new ManifestDistributor({})
    
    let sentRequest = null
    const sendRequest = (req) => { sentRequest = req }
    
    await assert.rejects(
      () => distributor.requestManifest('file-id', sendRequest, 100),
      /timeout/i
    )
    
    assert.ok(sentRequest)
    assert.strictEqual(sentRequest.type, 'manifest-request')
  })

  await t.test('getAnnouncedManifests returns cached manifests', () => {
    const distributor = new ManifestDistributor({})
    
    distributor.manifestCache.set('file-1', { announcedAt: 1000, infoHash: 'hash1' })
    distributor.manifestCache.set('file-2', { announcedAt: 2000, infoHash: 'hash2' })
    
    const manifests = distributor.getAnnouncedManifests()
    
    assert.strictEqual(manifests.length, 2)
    assert.ok(manifests.find(m => m.fileId === 'file-1'))
    assert.ok(manifests.find(m => m.fileId === 'file-2'))
  })

  await t.test('getStats returns correct counts', () => {
    const distributor = new ManifestDistributor({})
    
    distributor.manifestCache.set('f1', {})
    distributor.manifestCache.set('f2', {})
    distributor.pendingRequests.set('r1', { timer: setTimeout(() => {}, 1000) })
    
    const stats = distributor.getStats()
    
    assert.strictEqual(stats.announcedManifests, 2)
    assert.strictEqual(stats.pendingRequests, 1)
    
    clearTimeout(distributor.pendingRequests.get('r1').timer)
  })

  await t.test('destroy cleans up all resources', async () => {
    const distributor = new ManifestDistributor({})
    
    distributor.manifestCache.set('f1', {})
    
    let rejected = false
    distributor.pendingRequests.set('r1', {
      timer: setTimeout(() => {}, 10000),
      resolve: () => {},
      reject: () => { rejected = true }
    })
    
    const destroyedPromise = new Promise(resolve => {
      distributor.once('destroyed', resolve)
    })
    
    distributor.destroy()
    
    await destroyedPromise
    
    assert.strictEqual(distributor._destroyed, true)
    assert.strictEqual(distributor.manifestCache.size, 0)
    assert.strictEqual(distributor.pendingRequests.size, 0)
    assert.strictEqual(rejected, true)
  })

  await t.test('emits manifest-served event when handling request', () => {
    const storage = createMockStorage()
    storage.storeManifest('file-abc', Buffer.from('manifest'))
    
    const distributor = new ManifestDistributor({ storage })
    
    const events = []
    distributor.on('manifest-served', (info) => events.push(info))
    
    distributor.handleMessage(
      { type: 'manifest-request', fileId: 'file-abc', requestId: 'req-1' },
      () => {}
    )
    
    assert.strictEqual(events.length, 1)
    assert.strictEqual(events[0].fileId, 'file-abc')
    assert.strictEqual(events[0].found, true)
    assert.strictEqual(events[0].requestId, 'req-1')
  })

  await t.test('handles manifest-request for unknown file', () => {
    const storage = createMockStorage()
    const distributor = new ManifestDistributor({ storage })
    
    let response = null
    distributor.handleMessage(
      { type: 'manifest-request', fileId: 'unknown-file', requestId: 'req-1' },
      (resp) => { response = resp }
    )
    
    assert.strictEqual(response.found, false)
    assert.strictEqual(response.manifest, null)
  })
})

test('ManifestDistributor integration', async (t) => {
  await t.test('full request/response cycle between two distributors', async () => {
    const storage1 = createMockStorage()
    const storage2 = createMockStorage()
    
    const testManifest = Buffer.from('encrypted-file-manifest-content')
    storage1.storeManifest('shared-file', testManifest)
    
    const distributor1 = new ManifestDistributor({ storage: storage1 })
    const distributor2 = new ManifestDistributor({ storage: storage2 })
    
    const resultPromise = distributor2.requestManifest(
      'shared-file',
      (request) => {
        distributor1.handleMessage(request, (response) => {
          distributor2.handleMessage(response, () => {})
        })
      },
      5000
    )
    
    const result = await resultPromise
    
    assert.strictEqual(result.fileId, 'shared-file')
    assert.ok(result.manifest)
    assert.ok(result.manifest.equals(testManifest))
    
    distributor1.destroy()
    distributor2.destroy()
  })

  await t.test('request for non-existent manifest returns null', async () => {
    const storage1 = createMockStorage()
    const storage2 = createMockStorage()
    
    const distributor1 = new ManifestDistributor({ storage: storage1 })
    const distributor2 = new ManifestDistributor({ storage: storage2 })
    
    const resultPromise = distributor2.requestManifest(
      'nonexistent-file',
      (request) => {
        distributor1.handleMessage(request, (response) => {
          distributor2.handleMessage(response, () => {})
        })
      },
      5000
    )
    
    const result = await resultPromise
    
    assert.strictEqual(result.fileId, 'nonexistent-file')
    assert.strictEqual(result.manifest, null)
    
    distributor1.destroy()
    distributor2.destroy()
  })
})
