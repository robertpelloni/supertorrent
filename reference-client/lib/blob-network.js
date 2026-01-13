import { EventEmitter } from 'events'
import { WebSocketServer, WebSocket } from 'ws'
import crypto from 'crypto'

const PROTOCOL_VERSION = 1
const MAX_CONCURRENT_REQUESTS = 10
const REQUEST_TIMEOUT_MS = 30000
const MAX_BLOB_SIZE = 2 * 1024 * 1024

const MSG = {
  HANDSHAKE: 0,
  HANDSHAKE_ACK: 1,
  REQUEST: 2,
  RESPONSE: 3,
  HAVE: 4,
  WANT: 5,
  NOT_FOUND: 6,
  ERROR: 7
}

export class BlobNetwork extends EventEmitter {
  constructor (blobStore, options = {}) {
    super()
    this.store = blobStore
    this.port = options.port || 0
    this.peerId = options.peerId || crypto.randomBytes(20).toString('hex')
    this.maxConnections = options.maxConnections || 50
    
    this.server = null
    this.peers = new Map()
    this.pendingRequests = new Map()
    this.requestId = 0
    this._destroyed = false
  }

  async listen (port = this.port) {
    return new Promise((resolve, reject) => {
      this.server = new WebSocketServer({ port })
      
      this.server.on('listening', () => {
        this.port = this.server.address().port
        this.emit('listening', { port: this.port })
        resolve(this.port)
      })
      
      this.server.on('error', reject)
      
      this.server.on('connection', (socket, req) => {
        const addr = req.socket.remoteAddress + ':' + req.socket.remotePort
        this._handleIncoming(socket, addr)
      })
    })
  }

  async connect (address) {
    return new Promise((resolve, reject) => {
      if (this.peers.has(address)) {
        return resolve(this.peers.get(address))
      }
      
      const socket = new WebSocket(address)
      
      socket.on('open', () => {
        const peer = this._setupPeer(socket, address, false)
        this._sendHandshake(peer)
        resolve(peer)
      })
      
      socket.on('error', reject)
    })
  }

  _handleIncoming (socket, address) {
    if (this.peers.size >= this.maxConnections) {
      socket.close()
      return
    }
    
    const peer = this._setupPeer(socket, address, true)
    this._sendHandshake(peer)
  }

  _setupPeer (socket, address, incoming) {
    const peer = {
      socket,
      address,
      incoming,
      peerId: null,
      handshakeComplete: false,
      haveBlobs: new Set(),
      pendingRequests: new Map()
    }
    
    socket.on('message', (data) => this._handleMessage(peer, data))
    socket.on('close', () => this._handleDisconnect(peer))
    socket.on('error', () => this._handleDisconnect(peer))
    
    this.peers.set(address, peer)
    return peer
  }

  _sendHandshake (peer) {
    const msg = {
      type: MSG.HANDSHAKE,
      version: PROTOCOL_VERSION,
      peerId: this.peerId,
      blobCount: this.store.index.size
    }
    this._send(peer, msg)
  }

  _send (peer, msg) {
    if (peer.socket.readyState === WebSocket.OPEN) {
      peer.socket.send(JSON.stringify(msg))
    }
  }

  _sendBinary (peer, type, requestId, blobId, data) {
    if (peer.socket.readyState !== WebSocket.OPEN) return
    
    const header = Buffer.alloc(1 + 4 + 32)
    header.writeUInt8(type, 0)
    header.writeUInt32BE(requestId, 1)
    Buffer.from(blobId, 'hex').copy(header, 5, 0, 32)
    
    const packet = Buffer.concat([header, data || Buffer.alloc(0)])
    peer.socket.send(packet)
  }

  _handleMessage (peer, data) {
    if (Buffer.isBuffer(data) && data.length >= 37 && data[0] !== 0x7b) {
      this._handleBinaryMessage(peer, data)
      return
    }
    
    let msg
    try {
      msg = JSON.parse(data.toString())
    } catch {
      return
    }
    
    switch (msg.type) {
      case MSG.HANDSHAKE:
        this._handleHandshake(peer, msg)
        break
      case MSG.HANDSHAKE_ACK:
        this._handleHandshakeAck(peer, msg)
        break
      case MSG.REQUEST:
        this._handleRequest(peer, msg)
        break
      case MSG.HAVE:
        this._handleHave(peer, msg)
        break
      case MSG.WANT:
        this._handleWant(peer, msg)
        break
      case MSG.NOT_FOUND:
        this._handleNotFound(peer, msg)
        break
    }
  }

  _handleBinaryMessage (peer, data) {
    const type = data.readUInt8(0)
    const requestId = data.readUInt32BE(1)
    const blobId = data.slice(5, 37).toString('hex')
    const payload = data.slice(37)
    
    if (type === MSG.RESPONSE) {
      this._handleResponse(peer, requestId, blobId, payload)
    }
  }

  _handleHandshake (peer, msg) {
    if (msg.version !== PROTOCOL_VERSION) {
      peer.socket.close()
      return
    }
    
    peer.peerId = msg.peerId
    peer.handshakeComplete = true
    
    this._send(peer, {
      type: MSG.HANDSHAKE_ACK,
      version: PROTOCOL_VERSION,
      peerId: this.peerId
    })
    
    this.emit('peer', { address: peer.address, peerId: peer.peerId })
    this._announceHaves(peer)
  }

