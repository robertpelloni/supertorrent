import crypto from 'crypto'
import { EventEmitter } from 'events'
import { MuxEngine, generateEncryptionKey, generateISOSeed } from './mux/index.js'
import { createManifest, encryptManifest, decryptManifest, deriveManifestKey } from './mux/manifest.js'
import { ISOForge, SIZE_PRESETS, generateSeed as generateForeSeed } from './iso-forge/index.js'
import { ErasureCoder } from './erasure/coder.js'

const CHUNK_SIZE = 1024 * 1024

export class SupernodeStorage extends EventEmitter {
  constructor (blobStore, options = {}) {
    super()
    this.blobStore = blobStore
    this.mux = new MuxEngine()
    this.forge = new ISOForge()
    this.isoSize = options.isoSize || 'nano'
    this.manifestStore = new Map()
    
    this.enableErasure = options.enableErasure || false
    this.erasureCoder = null
    if (this.enableErasure) {
      this.erasureCoder = new ErasureCoder({
        dataShards: options.dataShards || 4,
        parityShards: options.parityShards || 2
      })
    }
  }

  async ingest (fileBuffer, fileName, masterKey) {
    const fileId = 'sha256:' + crypto.createHash('sha256').update(fileBuffer).digest('hex')
    const segments = []
    const chunkHashes = []
    
    let offset = 0
    while (offset < fileBuffer.length) {
      const end = Math.min(offset + CHUNK_SIZE, fileBuffer.length)
      const chunk = fileBuffer.subarray(offset, end)
      
      const chunkKey = generateEncryptionKey()
      const isoSeed = generateISOSeed()
      
      const { muxedData, manifest: muxManifest } = this.mux.mux(chunk, chunkKey, isoSeed, this.isoSize)
      
      let segmentData
      if (this.enableErasure) {
        segmentData = await this._ingestWithErasure(muxedData, chunkKey, isoSeed, muxManifest, chunk.length)
        chunkHashes.push(...segmentData.shardHashes)
      } else {
        const chunkHash = crypto.createHash('sha256').update(muxedData).digest('hex')
        this.blobStore.put(chunkHash, muxedData)
        chunkHashes.push(chunkHash)
        segmentData = {
          chunkHash,
          chunkKey: chunkKey.toString('hex'),
          isoSeed: isoSeed.toString('hex'),
          sectorStart: 0,
          sectorCount: muxManifest.sectorsUsed,
          encryptedSize: muxManifest.encryptedSize,
          originalSize: chunk.length
        }
      }
      
      segments.push(segmentData)
      
      offset = end
      this.emit('chunk-ingested', { fileId, chunkHash: segmentData.chunkHash || segmentData.shardHashes[0], progress: offset / fileBuffer.length })
    }

    const manifest = createManifest({
      fileId,
      fileName,
      fileSize: fileBuffer.length,
      isoSeed: segments[0].isoSeed,
      isoSize: this.isoSize,
      erasure: this.enableErasure ? {
        dataShards: this.erasureCoder.dataShards,
        parityShards: this.erasureCoder.parityShards
      } : null,
      segments: segments.map(s => ({
        chunkHash: s.chunkHash,
        chunkKey: s.chunkKey,
        isoSeed: s.isoSeed,
        sectorStart: s.sectorStart,
        sectorCount: s.sectorCount,
        encryptedSize: s.encryptedSize,
        originalSize: s.originalSize,
        shards: s.shards,
        muxedSize: s.muxedSize,
        shardSize: s.shardSize
      }))
    })
    
    const manifestKey = deriveManifestKey(masterKey, fileId)
    const encryptedManifest = encryptManifest(manifest, manifestKey)
    
    this.manifestStore.set(fileId, encryptedManifest)
    
    this.emit('file-ingested', { fileId, fileName, size: fileBuffer.length, chunks: chunkHashes.length })
    
    return {
      fileId,
      chunkHashes,
      encryptedManifest
    }
  }

  async _ingestWithErasure (muxedData, chunkKey, isoSeed, muxManifest, originalSize) {
    const encoded = this.erasureCoder.encode(muxedData)
    const shards = []
    const shardHashes = []
    
    for (let i = 0; i < encoded.shards.length; i++) {
      const shard = encoded.shards[i]
      const shardHash = crypto.createHash('sha256').update(shard).digest('hex')
      this.blobStore.put(shardHash, shard)
      shards.push({
        index: i,
        hash: shardHash,
        size: shard.length
      })
      shardHashes.push(shardHash)
    }
    
    this.emit('erasure-encoded', {
      dataShards: this.erasureCoder.dataShards,
      parityShards: this.erasureCoder.parityShards,
      shardCount: shards.length
    })
    
    return {
      chunkKey: chunkKey.toString('hex'),
      isoSeed: isoSeed.toString('hex'),
      sectorStart: 0,
      sectorCount: muxManifest.sectorsUsed,
      encryptedSize: muxManifest.encryptedSize,
      originalSize,
      muxedSize: muxedData.length,
      shardSize: encoded.shardSize,
      shards,
      shardHashes
    }
  }

