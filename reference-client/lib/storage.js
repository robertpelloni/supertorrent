import sodium from 'sodium-native'
import crypto from 'crypto'
import { EventEmitter } from 'events'

const CHUNK_SIZE = 1024 * 1024

function sha256 (buffer) {
  const hash = crypto.createHash('sha256')
  hash.update(buffer)
  return hash.digest('hex')
}

export function ingest (fileBuffer, fileName) {
  const totalSize = fileBuffer.length
  const chunks = []
  const blobs = []

  let offset = 0
  while (offset < totalSize) {
    const end = Math.min(offset + CHUNK_SIZE, totalSize)
    const chunkData = fileBuffer.slice(offset, end)

    const key = Buffer.alloc(sodium.crypto_aead_chacha20poly1305_ietf_KEYBYTES)
    const nonce = Buffer.alloc(sodium.crypto_aead_chacha20poly1305_ietf_NPUBBYTES)
    sodium.randombytes_buf(key)
    sodium.randombytes_buf(nonce)

    const ciphertext = Buffer.alloc(chunkData.length + sodium.crypto_aead_chacha20poly1305_ietf_ABYTES)
    sodium.crypto_aead_chacha20poly1305_ietf_encrypt(
      ciphertext,
      chunkData,
      null,
      null,
      nonce,
      key
    )

    const blobBuffer = ciphertext
    const blobId = sha256(blobBuffer)

    blobs.push({
      id: blobId,
      buffer: blobBuffer
    })

    chunks.push({
      blobId,
      offset: 0,
      length: blobBuffer.length,
      key: key.toString('hex'),
      nonce: nonce.toString('hex')
    })

    offset = end
  }

  return {
    fileEntry: {
      name: fileName,
      size: totalSize,
      chunks
    },
    blobs
  }
}

export async function reassemble (fileEntry, getBlobFn) {
  const parts = []

  for (const chunkMeta of fileEntry.chunks) {
    const blobBuffer = await getBlobFn(chunkMeta.blobId)
    if (!blobBuffer) throw new Error(`Blob ${chunkMeta.blobId} not found`)

    const ciphertext = blobBuffer.slice(chunkMeta.offset, chunkMeta.offset + chunkMeta.length)

    const key = Buffer.from(chunkMeta.key, 'hex')
    const nonce = Buffer.from(chunkMeta.nonce, 'hex')
    const plaintext = Buffer.alloc(ciphertext.length - sodium.crypto_aead_chacha20poly1305_ietf_ABYTES)

    try {
      sodium.crypto_aead_chacha20poly1305_ietf_decrypt(
        plaintext,
        null,
        ciphertext,
        null,
        nonce,
        key
      )
    } catch (err) {
      throw new Error(`Decryption failed for blob ${chunkMeta.blobId}`)
    }

    parts.push(plaintext)
  }

  return Buffer.concat(parts)
}

export class BlobClient extends EventEmitter {
  constructor (options = {}) {
    super()
    this.store = options.store
    this.network = options.network
    this.tracker = options.tracker
    this.maxParallel = options.maxParallel || 5
  }

  async seed (fileEntry) {
    const blobIds = fileEntry.chunks.map(c => c.blobId)
    
    for (const blobId of blobIds) {
      if (this.store.has(blobId)) {
        this.tracker.announce(blobId)
        this.network.announceBlob(blobId)
      }
    }
    
    this.emit('seeding', { blobIds })
    return blobIds
  }

  async fetch (fileEntry, options = {}) {
    const onProgress = options.onProgress || (() => {})
    const chunks = fileEntry.chunks
    const total = chunks.length
    let completed = 0
    
    const getBlobFn = async (blobId) => {
      if (this.store.has(blobId)) {
        return this.store.get(blobId)
      }
      
      const peers = await this.tracker.lookup(blobId)
      
      for (const peer of peers) {
        try {
          await this.network.connect(peer.address)
        } catch {
          continue
        }
      }
      
      this.network.queryBlob(blobId)
      
      await new Promise(resolve => setTimeout(resolve, 500))
      
      const blob = await this.network.requestBlob(blobId)
      
      completed++
      onProgress({ completed, total, blobId })
      
      return blob
    }
    
    return reassemble(fileEntry, getBlobFn)
  }

  async storeBlobs (blobs) {
    for (const blob of blobs) {
      this.store.put(blob.id, blob.buffer)
    }
  }
}

export function createBlobClient (store, network, tracker) {
  return new BlobClient({ store, network, tracker })
}
