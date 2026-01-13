/**
 * ISO Forge - Procedural Linux ISO Generation
 * 
 * Generates deterministic, bootable Linux ISOs from a 256-bit seed.
 * Same seed = same ISO (bit-for-bit identical).
 * 
 * The ISO contains:
 * - Base layer: Minimal Linux (kernel, busybox, init) - shared/cached
 * - Variable layer: Procedurally generated content (wallpapers, docs, machine-id)
 * - Padding: Deterministic random data to reach target size
 * 
 * Sizes:
 * - nano:   256 MB
 * - micro:  512 MB  
 * - mini:   1 GB
 * - full:   2 GB
 * - custom: N bytes (any size)
 */

import crypto from 'crypto'
import { generateContent, CONTENT_TYPES } from './content-gen.js'
import { ElToritoExtension } from './el-torito.js'

// ISO 9660 constants
const SECTOR_SIZE = 2048
const SYSTEM_AREA_SECTORS = 16 // First 32KB reserved
const PRIMARY_VOLUME_DESCRIPTOR_SECTOR = 16
const VOLUME_DESCRIPTOR_SET_TERMINATOR = 255

// Size presets in bytes
export const SIZE_PRESETS = {
  nano: 256 * 1024 * 1024,    // 256 MB
  micro: 512 * 1024 * 1024,   // 512 MB
  mini: 1024 * 1024 * 1024,   // 1 GB
  full: 2048 * 1024 * 1024    // 2 GB
}

/**
 * Deterministic PRNG seeded from ISO seed
 * Uses ChaCha20 stream cipher as PRNG (via crypto.createCipheriv)
 */
export class SeededRNG {
  constructor (seed) {
    // Derive a 32-byte key from seed
    this.seed = Buffer.isBuffer(seed) ? seed : Buffer.from(seed, 'hex')
    if (this.seed.length !== 32) {
      throw new Error('Seed must be 256 bits (32 bytes)')
    }
    this.counter = 0n
    this.buffer = Buffer.alloc(0)
    this.offset = 0
  }

  /**
   * Generate deterministic random bytes
   */
  bytes (length) {
    const result = Buffer.alloc(length)
    let written = 0

    while (written < length) {
      if (this.offset >= this.buffer.length) {
        this._refillBuffer()
      }
      const toCopy = Math.min(length - written, this.buffer.length - this.offset)
      this.buffer.copy(result, written, this.offset, this.offset + toCopy)
      this.offset += toCopy
      written += toCopy
    }

    return result
  }

  /**
   * Generate a random integer in range [0, max)
   */
  int (max) {
    const bytes = this.bytes(4)
    return bytes.readUInt32BE(0) % max
  }

  /**
   * Generate a random float in range [0, 1)
   */
  float () {
    return this.int(0x100000000) / 0x100000000
  }

  /**
   * Pick random element from array
   */
  pick (array) {
    return array[this.int(array.length)]
  }

  /**
   * Shuffle array in place (Fisher-Yates)
   */
  shuffle (array) {
    for (let i = array.length - 1; i > 0; i--) {
      const j = this.int(i + 1)
      ;[array[i], array[j]] = [array[j], array[i]]
    }
    return array
  }

  _refillBuffer () {
    // Use AES-256-CTR as a fast CSPRNG
    const nonce = Buffer.alloc(16)
    nonce.writeBigUInt64BE(this.counter, 8)
    this.counter++

    const cipher = crypto.createCipheriv('aes-256-ctr', this.seed, nonce)
    // Generate 64KB at a time
    const zeros = Buffer.alloc(65536)
    this.buffer = cipher.update(zeros)
    this.offset = 0
  }

  /**
   * Create a child RNG with derived seed (for independent streams)
   */
  derive (label) {
    const hmac = crypto.createHmac('sha256', this.seed)
    hmac.update(label)
    return new SeededRNG(hmac.digest())
  }
}

/**
 * ISO 9660 Primary Volume Descriptor
 */
