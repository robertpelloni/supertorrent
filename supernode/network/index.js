import { EventEmitter } from 'events'
import { BlobNetwork } from '../../reference-client/lib/blob-network.js'
import { BlobStore } from '../../reference-client/lib/blob-store.js'
import { SupernodeStorage } from '../storage/index.js'
import { DHTDiscovery } from './dht-discovery.js'
import { ManifestDistributor } from './manifest-distributor.js'

export class SupernodeNetwork extends EventEmitter {
  constructor (options = {}) {
    super()
    this.storageDir = options.storageDir || './supernode-data'
    this.port = options.port || 0
    this.isoSize = options.isoSize || 'nano'
    this.masterKey = options.masterKey
    this.enableDHT = options.enableDHT !== false
    this.dhtOptions = options.dht || {}
    
    this.blobStore = new BlobStore(this.storageDir + '/blobs', {
      maxSize: options.maxStorageSize || 10 * 1024 * 1024 * 1024
    })
    
    this.storage = new SupernodeStorage(this.blobStore, { isoSize: this.isoSize })
    this.network = new BlobNetwork(this.blobStore, {
      port: this.port,
      peerId: options.peerId,
      maxConnections: options.maxConnections || 50
    })
    
    this.dht = null
    this.manifestDistributor = null
    if (this.enableDHT) {
      this.dht = new DHTDiscovery({
        port: this.dhtOptions.port || 0,
        bootstrap: this.dhtOptions.bootstrap,
        announceInterval: this.dhtOptions.announceInterval
      })
      this.manifestDistributor = new ManifestDistributor({
        dht: this.dht,
        storage: this.storage
      })
    }
    
    this._setupEventForwarding()
  }

  _setupEventForwarding () {
    this.network.on('listening', (info) => this.emit('listening', info))
    this.network.on('peer', (info) => this.emit('peer', info))
    this.network.on('disconnect', (info) => this.emit('disconnect', info))
    this.network.on('upload', (info) => this.emit('upload', info))
    this.network.on('download', (info) => this.emit('download', info))
    this.network.on('have', (info) => this.emit('have', info))
    
    this.storage.on('chunk-ingested', (info) => this.emit('chunk-ingested', info))
    this.storage.on('file-ingested', (info) => this.emit('file-ingested', info))
    this.storage.on('chunk-retrieved', (info) => this.emit('chunk-retrieved', info))
    this.storage.on('file-retrieved', (info) => this.emit('file-retrieved', info))
    
    if (this.dht) {
      this.dht.on('peer', (info) => this.emit('dht-peer', info))
      this.dht.on('announced', (info) => this.emit('dht-announced', info))
      this.dht.on('ready', () => this.emit('dht-ready'))
    }
    
    if (this.manifestDistributor) {
      this.manifestDistributor.on('manifest-announced', (info) => this.emit('manifest-announced', info))
      this.manifestDistributor.on('manifest-served', (info) => this.emit('manifest-served', info))
    }
  }

  async listen (port = this.port) {
    const actualPort = await this.network.listen(port)
    this.port = actualPort
    
    if (this.dht) {
      await this.dht.start()
    }
    
    if (this.manifestDistributor) {
      this.manifestDistributor.setPort(actualPort)
    }
    
    return actualPort
  }

  async connect (address) {
    return this.network.connect(address)
  }

  async ingestFile (fileBuffer, fileName, masterKey = this.masterKey) {
    if (!masterKey) throw new Error('Master key required')
    
    const result = await this.storage.ingest(fileBuffer, fileName, masterKey)
    
    for (const chunkHash of result.chunkHashes) {
      this.network.announceBlob(chunkHash)
      if (this.dht) {
        this.dht.announce(chunkHash, this.port)
      }
    }
    
    if (this.manifestDistributor) {
      this.manifestDistributor.announceManifest(result.fileId)
    }
    
    return result
  }

