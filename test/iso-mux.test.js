import test from 'node:test'
import assert from 'node:assert'
import crypto from 'crypto'
import { ISOForge, SeededRNG, SIZE_PRESETS, generateSeed } from '../supernode/storage/iso-forge/index.js'
import { MuxEngine, generateEncryptionKey, generateISOSeed } from '../supernode/storage/mux/index.js'
import { createManifest, encryptManifest, decryptManifest, deriveManifestKey } from '../supernode/storage/mux/manifest.js'

const SECTOR_SIZE = 2048

test('SeededRNG produces deterministic output', () => {
  const seed = crypto.randomBytes(32)
  const rng1 = new SeededRNG(seed)
  const rng2 = new SeededRNG(seed)
  
  const bytes1 = rng1.bytes(1000)
  const bytes2 = rng2.bytes(1000)
  
  assert.deepStrictEqual(bytes1, bytes2, 'Same seed should produce identical bytes')
})

test('SeededRNG derive creates independent streams', () => {
  const seed = crypto.randomBytes(32)
  const rng = new SeededRNG(seed)
  
  const child1 = rng.derive('stream-a')
  const child2 = rng.derive('stream-b')
  
  const bytes1 = child1.bytes(100)
  const bytes2 = child2.bytes(100)
  
  assert.notDeepStrictEqual(bytes1, bytes2, 'Different labels should produce different streams')
})

test('ISOForge generates valid ISO 9660 structure', () => {
  const seed = generateSeed()
  const forge = new ISOForge()
  
  const pvdSector = forge.generateSector(seed, 16, 'nano')
  
  assert.strictEqual(pvdSector[0], 1, 'PVD type code should be 1')
  assert.strictEqual(pvdSector.toString('ascii', 1, 6), 'CD001', 'Standard identifier should be CD001')
  assert.strictEqual(pvdSector[6], 1, 'Version should be 1')
})

test('ISOForge same seed produces identical sectors', () => {
  const seed = generateSeed()
  const forge = new ISOForge()
  
  const sector1a = forge.generateSector(seed, 16, 'nano')
  const sector1b = forge.generateSector(seed, 16, 'nano')
  
  assert.deepStrictEqual(sector1a, sector1b, 'Same seed+sector should be identical')
  
  const sector20a = forge.generateSector(seed, 20, 'nano')
  const sector20b = forge.generateSector(seed, 20, 'nano')
  
  assert.deepStrictEqual(sector20a, sector20b, 'Content sectors should also be identical')
})

test('ISOForge different seeds produce different ISOs', () => {
  const seed1 = generateSeed()
  const seed2 = generateSeed()
  const forge = new ISOForge()
  
  const sector1 = forge.generateSector(seed1, 20, 'nano')
  const sector2 = forge.generateSector(seed2, 20, 'nano')
  
  assert.notDeepStrictEqual(sector1, sector2, 'Different seeds should produce different content')
})

test('ISOForge generates correct size ISOs', () => {
  const seed = generateSeed()
  const forge = new ISOForge()
  
  const smallSize = SECTOR_SIZE * 100
  const iso = forge.generate(seed, smallSize)
  
  assert.strictEqual(iso.length, smallSize, 'Generated ISO should be exact size requested')
})

test('MuxEngine mux/demux roundtrip', () => {
  const plaintext = Buffer.from('Hello, Supernode! This is a test message for the mux engine.')
  const key = generateEncryptionKey()
  const isoSeed = generateISOSeed()
  
  const engine = new MuxEngine()
  const { muxedData, manifest } = engine.mux(plaintext, key, isoSeed)
  
  const recovered = engine.demux(muxedData, key, manifest)
  
  assert.deepStrictEqual(recovered, plaintext, 'Demuxed data should match original')
})

test('MuxEngine handles large data', () => {
  const plaintext = crypto.randomBytes(50000)
  const key = generateEncryptionKey()
  const isoSeed = generateISOSeed()
  
  const engine = new MuxEngine()
  const { muxedData, manifest } = engine.mux(plaintext, key, isoSeed)
  
  assert.ok(manifest.sectorsUsed >= 25, 'Should use multiple sectors')
  
  const recovered = engine.demux(muxedData, key, manifest)
  assert.deepStrictEqual(recovered, plaintext, 'Large data should roundtrip correctly')
})

test('MuxEngine wrong key fails decryption', () => {
  const plaintext = Buffer.from('Secret message')
  const key = generateEncryptionKey()
  const wrongKey = generateEncryptionKey()
  const isoSeed = generateISOSeed()
  
  const engine = new MuxEngine()
  const { muxedData, manifest } = engine.mux(plaintext, key, isoSeed)
  
  assert.throws(() => {
    engine.demux(muxedData, wrongKey, manifest)
  }, 'Wrong key should throw authentication error')
})