  _handleHandshakeAck (peer, msg) {
    peer.peerId = msg.peerId
    peer.handshakeComplete = true
    this.emit('peer', { address: peer.address, peerId: peer.peerId })
    this._announceHaves(peer)
  }

  _announceHaves (peer) {
    const blobIds = this.store.list().map(b => b.blobId)
    if (blobIds.length > 0) {
      this._send(peer, { type: MSG.HAVE, blobIds })
    }
  }

  _handleRequest (peer, msg) {
    const { requestId, blobId } = msg
    
    if (!this.store.has(blobId)) {
      this._send(peer, { type: MSG.NOT_FOUND, requestId, blobId })
      return
    }
    
    const data = this.store.get(blobId)
    if (!data) {
      this._send(peer, { type: MSG.NOT_FOUND, requestId, blobId })
      return
    }
    
    this._sendBinary(peer, MSG.RESPONSE, requestId, blobId, data)
    this.emit('upload', { blobId, size: data.length, peer: peer.address })
  }

  _handleResponse (peer, requestId, blobId, data) {
    const pending = this.pendingRequests.get(requestId)
    if (!pending) return
    
    clearTimeout(pending.timeout)
    this.pendingRequests.delete(requestId)
    
    const hash = crypto.createHash('sha256').update(data).digest('hex')
    if (hash !== blobId) {
      pending.reject(new Error('Blob hash mismatch'))
      return
    }
    
    this.store.put(blobId, data)
    pending.resolve(data)
    this.emit('download', { blobId, size: data.length, peer: peer.address })
  }

  _handleHave (peer, msg) {
    const { blobIds } = msg
    if (!Array.isArray(blobIds)) return
    
    for (const blobId of blobIds) {
      peer.haveBlobs.add(blobId)
    }
    
    this.emit('have', { peer: peer.address, blobIds })
  }

  _handleWant (peer, msg) {
    const { blobIds } = msg
    if (!Array.isArray(blobIds)) return
    
    const have = blobIds.filter(id => this.store.has(id))
    if (have.length > 0) {
      this._send(peer, { type: MSG.HAVE, blobIds: have })
    }
  }

  _handleNotFound (peer, msg) {
    const { requestId } = msg
    const pending = this.pendingRequests.get(requestId)
    if (pending) {
      clearTimeout(pending.timeout)
      this.pendingRequests.delete(requestId)
      pending.reject(new Error('Blob not found'))
    }
  }

  _handleDisconnect (peer) {
    this.peers.delete(peer.address)
    
    for (const [reqId, pending] of peer.pendingRequests) {
      clearTimeout(pending.timeout)
      this.pendingRequests.delete(reqId)
      pending.reject(new Error('Peer disconnected'))
    }
    
    this.emit('disconnect', { address: peer.address, peerId: peer.peerId })
  }

  async requestBlob (blobId, preferredPeers = null) {
    if (this.store.has(blobId)) {
      return this.store.get(blobId)
    }
    
    const candidates = preferredPeers || this._findPeersWithBlob(blobId)
    
    for (const peer of candidates) {
      if (!peer.handshakeComplete) continue
      
      try {
        return await this._requestFromPeer(peer, blobId)
      } catch {
        continue
      }
    }
    
    throw new Error(`Blob ${blobId} not available from any peer`)
  }

  _findPeersWithBlob (blobId) {
    const result = []
    for (const peer of this.peers.values()) {
      if (peer.haveBlobs.has(blobId)) {
        result.push(peer)
      }
    }
    return result
  }

  _requestFromPeer (peer, blobId) {
    return new Promise((resolve, reject) => {
      if (this.pendingRequests.size >= MAX_CONCURRENT_REQUESTS) {
        return reject(new Error('Too many concurrent requests'))
      }
      
      const requestId = ++this.requestId
      
      const timeout = setTimeout(() => {
        this.pendingRequests.delete(requestId)
        reject(new Error('Request timeout'))
      }, REQUEST_TIMEOUT_MS)
      
      this.pendingRequests.set(requestId, {
        blobId,
        peer,
        resolve,
        reject,
        timeout
      })
      
      peer.pendingRequests.set(requestId, this.pendingRequests.get(requestId))
      
      this._send(peer, {
        type: MSG.REQUEST,
        requestId,
        blobId
      })
    })
  }

  announceBlob (blobId) {
    for (const peer of this.peers.values()) {
      if (peer.handshakeComplete) {
        this._send(peer, { type: MSG.HAVE, blobIds: [blobId] })
      }
    }
  }

  queryBlob (blobId) {
    for (const peer of this.peers.values()) {
      if (peer.handshakeComplete) {
        this._send(peer, { type: MSG.WANT, blobIds: [blobId] })
      }
    }
  }

  getPeers () {
    return Array.from(this.peers.values()).map(p => ({
      address: p.address,
      peerId: p.peerId,
      incoming: p.incoming,
      handshakeComplete: p.handshakeComplete,
      blobCount: p.haveBlobs.size
    }))
  }

  destroy () {
    this._destroyed = true
    
    for (const peer of this.peers.values()) {
      peer.socket.close()
    }
    this.peers.clear()
    
    for (const [, pending] of this.pendingRequests) {
      clearTimeout(pending.timeout)
      pending.reject(new Error('Network destroyed'))
    }
    this.pendingRequests.clear()
    
    if (this.server) {
      this.server.close()
    }
    
    this.emit('destroyed')
  }
}

export default BlobNetwork