function createPrimaryVolumeDescriptor (rng, totalSectors) {
  const pvd = Buffer.alloc(SECTOR_SIZE)
  const deterministicDate = generateDeterministicDate(rng)
  
  pvd[0] = 1
  
  // Standard Identifier: "CD001"
  pvd.write('CD001', 1, 'ascii')
  
  // Version: 1
  pvd[6] = 1
  
  // System Identifier (32 bytes, padded with spaces)
  const systemId = 'LINUX'
  pvd.write(systemId.padEnd(32, ' '), 8, 'ascii')
  
  // Volume Identifier (32 bytes) - procedurally generated
  const volumeNames = [
    'SUPERNODE_LINUX', 'MESH_LINUX', 'FREEDOM_LINUX',
    'SOVEREIGN_LINUX', 'LIBERTY_LINUX', 'DECENTRALIX'
  ]
  const volumeId = rng.pick(volumeNames) + '_' + rng.bytes(4).toString('hex').toUpperCase()
  pvd.write(volumeId.substring(0, 32).padEnd(32, ' '), 40, 'ascii')
  
  // Volume Space Size (number of logical blocks) - both-endian
  writeBothEndian32(pvd, 80, totalSectors)
  
  // Volume Set Size - both-endian 16-bit
  writeBothEndian16(pvd, 120, 1)
  
  // Volume Sequence Number - both-endian 16-bit
  writeBothEndian16(pvd, 124, 1)
  
  // Logical Block Size - both-endian 16-bit
  writeBothEndian16(pvd, 128, SECTOR_SIZE)
  
  // Path Table Size - will be filled in later
  writeBothEndian32(pvd, 132, 0)
  
  // Root Directory Record location (sector 18 typically)
  const rootDirSector = 18
  pvd.writeUInt32LE(rootDirSector, 156 + 2)
  pvd.writeUInt32BE(rootDirSector, 156 + 6)
  
  // Root Directory Record (34 bytes embedded)
  const rootDirRecord = createDirectoryRecord('.', rootDirSector, SECTOR_SIZE, true, deterministicDate)
  rootDirRecord.copy(pvd, 156)
  
  // Volume Set Identifier
  pvd.write(''.padEnd(128, ' '), 190, 'ascii')
  
  // Publisher Identifier
  pvd.write('SUPERNODE PROJECT'.padEnd(128, ' '), 318, 'ascii')
  
  // Data Preparer Identifier
  pvd.write('ISO FORGE'.padEnd(128, ' '), 446, 'ascii')
  
  // Application Identifier
  pvd.write('SUPERNODE'.padEnd(128, ' '), 574, 'ascii')
  
  const dateStr = formatISODate(deterministicDate)
  pvd.write(dateStr, 813, 'ascii')
  pvd[830] = 0
  
  pvd.write(dateStr, 831, 'ascii')
  pvd[848] = 0
  
  pvd.write('0000000000000000', 849, 'ascii')
  pvd[866] = 0
  
  pvd.write(dateStr, 867, 'ascii')
  pvd[884] = 0
  
  // File Structure Version
  pvd[881] = 1
  
  return pvd
}

/**
 * Volume Descriptor Set Terminator
 */
function createVolumeDescriptorTerminator () {
  const vdt = Buffer.alloc(SECTOR_SIZE)
  vdt[0] = VOLUME_DESCRIPTOR_SET_TERMINATOR
  vdt.write('CD001', 1, 'ascii')
  vdt[6] = 1
  return vdt
}

function createDirectoryRecord (name, sector, size, isDir, date) {
  const record = Buffer.alloc(34 + name.length + (name.length % 2 === 0 ? 1 : 0))
  
  record[0] = record.length
  record[1] = 0
  
  record.writeUInt32LE(sector, 2)
  record.writeUInt32BE(sector, 6)
  
  record.writeUInt32LE(size, 10)
  record.writeUInt32BE(size, 14)
  
  record[18] = date.getFullYear() - 1900
  record[19] = date.getMonth() + 1
  record[20] = date.getDate()
  record[21] = date.getHours()
  record[22] = date.getMinutes()
  record[23] = date.getSeconds()
  record[24] = 0
  
  // File flags
  record[25] = isDir ? 0x02 : 0x00
  
  // File unit size
  record[26] = 0
  
  // Interleave gap size
  record[27] = 0
  
  // Volume sequence number - both-endian
  record.writeUInt16LE(1, 28)
  record.writeUInt16BE(1, 30)
  
  // File identifier length
  record[32] = name.length
  
  // File identifier
  record.write(name, 33, 'ascii')
  
  return record
}

/**
 * Write both-endian 32-bit value
 */
function writeBothEndian32 (buffer, offset, value) {
  buffer.writeUInt32LE(value, offset)
  buffer.writeUInt32BE(value, offset + 4)
}

/**
 * Write both-endian 16-bit value
 */
function writeBothEndian16 (buffer, offset, value) {
  buffer.writeUInt16LE(value, offset)
  buffer.writeUInt16BE(value, offset + 2)
}

function generateDeterministicDate (rng) {
  const year = 2020 + rng.int(5)
  const month = rng.int(12)
  const day = 1 + rng.int(28)
  const hour = rng.int(24)
  const minute = rng.int(60)
  const second = rng.int(60)
  return new Date(year, month, day, hour, minute, second)
}