test('MuxEngine muxed data is same size as padded sectors', () => {
  const plaintext = Buffer.from('Test')
  const key = generateEncryptionKey()
  const isoSeed = generateISOSeed()
  
  const engine = new MuxEngine()
  const { muxedData, manifest } = engine.mux(plaintext, key, isoSeed)
  
  assert.strictEqual(muxedData.length, manifest.sectorsUsed * SECTOR_SIZE, 
    'Muxed data length should be sector-aligned')
})

test('Manifest encryption roundtrip', () => {
  const manifest = createManifest({
    fileId: 'sha256:' + crypto.randomBytes(32).toString('hex'),
    fileName: 'test.dat',
    fileSize: 12345,
    isoSeed: generateISOSeed(),
    isoSize: 'nano',
    encryptionKey: generateEncryptionKey(),
    segments: [
      { chunkHash: 'abc123', sectorStart: 0, sectorCount: 5, encryptedSize: 10000 }
    ]
  })
  
  const key = generateEncryptionKey()
  const encrypted = encryptManifest(manifest, key)
  const decrypted = decryptManifest(encrypted, key)
  
  assert.strictEqual(decrypted.fileId, manifest.fileId)
  assert.strictEqual(decrypted.fileName, manifest.fileName)
  assert.strictEqual(decrypted.fileSize, manifest.fileSize)
  assert.strictEqual(decrypted.segments.length, 1)
})

test('Manifest wrong key fails decryption', () => {
  const manifest = createManifest({
    fileId: 'test',
    fileName: 'test.dat',
    fileSize: 100,
    isoSeed: generateISOSeed(),
    isoSize: 'nano',
    segments: []
  })
  
  const key = generateEncryptionKey()
  const wrongKey = generateEncryptionKey()
  const encrypted = encryptManifest(manifest, key)
  
  assert.throws(() => {
    decryptManifest(encrypted, wrongKey)
  }, 'Wrong key should fail manifest decryption')
})

test('deriveManifestKey is deterministic', () => {
  const masterKey = generateEncryptionKey()
  const fileId = 'test-file-123'
  
  const key1 = deriveManifestKey(masterKey, fileId)
  const key2 = deriveManifestKey(masterKey, fileId)
  
  assert.deepStrictEqual(key1, key2, 'Same inputs should derive same key')
  
  const key3 = deriveManifestKey(masterKey, 'different-file')
  assert.notDeepStrictEqual(key1, key3, 'Different fileIds should derive different keys')
})

test('ISO muxing plausible deniability - chunks look like ISO data', () => {
  const secretData = Buffer.from('This is secret content that should be hidden')
  const key = generateEncryptionKey()
  const isoSeed = generateISOSeed()
  
  const engine = new MuxEngine()
  const forge = new ISOForge()
  
  const { muxedData, manifest } = engine.mux(secretData, key, isoSeed)
  
  const originalISO = forge.generate(isoSeed, SIZE_PRESETS.nano)
  const isoSector16 = originalISO.subarray(16 * SECTOR_SIZE, 17 * SECTOR_SIZE)
  
  assert.strictEqual(isoSector16[0], 1, 'Original ISO PVD type is 1')
  assert.strictEqual(isoSector16.toString('ascii', 1, 6), 'CD001', 'Original ISO has CD001')
})

test('Full workflow: ingest -> mux -> demux -> recover', async () => {
  const originalFile = Buffer.from('This is a complete file that will be processed through the full supernode workflow.')
  const fileName = 'document.txt'
  
  const encryptionKey = generateEncryptionKey()
  const isoSeed = generateISOSeed()
  const fileId = 'sha256:' + crypto.createHash('sha256').update(originalFile).digest('hex')
  
  const engine = new MuxEngine()
  const { muxedData, manifest: muxManifest } = engine.mux(originalFile, encryptionKey, isoSeed)
  
  const fullManifest = createManifest({
    fileId,
    fileName,
    fileSize: originalFile.length,
    isoSeed,
    isoSize: muxManifest.isoSize,
    encryptionKey,
    segments: [{
      chunkHash: crypto.createHash('sha256').update(muxedData).digest('hex'),
      sectorStart: 0,
      sectorCount: muxManifest.sectorsUsed,
      encryptedSize: muxManifest.encryptedSize
    }]
  })
  
  const manifestKey = deriveManifestKey(encryptionKey, fileId)
  const encryptedManifest = encryptManifest(fullManifest, manifestKey)
  
  const recoveredManifest = decryptManifest(encryptedManifest, manifestKey)
  const recoveredFile = engine.demux(muxedData, encryptionKey, {
    isoSeed: recoveredManifest.isoSeed,
    isoSize: recoveredManifest.isoSize,
    sectorsUsed: recoveredManifest.segments[0].sectorCount,
    encryptedSize: recoveredManifest.segments[0].encryptedSize
  })
  
  assert.deepStrictEqual(recoveredFile, originalFile, 'Full workflow should recover original file')
  assert.strictEqual(recoveredManifest.fileName, fileName)
  assert.strictEqual(recoveredManifest.fileId, fileId)
})
