import stringify from 'fast-json-stable-stringify'
import { sign, verify } from './crypto.js'

export function createManifest (keypair, sequence, collections, options = {}) {
  const payload = {
    publicKey: keypair.publicKey.toString('hex'),
    sequence,
    timestamp: Date.now(),
    collections,
    ...(options.topics && { topics: options.topics }),
    ...(options.metadata && { metadata: options.metadata })
  }

  // Canonicalize string for signing
  const jsonString = stringify(payload)

  // Sign
  const signature = sign(jsonString, keypair.secretKey)

  return {
    ...payload,
    signature: signature.toString('hex')
  }
}

export function validateManifest (manifest) {
  if (!manifest || typeof manifest !== 'object') throw new Error('Invalid manifest')
  if (!manifest.publicKey || !manifest.signature) throw new Error('Missing keys')

  // Reconstruct the payload to verify (exclude signature)
  const payload = {
    publicKey: manifest.publicKey,
    sequence: manifest.sequence,
    timestamp: manifest.timestamp,
    collections: manifest.collections,
    ...(manifest.topics && { topics: manifest.topics }),
    ...(manifest.metadata && { metadata: manifest.metadata })
  }

  const jsonString = stringify(payload)
  const publicKey = Buffer.from(manifest.publicKey, 'hex')
  const signature = Buffer.from(manifest.signature, 'hex')

  return verify(jsonString, signature, publicKey)
}