  async retrieve (fileId, masterKey) {
    const encryptedManifest = this.manifestStore.get(fileId)
    if (!encryptedManifest) {
      throw new Error(`Manifest not found for file: ${fileId}`)
    }
    
    const manifestKey = deriveManifestKey(masterKey, fileId)
    const manifest = decryptManifest(encryptedManifest, manifestKey)
    
    const parts = []
    
    for (let i = 0; i < manifest.segments.length; i++) {
      const segment = manifest.segments[i]
      
      let muxedData
      if (segment.shards && segment.shards.length > 0) {
        muxedData = await this._retrieveWithErasure(segment, manifest.erasure)
      } else {
        muxedData = this.blobStore.get(segment.chunkHash)
        if (!muxedData) {
          throw new Error(`Chunk not found: ${segment.chunkHash}`)
        }
      }
      
      const chunkKey = Buffer.from(segment.chunkKey, 'hex')
      const demuxManifest = {
        isoSeed: segment.isoSeed,
        isoSize: this.isoSize,
        sectorsUsed: segment.sectorCount,
        encryptedSize: segment.encryptedSize
      }
      
      const chunk = this.mux.demux(muxedData, chunkKey, demuxManifest)
      parts.push(chunk)
      
      this.emit('chunk-retrieved', { fileId, chunkHash: segment.chunkHash || segment.shards[0].hash, progress: (i + 1) / manifest.segments.length })
    }
    
    const fileBuffer = Buffer.concat(parts)
    this.emit('file-retrieved', { fileId, fileName: manifest.fileName, size: fileBuffer.length })
    
    return {
      fileName: manifest.fileName,
      fileSize: manifest.fileSize,
      data: fileBuffer
    }
  }

  async _retrieveWithErasure (segment, erasureConfig) {
    const coder = new ErasureCoder({
      dataShards: erasureConfig.dataShards,
      parityShards: erasureConfig.parityShards
    })
    
    const shards = []
    const presentIndices = []
    
    for (const shardInfo of segment.shards) {
      const shardData = this.blobStore.get(shardInfo.hash)
      if (shardData) {
        shards[shardInfo.index] = shardData
        presentIndices.push(shardInfo.index)
      }
    }
    
    if (presentIndices.length < coder.dataShards) {
      throw new Error(`Not enough shards available. Need ${coder.dataShards}, have ${presentIndices.length}`)
    }
    
    const muxedData = coder.decode(shards, presentIndices, segment.muxedSize, segment.shardSize)
    
    this.emit('erasure-decoded', {
      presentShards: presentIndices.length,
      totalShards: segment.shards.length,
      recovered: presentIndices.length < segment.shards.length
    })
    
    return muxedData
  }

  extractChunkAsISO (chunkHash, manifest) {
    const muxedData = this.blobStore.get(chunkHash)
    if (!muxedData) {
      throw new Error(`Chunk not found: ${chunkHash}`)
    }
    
    const segment = manifest.segments?.find(s => s.chunkHash === chunkHash)
    if (!segment) {
      throw new Error('Segment metadata not found')
    }
    
    const isoSeed = Buffer.from(segment.isoSeed, 'hex')
    return this.forge.generate(isoSeed, this.isoSize)
  }

  verifyChunkAsISO (chunkHash) {
    const muxedData = this.blobStore.get(chunkHash)
    if (!muxedData) return false
    
    if (muxedData.length < 2048) return false
    
    return true
  }

  getManifest (fileId) {
    return this.manifestStore.get(fileId)
  }

  storeManifest (fileId, encryptedManifest) {
    this.manifestStore.set(fileId, encryptedManifest)
  }

  stats () {
    const result = {
      ...this.blobStore.stats(),
      manifestCount: this.manifestStore.size,
      isoSize: this.isoSize
    }
    
    if (this.enableErasure) {
      result.erasure = this.erasureCoder.getStats()
    }
    
    return result
  }
}

export function createSupernodeStorage (blobStore, options = {}) {
  return new SupernodeStorage(blobStore, options)
}

export default SupernodeStorage
