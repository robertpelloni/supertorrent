import test from 'node:test'
import assert from 'node:assert'
import crypto from 'crypto'
import { ErasureCoder } from '../supernode/storage/erasure/coder.js'

test('ErasureCoder', async (t) => {
  await t.test('constructor with default options', () => {
    const coder = new ErasureCoder()
    
    assert.strictEqual(coder.dataShards, 4)
    assert.strictEqual(coder.parityShards, 2)
    assert.strictEqual(coder.totalShards, 6)
  })

  await t.test('constructor with custom options', () => {
    const coder = new ErasureCoder({ dataShards: 8, parityShards: 4 })
    
    assert.strictEqual(coder.dataShards, 8)
    assert.strictEqual(coder.parityShards, 4)
    assert.strictEqual(coder.totalShards, 12)
  })

  await t.test('constructor rejects invalid shard counts', () => {
    assert.throws(() => new ErasureCoder({ dataShards: 0 }), /dataShards must be/)
    assert.throws(() => new ErasureCoder({ dataShards: 256 }), /dataShards must be/)
    assert.throws(() => new ErasureCoder({ parityShards: 0 }), /parityShards must be/)
    assert.throws(() => new ErasureCoder({ dataShards: 200, parityShards: 100 }), /Total shards/)
  })

  await t.test('encode produces correct number of shards', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = crypto.randomBytes(1024)
    
    const result = coder.encode(data)
    
    assert.strictEqual(result.shards.length, 6)
    assert.strictEqual(result.dataShards, 4)
    assert.strictEqual(result.parityShards, 2)
    assert.strictEqual(result.originalSize, 1024)
  })

  await t.test('encode produces equal-sized shards', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = crypto.randomBytes(1000)
    
    const result = coder.encode(data)
    
    const shardSizes = result.shards.map(s => s.length)
    assert.ok(shardSizes.every(s => s === result.shardSize))
  })

  await t.test('decode recovers original data with all shards', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from('Hello, World! This is a test of erasure coding.')
    
    const encoded = coder.encode(data)
    const indices = [0, 1, 2, 3, 4, 5]
    
    const decoded = coder.decode(encoded.shards, indices, encoded.originalSize, encoded.shardSize)
    
    assert.ok(decoded.equals(data))
  })

  await t.test('decode recovers data with only data shards', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from('Recovery test with minimum shards required.')
    
    const encoded = coder.encode(data)
    const indices = [0, 1, 2, 3]
    
    const decoded = coder.decode(encoded.shards, indices, encoded.originalSize, encoded.shardSize)
    
    assert.ok(decoded.equals(data))
  })

  await t.test('decode recovers data with one data shard missing', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from('Testing recovery with one missing data shard.')
    
    const encoded = coder.encode(data)
    const indices = [0, 1, 2, 4]
    
    const decoded = coder.decode(encoded.shards, indices, encoded.originalSize, encoded.shardSize)
    
    assert.ok(decoded.equals(data))
  })

  await t.test('decode recovers data with two data shards missing', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from('Testing recovery with two missing data shards.')
    
    const encoded = coder.encode(data)
    const indices = [0, 1, 4, 5]
    
    const decoded = coder.decode(encoded.shards, indices, encoded.originalSize, encoded.shardSize)
    
    assert.ok(decoded.equals(data))
  })

  await t.test('decode fails with too few shards', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from('Test data')
    
    const encoded = coder.encode(data)
    const indices = [0, 1, 2]
    
    assert.throws(
      () => coder.decode(encoded.shards, indices, encoded.originalSize, encoded.shardSize),
      /Need at least 4 shards/
    )
  })

  await t.test('encode/decode with large random data', () => {
    const coder = new ErasureCoder({ dataShards: 8, parityShards: 4 })
    const data = crypto.randomBytes(64 * 1024)
    
    const encoded = coder.encode(data)
    const indices = [0, 2, 4, 6, 8, 9, 10, 11]
    
    const decoded = coder.decode(encoded.shards, indices, encoded.originalSize, encoded.shardSize)
    
    assert.ok(decoded.equals(data))
  })

  await t.test('computeShardHash produces consistent SHA256', () => {
    const coder = new ErasureCoder()
    const shard = Buffer.from('test shard data')
    
    const hash1 = coder.computeShardHash(shard)
    const hash2 = coder.computeShardHash(shard)
    
    assert.strictEqual(hash1, hash2)
    assert.strictEqual(hash1.length, 64)
  })

  await t.test('verify checks shard integrity', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from('Verification test data.')
    
    const encoded = coder.encode(data)
    const hashes = encoded.shards.map(s => coder.computeShardHash(s))
    
    const results = coder.verify(encoded.shards, hashes)
    
    assert.strictEqual(results.length, 6)
    assert.ok(results.every(r => r.valid === true))
  })

  await t.test('verify detects corrupted shard', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from('Corruption detection test.')
    
    const encoded = coder.encode(data)
    const hashes = encoded.shards.map(s => coder.computeShardHash(s))
    
    encoded.shards[2][0] ^= 0xFF
    
    const results = coder.verify(encoded.shards, hashes)
    
    assert.strictEqual(results[2].valid, false)
    assert.ok(results.filter(r => r.valid).length === 5)
  })

  await t.test('verify handles missing shards', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from('Missing shard test.')
    
    const encoded = coder.encode(data)
    const hashes = encoded.shards.map(s => coder.computeShardHash(s))
    
    encoded.shards[1] = null
    hashes[3] = null
    
    const results = coder.verify(encoded.shards, hashes)
    
    assert.strictEqual(results[1].missing, true)
    assert.strictEqual(results[3].missing, true)
  })

  await t.test('getStats returns configuration info', () => {
    const coder = new ErasureCoder({ dataShards: 10, parityShards: 4 })
    
    const stats = coder.getStats()
    
    assert.strictEqual(stats.dataShards, 10)
    assert.strictEqual(stats.parityShards, 4)
    assert.strictEqual(stats.totalShards, 14)
    assert.strictEqual(stats.redundancyRatio, 0.4)
    assert.strictEqual(stats.maxRecoverable, 4)
  })

  await t.test('emits encoded event', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from('Event test')
    
    let eventData = null
    coder.on('encoded', (info) => { eventData = info })
    
    coder.encode(data)
    
    assert.ok(eventData)
    assert.strictEqual(eventData.originalSize, data.length)
    assert.strictEqual(eventData.dataShards, 4)
    assert.strictEqual(eventData.parityShards, 2)
  })

  await t.test('emits decoded event', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from('Decode event test')
    
    let eventData = null
    coder.on('decoded', (info) => { eventData = info })
    
    const encoded = coder.encode(data)
    coder.decode(encoded.shards, [0, 1, 2, 4], encoded.originalSize, encoded.shardSize)
    
    assert.ok(eventData)
    assert.strictEqual(eventData.originalSize, data.length)
    assert.strictEqual(eventData.usedShards, 4)
    assert.strictEqual(eventData.missingShards, 2)
  })
})

