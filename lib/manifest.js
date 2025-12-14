import stringify from 'fast-json-stable-stringify'
import sodium from 'sodium-native'

export function verify (message, signature, publicKey) {
  const msgBuffer = Buffer.isBuffer(message) ? message : Buffer.from(message)
  return sodium.crypto_sign_verify_detached(signature, msgBuffer, publicKey)
}

export function validateManifest (manifest) {
  if (!manifest || typeof manifest !== 'object') throw new Error('Invalid manifest')
  if (!manifest.publicKey || !manifest.signature) throw new Error('Missing keys')

  // Reconstruct the payload to verify (exclude signature)
  const payload = {
    publicKey: manifest.publicKey,
    sequence: manifest.sequence,
    timestamp: manifest.timestamp,
    collections: manifest.collections
  }

  const jsonString = stringify(payload)
  const publicKey = Buffer.from(manifest.publicKey, 'hex')
  const signature = Buffer.from(manifest.signature, 'hex')

  return verify(jsonString, signature, publicKey)
}
