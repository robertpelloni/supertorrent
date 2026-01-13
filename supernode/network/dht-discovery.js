import { EventEmitter } from 'events'
import DHT from 'bittorrent-dht'
import crypto from 'crypto'

const DEFAULT_BOOTSTRAP = [
  'router.bittorrent.com:6881',
  'router.utorrent.com:6881',
  'dht.transmissionbt.com:6881'
]

export class DHTDiscovery extends EventEmitter {
  constructor (options = {}) {
    super()
    this.nodeId = options.nodeId || crypto.randomBytes(20)
    this.bootstrap = options.bootstrap || DEFAULT_BOOTSTRAP
    this.port = options.port || 0
    
    this.dht = null
    this.announceInterval = options.announceInterval || 15 * 60 * 1000
    this.announcedHashes = new Map()
    this.lookupCallbacks = new Map()
    this._destroyed = false
  }

  async start () {
    return new Promise((resolve, reject) => {
      this.dht = new DHT({
        nodeId: this.nodeId,
        bootstrap: this.bootstrap
      })

      this.dht.on('ready', () => {
        this.emit('ready')
        resolve()
      })

      this.dht.on('error', (err) => {
        this.emit('error', err)
        if (!this.dht.ready) reject(err)
      })

      this.dht.on('peer', (peer, infoHash, from) => {
        const hashHex = infoHash.toString('hex')
        this.emit('peer', {
          address: peer.host,
          port: peer.port,
          infoHash: hashHex,
          from: from ? `${from.address}:${from.port}` : null
        })

        const callbacks = this.lookupCallbacks.get(hashHex)
        if (callbacks) {
          for (const cb of callbacks) {
            cb({ host: peer.host, port: peer.port })
          }
        }
      })

      this.dht.on('announce', (peer, infoHash) => {
        this.emit('announce', {
          address: peer.host,
          port: peer.port,
          infoHash: infoHash.toString('hex')
        })
      })

      this.dht.on('warning', (err) => {
        this.emit('warning', err)
      })

      this.dht.listen(this.port, () => {
        this.port = this.dht.address().port
        this.emit('listening', { port: this.port })
      })
    })
  }

  blobIdToInfoHash (blobId) {
    const hash = crypto.createHash('sha1').update(blobId).digest()
    return hash
  }

  announce (blobId, wsPort) {
    if (this._destroyed || !this.dht) return

    const infoHash = this.blobIdToInfoHash(blobId)
    const hashHex = infoHash.toString('hex')

    this.dht.announce(infoHash, wsPort, (err) => {
      if (err) {
        this.emit('announce-error', { blobId, error: err.message })
      } else {
        this.emit('announced', { blobId, infoHash: hashHex })
      }
    })

    if (!this.announcedHashes.has(hashHex)) {
      const timer = setInterval(() => {
        if (!this._destroyed && this.dht) {
          this.dht.announce(infoHash, wsPort)
        }
      }, this.announceInterval)

      this.announcedHashes.set(hashHex, {
        blobId,
        timer,
        wsPort
      })
    }
  }

  unannounce (blobId) {
    const infoHash = this.blobIdToInfoHash(blobId)
    const hashHex = infoHash.toString('hex')

    const entry = this.announcedHashes.get(hashHex)
    if (entry) {
      clearInterval(entry.timer)
      this.announcedHashes.delete(hashHex)
    }
  }

  lookup (blobId, callback) {
    if (this._destroyed || !this.dht) return

    const infoHash = this.blobIdToInfoHash(blobId)
    const hashHex = infoHash.toString('hex')

    if (callback) {
      if (!this.lookupCallbacks.has(hashHex)) {
        this.lookupCallbacks.set(hashHex, new Set())
      }
      this.lookupCallbacks.get(hashHex).add(callback)
    }

    this.dht.lookup(infoHash, (err, peersFound) => {
      if (callback) {
        const callbacks = this.lookupCallbacks.get(hashHex)
        if (callbacks) {
          callbacks.delete(callback)
          if (callbacks.size === 0) {
            this.lookupCallbacks.delete(hashHex)
          }
        }
      }

      if (err) {
        this.emit('lookup-error', { blobId, error: err.message })
      } else {
        this.emit('lookup-complete', { blobId, peersFound })
      }
    })
  }

  async findPeers (blobId, timeout = 10000) {
    return new Promise((resolve) => {
      const peers = []
      const infoHash = this.blobIdToInfoHash(blobId)
      const hashHex = infoHash.toString('hex')

      const timer = setTimeout(() => {
        const callbacks = this.lookupCallbacks.get(hashHex)
        if (callbacks) {
          callbacks.delete(onPeer)
          if (callbacks.size === 0) {
            this.lookupCallbacks.delete(hashHex)
          }
        }
        resolve(peers)
      }, timeout)

      const onPeer = (peer) => {
        const addr = `${peer.host}:${peer.port}`
        if (!peers.find(p => `${p.host}:${p.port}` === addr)) {
          peers.push(peer)
        }
      }

      this.lookup(blobId, onPeer)

      setTimeout(() => {
        if (peers.length > 0) {
          clearTimeout(timer)
          resolve(peers)
        }
      }, Math.min(timeout / 2, 3000))
    })
  }

  getStats () {
    if (!this.dht) return null

    return {
      nodeId: this.nodeId.toString('hex'),
      port: this.port,
      nodes: this.dht.nodes ? this.dht.nodes.count() : 0,
      announcedHashes: this.announcedHashes.size
    }
  }

  destroy () {
    this._destroyed = true

    for (const [, entry] of this.announcedHashes) {
      clearInterval(entry.timer)
    }
    this.announcedHashes.clear()
    this.lookupCallbacks.clear()

    if (this.dht) {
      this.dht.destroy()
      this.dht = null
    }

    this.emit('destroyed')
  }
}

export default DHTDiscovery
