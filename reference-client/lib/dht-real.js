import DHT from 'bittorrent-dht'
import sodium from 'sodium-native'

// Wrapper for BEP 44 Mutable Items
export class DHTClient {
  constructor (opts = {}) {
    this.dht = new DHT(opts)
    this.ready = false
    this.dht.on('ready', () => { this.ready = true })
  }

  destroy () {
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
        resolve(hash)
      })
    })
  }

  // Get Mutable Data (Manifest)
  async getManifest (publicKeyHex) {
    return new Promise((resolve, reject) => {
      const publicKey = Buffer.from(publicKeyHex, 'hex')
      this.dht.get(publicKey.toString('hex'), { verify: this._verify }, (err, res) => {
        if (err) return reject(err)
        if (!res || !res.v) return resolve(null)

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
        if (err) reject(err)
        else resolve()
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
        // Return whatever we found
        resolve(peers)
      })
    })
  }
}