  async retrieveFile (fileId, masterKey = this.masterKey) {
    if (!masterKey) throw new Error('Master key required')
    
    const encryptedManifest = this.storage.getManifest(fileId)
    if (!encryptedManifest) {
      throw new Error(`Unknown file: ${fileId}`)
    }
    
    return this.storage.retrieve(fileId, masterKey)
  }

  async fetchFile (fileId, encryptedManifest, masterKey = this.masterKey) {
    if (!masterKey) throw new Error('Master key required')
    
    this.storage.storeManifest(fileId, encryptedManifest)
    
    const { decryptManifest, deriveManifestKey } = await import('../storage/mux/manifest.js')
    const manifestKey = deriveManifestKey(masterKey, fileId)
    const manifest = decryptManifest(encryptedManifest, manifestKey)
    
    for (const segment of manifest.segments) {
      if (!this.blobStore.has(segment.chunkHash)) {
        this.network.queryBlob(segment.chunkHash)
        await this._waitForBlob(segment.chunkHash, 30000)
      }
    }
    
    return this.storage.retrieve(fileId, masterKey)
  }

  async _waitForBlob (blobId, timeoutMs = 30000) {
    if (this.blobStore.has(blobId)) return
    
    if (this.dht) {
      const peers = await this.dht.findPeers(blobId, Math.min(timeoutMs / 3, 5000))
      for (const peer of peers) {
        try {
          await this.network.connect(`ws://${peer.host}:${peer.port}`)
        } catch {
        }
      }
    }
    
    const start = Date.now()
    while (Date.now() - start < timeoutMs) {
      const peers = this.network._findPeersWithBlob(blobId)
      if (peers.length > 0) {
        try {
          await this.network.requestBlob(blobId, peers)
          return
        } catch {
        }
      }
      await new Promise(r => setTimeout(r, 500))
    }
    throw new Error(`Timeout waiting for blob: ${blobId}`)
  }

  async findManifest (fileId, timeout = 10000) {
    if (!this.manifestDistributor) {
      throw new Error('DHT required for manifest discovery')
    }
    
    const peers = await this.manifestDistributor.findManifestPeers(fileId, timeout)
    
    for (const peer of peers) {
      try {
        const ws = await this.network.connect(`ws://${peer.host}:${peer.port}`)
        
        const result = await this.manifestDistributor.requestManifest(
          fileId,
          (msg) => ws.send(JSON.stringify(msg)),
          5000
        )
        
        if (result.manifest) {
          return result.manifest
        }
      } catch {
      }
    }
    
    return null
  }

  async fetchFileByManifest (fileId, timeout = 30000) {
    const encryptedManifest = await this.findManifest(fileId, timeout)
    if (!encryptedManifest) {
      throw new Error(`Manifest not found for: ${fileId}`)
    }
    return encryptedManifest
  }

  handleManifestMessage (message, sendResponse) {
    if (!this.manifestDistributor) return false
    return this.manifestDistributor.handleMessage(message, sendResponse)
  }

  async shareManifest (fileId) {
    const manifest = this.storage.getManifest(fileId)
    if (!manifest) throw new Error(`Unknown file: ${fileId}`)
    return { fileId, encryptedManifest: manifest }
  }

  getPeers () {
    return this.network.getPeers()
  }

  stats () {
    const result = {
      ...this.storage.stats(),
      peers: this.network.getPeers().length,
      port: this.port
    }
    
    if (this.dht) {
      result.dht = this.dht.getStats()
    }
    
    if (this.manifestDistributor) {
      result.manifests = this.manifestDistributor.getStats()
    }
    
    return result
  }

  destroy () {
    if (this.manifestDistributor) {
      this.manifestDistributor.destroy()
    }
    if (this.dht) {
      this.dht.destroy()
    }
    this.network.destroy()
    this.emit('destroyed')
  }
}

export function createSupernodeNetwork (options = {}) {
  return new SupernodeNetwork(options)
}

export default SupernodeNetwork
