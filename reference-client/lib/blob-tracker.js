import { EventEmitter } from 'events'
import crypto from 'crypto'

const ANNOUNCE_INTERVAL_MS = 15 * 60 * 1000
const LOOKUP_TIMEOUT_MS = 10000
const MAX_PEERS_PER_BLOB = 50

export class BlobTracker extends EventEmitter {
  constructor (dht, options = {}) {
    super()
    this.dht = dht
    this.port = options.port || 6881
    this.announceInterval = options.announceInterval || ANNOUNCE_INTERVAL_MS
    
    this.announced = new Map()
    this._announceTimers = new Map()
    this._destroyed = false
  }

  _blobIdToInfoHash (blobId) {
    return crypto.createHash('sha1')
      .update(Buffer.from('supertorrent:blob:' + blobId))
      .digest()
  }

  announce (blobId) {
    if (this._destroyed) return
    
    const infoHash = this._blobIdToInfoHash(blobId)
    
    this.dht.announce(infoHash, this.port, (err) => {
      if (err) {
        this.emit('error', { blobId, error: err })
        return
      }
      
      this.announced.set(blobId, {
        infoHash: infoHash.toString('hex'),
        announcedAt: Date.now(),
        port: this.port
      })
      
      this.emit('announced', { blobId })
    })
    
    this._scheduleReannounce(blobId)
  }

  _scheduleReannounce (blobId) {
    if (this._announceTimers.has(blobId)) {
      clearTimeout(this._announceTimers.get(blobId))
    }
    
    const timer = setTimeout(() => {
      if (!this._destroyed && this.announced.has(blobId)) {
        this.announce(blobId)
      }
    }, this.announceInterval)
    
    this._announceTimers.set(blobId, timer)
  }

  unannounce (blobId) {
    this.announced.delete(blobId)
    
    if (this._announceTimers.has(blobId)) {
      clearTimeout(this._announceTimers.get(blobId))
      this._announceTimers.delete(blobId)
    }
  }

  async lookup (blobId) {
    return new Promise((resolve) => {
      const infoHash = this._blobIdToInfoHash(blobId)
      const peers = []
      const seen = new Set()
      
      const timeout = setTimeout(() => {
        cleanup()
        resolve(peers)
      }, LOOKUP_TIMEOUT_MS)
      
      const onPeer = (peer, ih) => {
        if (ih.toString('hex') !== infoHash.toString('hex')) return
        
        const addr = `${peer.host}:${peer.port}`
        if (seen.has(addr)) return
        seen.add(addr)
        
        peers.push({
          host: peer.host,
          port: peer.port,
          address: `ws://${peer.host}:${peer.port}`
        })
        
        if (peers.length >= MAX_PEERS_PER_BLOB) {
          cleanup()
          resolve(peers)
        }
      }
      
      const cleanup = () => {
        clearTimeout(timeout)
        this.dht.removeListener('peer', onPeer)
      }
      
      this.dht.on('peer', onPeer)
      this.dht.lookup(infoHash)
    })
  }

  announceAll (blobIds) {
    for (const blobId of blobIds) {
      this.announce(blobId)
    }
  }

  getAnnounced () {
    return Array.from(this.announced.entries()).map(([blobId, meta]) => ({
      blobId,
      ...meta
    }))
  }

  destroy () {
    this._destroyed = true
    
    for (const timer of this._announceTimers.values()) {
      clearTimeout(timer)
    }
    this._announceTimers.clear()
    this.announced.clear()
    
    this.emit('destroyed')
  }
}

export default BlobTracker