test('ErasureCoder stress tests', async (t) => {
  await t.test('handles single byte data', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from([42])
    
    const encoded = coder.encode(data)
    const decoded = coder.decode(encoded.shards, [0, 1, 2, 3], encoded.originalSize, encoded.shardSize)
    
    assert.ok(decoded.equals(data))
  })

  await t.test('handles data exactly divisible by dataShards', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = crypto.randomBytes(400)
    
    const encoded = coder.encode(data)
    const decoded = coder.decode(encoded.shards, [0, 2, 4, 5], encoded.originalSize, encoded.shardSize)
    
    assert.ok(decoded.equals(data))
  })

  await t.test('recovery with different shard combinations', () => {
    const coder = new ErasureCoder({ dataShards: 4, parityShards: 2 })
    const data = Buffer.from('Testing multiple recovery combinations')
    
    const encoded = coder.encode(data)
    
    const combinations = [
      [0, 1, 2, 3],
      [0, 1, 2, 4],
      [0, 1, 2, 5],
      [0, 1, 4, 5],
      [2, 3, 4, 5]
    ]
    
    for (const indices of combinations) {
      const decoded = coder.decode(encoded.shards, indices, encoded.originalSize, encoded.shardSize)
      assert.ok(decoded.equals(data), `Failed with indices: ${indices}`)
    }
  })
})
