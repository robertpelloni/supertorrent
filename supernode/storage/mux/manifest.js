import crypto from 'crypto'

const MANIFEST_VERSION = 1

export function createManifest (options) {
  const {
    fileId,
    fileName,
    fileSize,
    isoSeed,
    isoSize,
    encryptionKey,
    erasure,
    segments
  } = options

  return {
    version: MANIFEST_VERSION,
    fileId,
    fileName,
    fileSize,
    isoSeed: Buffer.isBuffer(isoSeed) ? isoSeed.toString('hex') : isoSeed,
    isoSize,
    isoHash: null,
    erasure: erasure || null,
    segments: segments.map(seg => ({
      chunkHash: seg.chunkHash,
      chunkKey: seg.chunkKey,
      isoSeed: seg.isoSeed,
      sectorStart: seg.sectorStart,
      sectorCount: seg.sectorCount,
      encryptedSize: seg.encryptedSize,
      originalSize: seg.originalSize,
      // Erasure coding fields (when enabled)
      shards: seg.shards || null,
      muxedSize: seg.muxedSize || null,
      shardSize: seg.shardSize || null
    })),
    createdAt: Date.now()
  }
}

export function encryptManifest (manifest, key) {
  const json = JSON.stringify(manifest)
  const plaintext = Buffer.from(json, 'utf8')
  
  const nonce = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('chacha20-poly1305', key, nonce, { authTagLength: 16 })
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()])
  const tag = cipher.getAuthTag()
  
  const result = Buffer.alloc(1 + 12 + 16 + encrypted.length)
  result[0] = MANIFEST_VERSION
  nonce.copy(result, 1)
  tag.copy(result, 13)
  encrypted.copy(result, 29)
  
  return result
}

export function decryptManifest (encryptedManifest, key) {
  const version = encryptedManifest[0]
  if (version !== MANIFEST_VERSION) {
    throw new Error(`Unsupported manifest version: ${version}`)
  }
  
  const nonce = encryptedManifest.subarray(1, 13)
  const tag = encryptedManifest.subarray(13, 29)
  const encrypted = encryptedManifest.subarray(29)
  
  const decipher = crypto.createDecipheriv('chacha20-poly1305', key, nonce, { authTagLength: 16 })
  decipher.setAuthTag(tag)
  const decrypted = Buffer.concat([decipher.update(encrypted), decipher.final()])
  
  return JSON.parse(decrypted.toString('utf8'))
}

export function deriveManifestKey (masterKey, fileId) {
  return crypto.createHmac('sha256', masterKey)
    .update(`manifest:${fileId}`)
    .digest()
}

export function hashManifest (manifest) {
  const normalized = JSON.stringify(manifest, Object.keys(manifest).sort())
  return crypto.createHash('sha256').update(normalized).digest('hex')
}

export default {
  createManifest,
  encryptManifest,
  decryptManifest,
  deriveManifestKey,
  hashManifest
}
