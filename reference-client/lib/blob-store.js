import fs from 'fs'
import path from 'path'
import crypto from 'crypto'
import { EventEmitter } from 'events'

const DEFAULT_MAX_SIZE = 1024 * 1024 * 1024 * 10

export class BlobStore extends EventEmitter {
  constructor (storageDir, options = {}) {
    super()
    this.storageDir = storageDir
    this.maxSize = options.maxSize || DEFAULT_MAX_SIZE
    this.currentSize = 0
    this.index = new Map()
    this._ensureDir()
    this._loadIndex()
  }

  _ensureDir () {
    if (!fs.existsSync(this.storageDir)) {
      fs.mkdirSync(this.storageDir, { recursive: true })
    }
  }

  _blobPath (blobId) {
    const prefix = blobId.slice(0, 2)
    const dir = path.join(this.storageDir, prefix)
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true })
    }
    return path.join(dir, blobId)
  }

  _loadIndex () {
    const indexPath = path.join(this.storageDir, 'index.json')
    if (fs.existsSync(indexPath)) {
      try {
        const data = JSON.parse(fs.readFileSync(indexPath, 'utf-8'))
        this.index = new Map(Object.entries(data.blobs || {}))
        this.currentSize = data.currentSize || 0
      } catch {
        this.index = new Map()
        this.currentSize = 0
      }
    }
    this._reconcileIndex()
  }

  _reconcileIndex () {
    for (const [blobId, meta] of this.index) {
      const blobPath = this._blobPath(blobId)
      if (!fs.existsSync(blobPath)) {
        this.index.delete(blobId)
        this.currentSize -= meta.size
      }
    }
    this._saveIndex()
  }

  _saveIndex () {
    const indexPath = path.join(this.storageDir, 'index.json')
    const data = {
      currentSize: this.currentSize,
      blobs: Object.fromEntries(this.index)
    }
    fs.writeFileSync(indexPath, JSON.stringify(data))
  }

  has (blobId) {
    return this.index.has(blobId)
  }

  get (blobId) {
    if (!this.has(blobId)) return null
    
    const blobPath = this._blobPath(blobId)
    if (!fs.existsSync(blobPath)) {
      this.index.delete(blobId)
      this._saveIndex()
      return null
    }
    
    const meta = this.index.get(blobId)
    meta.lastAccess = Date.now()
    this.index.set(blobId, meta)
    
    return fs.readFileSync(blobPath)
  }

  put (blobId, buffer) {
    const hash = crypto.createHash('sha256').update(buffer).digest('hex')
    if (hash !== blobId) {
      throw new Error(`Blob hash mismatch: expected ${blobId}, got ${hash}`)
    }
    
    if (this.has(blobId)) {
      return false
    }
    
    while (this.currentSize + buffer.length > this.maxSize) {
      if (!this._evictOne()) break
    }
    
    const blobPath = this._blobPath(blobId)
    fs.writeFileSync(blobPath, buffer)
    
    this.index.set(blobId, {
      size: buffer.length,
      addedAt: Date.now(),
      lastAccess: Date.now()
    })
    this.currentSize += buffer.length
    this._saveIndex()
    
    this.emit('added', { blobId, size: buffer.length })
    return true
  }

  _evictOne () {
    let oldest = null
    let oldestTime = Infinity
    
    for (const [blobId, meta] of this.index) {
      if (meta.pinned) continue
      if (meta.lastAccess < oldestTime) {
        oldestTime = meta.lastAccess
        oldest = blobId
      }
    }
    
    if (!oldest) return false
    
    this.delete(oldest)
    return true
  }

  delete (blobId) {
    if (!this.has(blobId)) return false
    
    const meta = this.index.get(blobId)
    const blobPath = this._blobPath(blobId)
    
    if (fs.existsSync(blobPath)) {
      fs.unlinkSync(blobPath)
    }
    
    this.currentSize -= meta.size
    this.index.delete(blobId)
    this._saveIndex()
    
    this.emit('removed', { blobId })
    return true
  }

  pin (blobId) {
    const meta = this.index.get(blobId)
    if (meta) {
      meta.pinned = true
      this.index.set(blobId, meta)
      this._saveIndex()
    }
  }

  unpin (blobId) {
    const meta = this.index.get(blobId)
    if (meta) {
      meta.pinned = false
      this.index.set(blobId, meta)
      this._saveIndex()
    }
  }

  list () {
    return Array.from(this.index.entries()).map(([blobId, meta]) => ({
      blobId,
      ...meta
    }))
  }

  stats () {
    return {
      blobCount: this.index.size,
      currentSize: this.currentSize,
      maxSize: this.maxSize,
      utilization: this.currentSize / this.maxSize
    }
  }
}

export default BlobStore