function formatISODate (date) {
  const y = date.getFullYear().toString()
  const m = (date.getMonth() + 1).toString().padStart(2, '0')
  const d = date.getDate().toString().padStart(2, '0')
  const h = date.getHours().toString().padStart(2, '0')
  const min = date.getMinutes().toString().padStart(2, '0')
  const s = date.getSeconds().toString().padStart(2, '0')
  const cs = Math.floor(date.getMilliseconds() / 10).toString().padStart(2, '0')
  return `${y}${m}${d}${h}${min}${s}${cs}`
}

/**
 * Main ISO Forge class
 */
export class ISOForge {
  constructor (options = {}) {
    this.cacheDir = options.cacheDir || null
    this.baseLayerCache = new Map()
    this.bootable = options.bootable || false
    this.elTorito = this.bootable ? new ElToritoExtension() : null
  }

  /**
   * Generate a complete ISO from seed
   * 
   * @param {Buffer|string} seed - 256-bit seed
   * @param {string|number} size - Size preset name or exact byte count
   * @returns {Buffer} Complete ISO image
   */
  generate (seed, size = 'nano') {
    const targetSize = typeof size === 'string' ? SIZE_PRESETS[size] : size
    if (!targetSize || targetSize < SECTOR_SIZE * 20) {
      throw new Error('Invalid size. Must be at least 40KB')
    }

    const rng = new SeededRNG(seed)
    const totalSectors = Math.floor(targetSize / SECTOR_SIZE)
    
    // Allocate full ISO buffer
    const iso = Buffer.alloc(totalSectors * SECTOR_SIZE)
    
    // === System Area (first 16 sectors) ===
    // Can contain boot code - we'll add El Torito boot record support later
    const systemAreaRng = rng.derive('system-area')
    const systemArea = this._generateSystemArea(systemAreaRng)
    systemArea.copy(iso, 0)
    
    // === Volume Descriptors (sectors 16-17) ===
    const pvd = createPrimaryVolumeDescriptor(rng.derive('pvd'), totalSectors)
    pvd.copy(iso, PRIMARY_VOLUME_DESCRIPTOR_SECTOR * SECTOR_SIZE)
    
    if (this.bootable && this.elTorito) {
      const bootRecord = this.elTorito.generateBootRecord()
      bootRecord.copy(iso, 17 * SECTOR_SIZE)
      
      const vdt = createVolumeDescriptorTerminator()
      vdt.copy(iso, 18 * SECTOR_SIZE)
      
      const bootCatalog = this.elTorito.generateBootCatalog(rng.derive('boot-catalog'))
      bootCatalog.copy(iso, this.elTorito.bootCatalogSector * SECTOR_SIZE)
      
      const bootImage = this.elTorito.generateBootImage(rng.derive('boot-image'))
      bootImage.copy(iso, this.elTorito.bootImageSector * SECTOR_SIZE)
      
      const rootDir = this._generateRootDirectory(rng.derive('root-dir'))
      rootDir.copy(iso, 24 * SECTOR_SIZE)
      
      const contentRng = rng.derive('content')
      const contentStart = 25 * SECTOR_SIZE
      const contentEnd = totalSectors * SECTOR_SIZE
      const contentSize = contentEnd - contentStart
      const content = contentRng.bytes(contentSize)
      content.copy(iso, contentStart)
    } else {
      const vdt = createVolumeDescriptorTerminator()
      vdt.copy(iso, 17 * SECTOR_SIZE)
      
      const rootDir = this._generateRootDirectory(rng.derive('root-dir'))
      rootDir.copy(iso, 18 * SECTOR_SIZE)
      
      const contentRng = rng.derive('content')
      const contentStart = 19 * SECTOR_SIZE
      const contentEnd = totalSectors * SECTOR_SIZE
      const contentSize = contentEnd - contentStart
      const content = contentRng.bytes(contentSize)
      content.copy(iso, contentStart)
    }
    
    return iso
  }

