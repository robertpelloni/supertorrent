import { EventEmitter } from 'events'
import crypto from 'crypto'

export class ManifestDistributor extends EventEmitter {
  constructor (options = {}) {
    super()
    this.dht = options.dht
    this.storage = options.storage
    this.wsPort = options.wsPort || 0
    this.manifestCache = new Map()
    this.pendingRequests = new Map()
    this._destroyed = false
  }

  setPort (port) {
    this.wsPort = port
  }

  fileIdToInfoHash (fileId) {
    return crypto.createHash('sha1').update(`manifest:${fileId}`).digest()
  }

  announceManifest (fileId) {
    if (this._destroyed || !this.dht || !this.wsPort) return

    const infoHash = this.fileIdToInfoHash(fileId)
    const hashHex = infoHash.toString('hex')

    this.dht.dht.announce(infoHash, this.wsPort, (err) => {
      if (err) {
        this.emit('announce-error', { fileId, error: err.message })
      } else {
        this.emit('manifest-announced', { fileId, infoHash: hashHex })
      }
    })

    if (!this.manifestCache.has(fileId)) {
      this.manifestCache.set(fileId, {
        announcedAt: Date.now(),
        infoHash: hashHex
      })
    }
  }

  async findManifestPeers (fileId, timeout = 10000) {
    if (this._destroyed || !this.dht) return []

    const infoHash = this.fileIdToInfoHash(fileId)
    const hashHex = infoHash.toString('hex')

    return new Promise((resolve) => {
      const peers = []
      let resolved = false

      const timer = setTimeout(() => {
        if (!resolved) {
          resolved = true
          resolve(peers)
        }
      }, timeout)

      const onPeer = (peer, peerInfoHash) => {
        if (peerInfoHash.toString('hex') === hashHex) {
          const addr = `${peer.host}:${peer.port}`
          if (!peers.find(p => `${p.host}:${p.port}` === addr)) {
            peers.push({ host: peer.host, port: peer.port })
          }
        }
      }

      this.dht.dht.on('peer', onPeer)

      this.dht.dht.lookup(infoHash, () => {
        setTimeout(() => {
          this.dht.dht.removeListener('peer', onPeer)
          if (!resolved) {
            resolved = true
            clearTimeout(timer)
            resolve(peers)
          }
        }, Math.min(timeout / 2, 2000))
      })
    })
  }

  createManifestRequest (fileId) {
    return {
      type: 'manifest-request',
      fileId,
      requestId: crypto.randomBytes(8).toString('hex'),
      timestamp: Date.now()
    }
  }

  createManifestResponse (requestId, fileId, encryptedManifest) {
    return {
      type: 'manifest-response',
      requestId,
      fileId,
      manifest: encryptedManifest ? encryptedManifest.toString('base64') : null,
      found: !!encryptedManifest,
      timestamp: Date.now()
    }
  }

  handleMessage (message, sendResponse) {
    if (message.type === 'manifest-request') {
      return this._handleManifestRequest(message, sendResponse)
    }
    if (message.type === 'manifest-response') {
      return this._handleManifestResponse(message)
    }
    return false
  }

  _handleManifestRequest (request, sendResponse) {
    const { fileId, requestId } = request

    const encryptedManifest = this.storage.getManifest(fileId)

    const response = this.createManifestResponse(requestId, fileId, encryptedManifest)
    sendResponse(response)

    this.emit('manifest-served', {
      fileId,
      found: !!encryptedManifest,
      requestId
    })

    return true
  }

  _handleManifestResponse (response) {
    const { requestId, fileId, manifest, found } = response

    const pending = this.pendingRequests.get(requestId)
    if (!pending) return false

    clearTimeout(pending.timer)
    this.pendingRequests.delete(requestId)

    if (found && manifest) {
      const manifestBuffer = Buffer.from(manifest, 'base64')
      pending.resolve({ fileId, manifest: manifestBuffer })
    } else {
      pending.resolve({ fileId, manifest: null })
    }

    return true
  }

  async requestManifest (fileId, sendRequest, timeout = 5000) {
    const request = this.createManifestRequest(fileId)

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingRequests.delete(request.requestId)
        reject(new Error(`Manifest request timeout: ${fileId}`))
      }, timeout)

      this.pendingRequests.set(request.requestId, {
        fileId,
        resolve,
        reject,
        timer
      })

      sendRequest(request)
    })
  }

  getAnnouncedManifests () {
    return Array.from(this.manifestCache.entries()).map(([fileId, info]) => ({
      fileId,
      ...info
    }))
  }

  getStats () {
    return {
      announcedManifests: this.manifestCache.size,
      pendingRequests: this.pendingRequests.size
    }
  }

  destroy () {
    this._destroyed = true

    for (const [, pending] of this.pendingRequests) {
      clearTimeout(pending.timer)
      pending.reject(new Error('Distributor destroyed'))
    }
    this.pendingRequests.clear()
    this.manifestCache.clear()

    this.emit('destroyed')
  }
}

export default ManifestDistributor
