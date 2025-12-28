import DHT from 'bittorrent-dht'
import sodium from 'sodium-native'
import fs from 'fs'
import crypto from 'crypto'

// Wrapper for BEP 44 Mutable Items & State Persistence
export class DHTClient {
  constructor (opts = {}) {
    // Load persisted state if available
    const stateFile = opts.stateFile || './dht_state.json'
    const dhtOpts = { ...opts }

    if (fs.existsSync(stateFile)) {
      try {
        const state = JSON.parse(fs.readFileSync(stateFile, 'utf-8'))
        if (state.nodeId) dhtOpts.nodeId = Buffer.from(state.nodeId, 'hex')
        if (state.nodes) {
          this._savedNodes = state.nodes
        }
      } catch (e) {
        console.error('Failed to load DHT state:', e.message)
      }
    }

    // Support custom bootstrap via options
    if (opts.bootstrap) {
        dhtOpts.bootstrap = Array.isArray(opts.bootstrap) ? opts.bootstrap : [opts.bootstrap]
    }

    this.dht = new DHT(dhtOpts)
    this.ready = false
    this.stateFile = stateFile

    this.dht.on('ready', () => {
      this.ready = true
      if (this._savedNodes) {
        this._savedNodes.forEach(node => this.dht.addNode(node))
      }
    })

    if (opts.port) {
        this.dht.listen(opts.port, () => {
            console.log(`[DHT] Listening on ${opts.port}`)
        })
    }

    // Auto-save state on exit or periodically
    this._saveInterval = setInterval(() => this.saveState(), 60000 * 5)
  }

  saveState () {
    try {
      const state = {
        nodeId: this.dht.nodeId.toString('hex'),
        nodes: this.dht.toJSON().nodes
      }
      fs.writeFileSync(this.stateFile, JSON.stringify(state, null, 2))
    } catch (e) {
      // Ignore write errors
    }
  }

  destroy () {
    this.saveState()
    clearInterval(this._saveInterval)
    this.dht.destroy()
  }

  // Publish Mutable Data (Manifest)
  async putManifest (keypair, sequence, manifest) {
    return new Promise((resolve, reject) => {
      const value = Buffer.from(JSON.stringify(manifest))

      const opts = {
        k: keypair.publicKey,
        seq: sequence,
        v: value,
        sign: (buf) => {
          const sig = Buffer.alloc(sodium.crypto_sign_BYTES)
          sodium.crypto_sign_detached(sig, buf, keypair.secretKey)
          return sig
        }
      }

      this.dht.put(opts, (err, hash) => {
        if (err) return reject(err)
        console.log(`[DHT] Put Success. Hash: ${hash.toString('hex')}`)
        resolve(hash)
      })
    })
  }

  // Gateway Relay: Put data signed by someone else
  async relaySignedPut (publicKey, sequence, value, signature) {
      return new Promise((resolve, reject) => {
          const opts = {
              k: publicKey,
              seq: sequence,
              v: value,
              sign: (buf) => {
                  return signature
              }
          }

          this.dht.put(opts, (err, hash) => {
              if (err) return reject(err)
              resolve(hash)
          })
      })
  }

  // Get Mutable Data (Manifest)
  async getManifest (publicKeyHex) {
    return new Promise((resolve, reject) => {
      const publicKey = Buffer.from(publicKeyHex, 'hex')

      // Calculate target ID = SHA1(publicKey)
      // Note: bittorrent-dht expects the target ID (infohash) for `get` calls.
      const target = crypto.createHash('sha1').update(publicKey).digest()

      console.log(`[DHT] Looking up target ${target.toString('hex')} for pubkey ${publicKeyHex.substring(0,8)}...`)

      this.dht.get(target, { verify: this._verify }, (err, res) => {
        if (err) return reject(err)
        if (!res || !res.v) {
             console.log(`[DHT] Get Miss for ${publicKey.toString('hex')}`)
             return resolve(null)
        }
        console.log(`[DHT] Get Hit for ${publicKey.toString('hex')}. Seq: ${res.seq}`)

        try {
          const manifest = JSON.parse(res.v.toString())
          resolve({ manifest, seq: res.seq })
        } catch (e) {
          reject(new Error('Invalid JSON in DHT'))
        }
      })
    })
  }

  _verify (sig, value, pubKey) {
    return sodium.crypto_sign_verify_detached(sig, value, pubKey)
  }

  // Announce Blob (Immutable) - Map BlobHash -> Peer (IP:Port)
  announceBlob (blobId, port) {
    return new Promise((resolve, reject) => {
      this.dht.announce(blobId, port, (err) => {
        if (err) {
            // Do not crash, just resolve with warning or error
            console.error(`[DHT] Announce Warning: ${err.message}`)
            return resolve()
        }
        resolve()
      })
    })
  }

  // Find Blob Peers
  findBlobPeers (blobId) {
    return new Promise((resolve, reject) => {
      const peers = []
      this.dht.on('peer', (peer, infoHash, from) => {
        if (infoHash.toString('hex') === blobId) {
          peers.push(`${peer.host}:${peer.port}`)
        }
      })

      this.dht.lookup(blobId, (err) => {
        if (err) return reject(err)
        resolve(peers)
      })
    })
  }
}