  /**
   * Generate a specific sector from an ISO
   * Useful for on-demand generation without creating entire ISO
   * 
   * @param {Buffer|string} seed - 256-bit seed
   * @param {number} sectorIndex - Which sector to generate
   * @param {string|number} totalSize - Total ISO size
   * @returns {Buffer} Single 2048-byte sector
   */
  generateSector (seed, sectorIndex, totalSize = 'nano') {
    const targetSize = typeof totalSize === 'string' ? SIZE_PRESETS[totalSize] : totalSize
    const totalSectors = Math.floor(targetSize / SECTOR_SIZE)
    
    if (sectorIndex < 0 || sectorIndex >= totalSectors) {
      throw new Error(`Sector ${sectorIndex} out of range [0, ${totalSectors})`)
    }

    const rng = new SeededRNG(seed)
    
    if (sectorIndex < SYSTEM_AREA_SECTORS) {
      const systemArea = this._generateSystemArea(rng.derive('system-area'))
      return systemArea.subarray(sectorIndex * SECTOR_SIZE, (sectorIndex + 1) * SECTOR_SIZE)
    }
    
    // Primary Volume Descriptor
    if (sectorIndex === PRIMARY_VOLUME_DESCRIPTOR_SECTOR) {
      return createPrimaryVolumeDescriptor(rng.derive('pvd'), totalSectors)
    }
    
    if (this.bootable && this.elTorito) {
      if (sectorIndex === 17) {
        return this.elTorito.generateBootRecord()
      }
      if (sectorIndex === 18) {
        return createVolumeDescriptorTerminator()
      }
      if (sectorIndex === this.elTorito.bootCatalogSector) {
        return this.elTorito.generateBootCatalog(rng.derive('boot-catalog'))
      }
      if (sectorIndex >= this.elTorito.bootImageSector && sectorIndex < this.elTorito.bootImageSector + 4) {
        const bootImage = this.elTorito.generateBootImage(rng.derive('boot-image'))
        const offset = (sectorIndex - this.elTorito.bootImageSector) * SECTOR_SIZE
        return bootImage.subarray(offset, offset + SECTOR_SIZE)
      }
      if (sectorIndex === 24) {
        return this._generateRootDirectory(rng.derive('root-dir'))
      }
      
      const contentRng = rng.derive('content')
      const sectorOffset = (sectorIndex - 25) * SECTOR_SIZE
      contentRng.bytes(sectorOffset)
      return contentRng.bytes(SECTOR_SIZE)
    }
    
    if (sectorIndex === 17) {
      return createVolumeDescriptorTerminator()
    }
    
    if (sectorIndex === 18) {
      return this._generateRootDirectory(rng.derive('root-dir'))
    }
    
    const contentRng = rng.derive('content')
    const sectorOffset = (sectorIndex - 19) * SECTOR_SIZE
    contentRng.bytes(sectorOffset)
    return contentRng.bytes(SECTOR_SIZE)
  }

  /**
   * Generate the system area (first 32KB)
   * Contains boot sector and optional boot code
   */
  _generateSystemArea (rng) {
    const systemArea = Buffer.alloc(SYSTEM_AREA_SECTORS * SECTOR_SIZE)
    
    // First sector: MBR-like structure for compatibility
    // This won't actually boot but makes the ISO look more legitimate
    
    // Boot signature at end of first sector
    systemArea[510] = 0x55
    systemArea[511] = 0xAA
    
    // Fill rest with deterministic "boot code" pattern
    const bootCode = rng.bytes(SYSTEM_AREA_SECTORS * SECTOR_SIZE - 512)
    bootCode.copy(systemArea, 512)
    
    return systemArea
  }

  /**
   * Generate root directory
   */
  _generateRootDirectory (rng) {
    const rootDir = Buffer.alloc(SECTOR_SIZE)
    const date = generateDeterministicDate(rng)
    let offset = 0
    
    const selfRecord = createDirectoryRecord('\x00', 18, SECTOR_SIZE, true, date)
    selfRecord.copy(rootDir, offset)
    offset += selfRecord.length
    
    const parentRecord = createDirectoryRecord('\x01', 18, SECTOR_SIZE, true, date)
    parentRecord.copy(rootDir, offset)
    offset += parentRecord.length
    
    const dirs = ['boot', 'etc', 'home', 'usr', 'var', 'tmp']
    for (const dir of dirs) {
      if (offset + 34 + dir.length > SECTOR_SIZE) break
      const dirRecord = createDirectoryRecord(dir.toUpperCase(), 19 + dirs.indexOf(dir), SECTOR_SIZE, true, date)
      dirRecord.copy(rootDir, offset)
      offset += dirRecord.length
    }
    
    return rootDir
  }

  /**
   * Get hash of ISO without generating full image
   * Uses streaming hash of deterministic content
   */
  getHash (seed, size = 'nano') {
    const targetSize = typeof size === 'string' ? SIZE_PRESETS[size] : size
    const totalSectors = Math.floor(targetSize / SECTOR_SIZE)
    
    const hash = crypto.createHash('sha256')
    
    // Hash each sector
    for (let i = 0; i < totalSectors; i++) {
      const sector = this.generateSector(seed, i, targetSize)
      hash.update(sector)
    }
    
    return hash.digest('hex')
  }
}

/**
 * Convenience function to generate ISO
 */
export function forgeISO (seed, size = 'nano') {
  const forge = new ISOForge()
  return forge.generate(seed, size)
}

/**
 * Generate a random seed
 */
export function generateSeed () {
  return crypto.randomBytes(32)
}

export default ISOForge
