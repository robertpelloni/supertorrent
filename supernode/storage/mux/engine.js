import crypto from 'crypto'
import { ISOForge, SeededRNG, SIZE_PRESETS } from '../iso-forge/forge.js'

const SECTOR_SIZE = 2048
const DEFAULT_ISO_SIZE = 'nano'

export class MuxEngine {
  constructor (options = {}) {
    this.forge = options.forge || new ISOForge()
    this.defaultISOSize = options.isoSize || DEFAULT_ISO_SIZE
  }

  mux (plaintext, key, isoSeed, isoSize = this.defaultISOSize) {
    const targetSize = typeof isoSize === 'string' ? SIZE_PRESETS[isoSize] : isoSize
    const totalSectors = Math.floor(targetSize / SECTOR_SIZE)
    
    if (plaintext.length > targetSize) {
      throw new Error(`Plaintext (${plaintext.length} bytes) exceeds ISO size (${targetSize} bytes)`)
    }

    const encrypted = this._encrypt(plaintext, key)
    const paddedLength = Math.ceil(encrypted.length / SECTOR_SIZE) * SECTOR_SIZE
    const padded = Buffer.alloc(paddedLength)
    encrypted.copy(padded)
    
    const sectorsNeeded = paddedLength / SECTOR_SIZE
    const muxed = Buffer.alloc(paddedLength)
    
    for (let i = 0; i < sectorsNeeded; i++) {
      const isoSector = this.forge.generateSector(isoSeed, i, isoSize)
      const dataSector = padded.subarray(i * SECTOR_SIZE, (i + 1) * SECTOR_SIZE)
      const muxedSector = this._xorBuffers(isoSector, dataSector)
      muxedSector.copy(muxed, i * SECTOR_SIZE)
    }

    return {
      muxedData: muxed,
      manifest: {
        isoSeed: isoSeed.toString('hex'),
        isoSize,
        sectorsUsed: sectorsNeeded,
        originalSize: plaintext.length,
        encryptedSize: encrypted.length
      }
    }
  }

  demux (muxedData, key, manifest) {
    const isoSeed = Buffer.from(manifest.isoSeed, 'hex')
    const sectorsUsed = manifest.sectorsUsed
    
    const encrypted = Buffer.alloc(sectorsUsed * SECTOR_SIZE)
    
    for (let i = 0; i < sectorsUsed; i++) {
      const isoSector = this.forge.generateSector(isoSeed, i, manifest.isoSize)
      const muxedSector = muxedData.subarray(i * SECTOR_SIZE, (i + 1) * SECTOR_SIZE)
      const dataSector = this._xorBuffers(isoSector, muxedSector)
      dataSector.copy(encrypted, i * SECTOR_SIZE)
    }

    return this._decrypt(encrypted.subarray(0, manifest.encryptedSize), key)
  }

  extractISO (muxedData, manifest) {
    const isoSeed = Buffer.from(manifest.isoSeed, 'hex')
    return this.forge.generate(isoSeed, manifest.isoSize)
  }

  verifyAsISO (muxedData, manifest) {
    const isoSeed = Buffer.from(manifest.isoSeed, 'hex')
    const sectorsUsed = manifest.sectorsUsed
    
    for (let i = 0; i < Math.min(sectorsUsed, 20); i++) {
      const expectedSector = this.forge.generateSector(isoSeed, i, manifest.isoSize)
      const muxedSector = muxedData.subarray(i * SECTOR_SIZE, (i + 1) * SECTOR_SIZE)
      
      if (i < 16) {
        continue
      }
      
      if (i === 16) {
        if (muxedSector[1] !== 0x43 || muxedSector[2] !== 0x44) {
          return false
        }
      }
    }
    
    return true
  }

  _encrypt (plaintext, key) {
    const nonce = crypto.randomBytes(12)
    const cipher = crypto.createCipheriv('chacha20-poly1305', key, nonce, { authTagLength: 16 })
    const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()])
    const tag = cipher.getAuthTag()
    return Buffer.concat([nonce, tag, encrypted])
  }

  _decrypt (data, key) {
    const nonce = data.subarray(0, 12)
    const tag = data.subarray(12, 28)
    const encrypted = data.subarray(28)
    
    const decipher = crypto.createDecipheriv('chacha20-poly1305', key, nonce, { authTagLength: 16 })
    decipher.setAuthTag(tag)
    return Buffer.concat([decipher.update(encrypted), decipher.final()])
  }

  _xorBuffers (a, b) {
    const result = Buffer.alloc(a.length)
    for (let i = 0; i < a.length; i++) {
      result[i] = a[i] ^ b[i]
    }
    return result
  }
}

export function generateEncryptionKey () {
  return crypto.randomBytes(32)
}

export function generateISOSeed () {
  return crypto.randomBytes(32)
}

export default MuxEngine
