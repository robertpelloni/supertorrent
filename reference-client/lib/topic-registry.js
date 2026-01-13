import sodium from 'sodium-native'
import { EventEmitter } from 'events'
import { sign, verify } from './crypto.js'

/**
 * TopicRegistry - BEP 44 based topic registration and discovery
 * 
 * Topics use a hierarchical Usenet-style naming convention:
 *   mp3/spotify-rips/electronic
 *   software/windows/games
 * 
 * Each topic is addressed via DHT using:
 *   targetId = SHA1(registryPublicKey + topicSalt)
 *   where topicSalt = SHA256("supertorrent:topic:" + topicPath)
 * 
 * Registry entries contain publisher lists that point to their manifests.
 */

const SUPERTORRENT_TOPIC_PREFIX = 'supertorrent:topic:'
const MAX_BEP44_VALUE_SIZE = 1000
const DHT_REPUBLISH_INTERVAL_MS = 45 * 60 * 1000
const DHT_POLL_INTERVAL_MS = 5 * 60 * 1000

/**
 * Generate a deterministic salt for a topic path
 * @param {string} topicPath - e.g., "mp3/electronic"
 * @returns {Buffer} - 32-byte salt
 */
export function topicToSalt (topicPath) {
  const normalized = topicPath.toLowerCase().replace(/\/+/g, '/').replace(/^\/|\/$/g, '')
  const input = Buffer.from(SUPERTORRENT_TOPIC_PREFIX + normalized)
  const hash = Buffer.alloc(32)
  sodium.crypto_generichash(hash, input)
  return hash
}

/**
 * Compute DHT target ID for a topic (BEP 44 style)
 * targetId = SHA1(publicKey + salt)
 * @param {Buffer} publicKey - Registry public key
 * @param {Buffer} salt - Topic salt
 * @returns {Buffer} - 20-byte target ID
 */
export function computeTargetId (publicKey, salt) {
  const combined = Buffer.concat([publicKey, salt])
  const hash = Buffer.alloc(20)
  sodium.crypto_generichash(hash, combined)
  return hash
}

/**
 * Create a signed topic registry entry (BEP 44 mutable item format)
 * @param {Object} keypair - { publicKey, secretKey }
 * @param {Buffer} salt - Topic salt
 * @param {number} seq - Sequence number (must monotonically increase)
 * @param {Object} value - Registry entry value
 * @returns {Object} - Signed entry ready for DHT put
 */
export function createRegistryEntry (keypair, salt, seq, value) {
  const encodedValue = Buffer.from(JSON.stringify(value))
  
  if (encodedValue.length > MAX_BEP44_VALUE_SIZE) {
    throw new Error(`Registry entry too large: ${encodedValue.length} > ${MAX_BEP44_VALUE_SIZE} bytes`)
  }
  
  return {
    k: keypair.publicKey,
    v: encodedValue,
    seq,
    salt,
    sign: (buf) => sign(buf, keypair.secretKey)
  }
}

/**
 * Verify a registry entry signature
 * @param {Object} entry - DHT entry with k, v, sig, seq, salt
 * @returns {boolean}
 */
export function verifyRegistryEntry (entry, verifyFn) {
  if (!entry || !entry.k || !entry.v || !entry.sig) return false
  return verifyFn(entry.sig, entry.v, entry.k)
}

/**
 * TopicRegistry class - manages topic registration and discovery via DHT
 */
export class TopicRegistry extends EventEmitter {
  /**
   * @param {Object} dht - bittorrent-dht instance
   * @param {Object} keypair - Optional keypair for publishing
   */
  constructor (dht, keypair = null) {
    super()
    this.dht = dht
    this.keypair = keypair
    this.subscriptions = new Map()
    this.republishIntervals = new Map()
    this._destroyed = false
  }

  /**
   * Register as a publisher for a topic
   * @param {string} topicPath - e.g., "mp3/electronic"
   * @param {Object} metadata - Publisher metadata { name, description, ... }
   * @returns {Promise<void>}
   */
  async registerPublisher (topicPath, metadata = {}) {
    if (!this.keypair) {
      throw new Error('Keypair required to register as publisher')
    }

    const salt = topicToSalt(topicPath)
    const existing = await this._dhtGet(salt)
    
    let registry = existing?.value || { publishers: [], subtopics: [] }
    let seq = (existing?.seq || 0) + 1
    
    if (Buffer.isBuffer(registry)) {
      try {
        registry = JSON.parse(registry.toString())
      } catch {
        registry = { publishers: [], subtopics: [] }
      }
    }
    
    const myPk = this.keypair.publicKey.toString('hex')
    const existingIdx = registry.publishers.findIndex(p => p.pk === myPk)
    
    const publisherEntry = {
      pk: myPk,
      name: metadata.name || 'Anonymous',
      registered: Date.now(),
      ...metadata
    }
    
    if (existingIdx >= 0) {
      registry.publishers[existingIdx] = publisherEntry
    } else {
      registry.publishers.push(publisherEntry)
    }
    
    const parts = topicPath.split('/')
    if (parts.length > 1) {
      const parentPath = parts.slice(0, -1).join('/')
      await this._ensureSubtopicRegistered(parentPath, parts[parts.length - 1])
    }
    
    await this._dhtPut(salt, seq, registry, existing?.seq)
    this._startRepublishing(topicPath, salt, registry)
    
    this.emit('registered', { topicPath, metadata })
  }

  /**
   * Unregister as a publisher from a topic
   * @param {string} topicPath
   */
  async unregisterPublisher (topicPath) {
    if (!this.keypair) {
      throw new Error('Keypair required to unregister')
    }

    const salt = topicToSalt(topicPath)
    const existing = await this._dhtGet(salt)
    
    if (!existing?.value) return
    
    let registry = existing.value
    if (Buffer.isBuffer(registry)) {
      registry = JSON.parse(registry.toString())
    }
    
    const myPk = this.keypair.publicKey.toString('hex')
    registry.publishers = registry.publishers.filter(p => p.pk !== myPk)
    
    await this._dhtPut(salt, existing.seq + 1, registry, existing.seq)
    this._stopRepublishing(topicPath)
    
    this.emit('unregistered', { topicPath })
  }

  /**
   * Subscribe to a topic - returns list of publishers and watches for updates
   * @param {string} topicPath
   * @param {Object} options - { pollInterval }
   * @returns {Promise<TopicSubscription>}
   */
  async subscribe (topicPath, options = {}) {
    const salt = topicToSalt(topicPath)
    const pollInterval = options.pollInterval || DHT_POLL_INTERVAL_MS
    
    const entry = await this._dhtGet(salt)
    let registry = { publishers: [], subtopics: [] }
    
    if (entry?.value) {
      registry = Buffer.isBuffer(entry.value) 
        ? JSON.parse(entry.value.toString())
        : entry.value
    }
    
    const subscription = new TopicSubscription(this, topicPath, registry, entry?.seq || 0)
    
    const intervalId = setInterval(async () => {
      if (this._destroyed) return
      
      try {
        const updated = await this._dhtGet(salt)
        if (updated && updated.seq > subscription.seq) {
          const newRegistry = Buffer.isBuffer(updated.value)
            ? JSON.parse(updated.value.toString())
            : updated.value
          
          subscription._update(newRegistry, updated.seq)
        }
      } catch (err) {
        this.emit('error', err)
      }
    }, pollInterval)
    
    this.subscriptions.set(topicPath, { 
      intervalId, 
      subscription,
      salt 
    })
    
    this.emit('subscribed', { topicPath, publishers: registry.publishers })
    
    return subscription
  }

  /**
   * Unsubscribe from a topic
   * @param {string} topicPath
   */
  unsubscribe (topicPath) {
    const sub = this.subscriptions.get(topicPath)
    if (sub) {
      clearInterval(sub.intervalId)
      this.subscriptions.delete(topicPath)
      this.emit('unsubscribed', { topicPath })
    }
  }

  /**
   * List all publishers for a topic (one-shot, no subscription)
   * @param {string} topicPath
   * @returns {Promise<Array>}
   */
  async listPublishers (topicPath) {
    const salt = topicToSalt(topicPath)
    const entry = await this._dhtGet(salt)
    
    if (!entry?.value) return []
    
    const registry = Buffer.isBuffer(entry.value)
      ? JSON.parse(entry.value.toString())
      : entry.value
    
    return registry.publishers || []
  }

  /**
   * List subtopics of a topic
   * @param {string} topicPath
   * @returns {Promise<Array>}
   */
  async listSubtopics (topicPath) {
    const salt = topicToSalt(topicPath)
    const entry = await this._dhtGet(salt)
    
    if (!entry?.value) return []
    
    const registry = Buffer.isBuffer(entry.value)
      ? JSON.parse(entry.value.toString())
      : entry.value
    
    return registry.subtopics || []
  }

  /**
   * Browse topic hierarchy starting from root or a path
   * @param {string} topicPath - Starting path (empty for root)
   * @returns {Promise<Object>} - { publishers, subtopics }
   */
  async browse (topicPath = '') {
    const [publishers, subtopics] = await Promise.all([
      this.listPublishers(topicPath),
      this.listSubtopics(topicPath)
    ])
    
    return { 
      path: topicPath || '/', 
      publishers, 
      subtopics 
    }
  }

  /**
   * Destroy the registry, clean up intervals
   */
  destroy () {
    this._destroyed = true
    
    for (const [, sub] of this.subscriptions) {
      clearInterval(sub.intervalId)
    }
    this.subscriptions.clear()
    
    for (const [, intervalId] of this.republishIntervals) {
      clearInterval(intervalId)
    }
    this.republishIntervals.clear()
    
    this.emit('destroyed')
  }


  async _dhtGet (salt) {
    return new Promise((resolve, reject) => {
      const targetKey = this.keypair?.publicKey || this._getWellKnownRegistryKey()
      
      this.dht.get(targetKey, { salt }, (err, result) => {
        if (err) {
          if (err.message?.includes('not found')) {
            resolve(null)
          } else {
            reject(err)
          }
        } else {
          resolve(result)
        }
      })
    })
  }

  async _dhtPut (salt, seq, value, cas = null) {
    return new Promise((resolve, reject) => {
      if (!this.keypair) {
        return reject(new Error('Keypair required for DHT put'))
      }
      
      const encodedValue = Buffer.from(JSON.stringify(value))
      
      if (encodedValue.length > MAX_BEP44_VALUE_SIZE) {
        return reject(new Error(`Value too large: ${encodedValue.length} bytes`))
      }
      
      const putOpts = {
        k: this.keypair.publicKey,
        v: encodedValue,
        seq,
        salt,
        sign: (buf) => sign(buf, this.keypair.secretKey)
      }
      
      if (cas !== null) {
        putOpts.cas = cas
      }
      
      this.dht.put(putOpts, (err, hash) => {
        if (err) reject(err)
        else resolve(hash)
      })
    })
  }

  _startRepublishing (topicPath, salt, registry) {
    this._stopRepublishing(topicPath)
    
    const republish = async () => {
      if (this._destroyed) return
      
      try {
        const existing = await this._dhtGet(salt)
        const seq = (existing?.seq || 0) + 1
        await this._dhtPut(salt, seq, registry, existing?.seq)
      } catch (err) {
        this.emit('error', err)
      }
    }
    
    const intervalId = setInterval(republish, DHT_REPUBLISH_INTERVAL_MS)
    this.republishIntervals.set(topicPath, intervalId)
  }

  _stopRepublishing (topicPath) {
    const intervalId = this.republishIntervals.get(topicPath)
    if (intervalId) {
      clearInterval(intervalId)
      this.republishIntervals.delete(topicPath)
    }
  }

  async _ensureSubtopicRegistered (parentPath, subtopic) {
    try {
      const salt = topicToSalt(parentPath)
      const existing = await this._dhtGet(salt)
      
      let registry = existing?.value || { publishers: [], subtopics: [] }
      if (Buffer.isBuffer(registry)) {
        registry = JSON.parse(registry.toString())
      }
      
      if (!registry.subtopics.includes(subtopic)) {
        registry.subtopics.push(subtopic)
        registry.subtopics.sort()
        
        await this._dhtPut(salt, (existing?.seq || 0) + 1, registry, existing?.seq)
      }
    } catch (err) {
      this.emit('error', err)
    }
  }

  _getWellKnownRegistryKey () {
    const seed = Buffer.alloc(32)
    sodium.crypto_generichash(seed, Buffer.from('supertorrent:global-registry'))
    
    const pk = Buffer.alloc(sodium.crypto_sign_PUBLICKEYBYTES)
    const sk = Buffer.alloc(sodium.crypto_sign_SECRETKEYBYTES)
    sodium.crypto_sign_seed_keypair(pk, sk)
    
    return pk
  }
}

/**
 * TopicSubscription - represents an active subscription to a topic
 */
export class TopicSubscription extends EventEmitter {
  constructor (registry, topicPath, initialRegistry, seq) {
    super()
    this.registry = registry
    this.topicPath = topicPath
    this.publishers = initialRegistry.publishers || []
    this.subtopics = initialRegistry.subtopics || []
    this.seq = seq
  }

  /**
   * Get current list of publishers
   */
  getPublishers () {
    return [...this.publishers]
  }

  /**
   * Get subtopics
   */
  getSubtopics () {
    return [...this.subtopics]
  }

  /**
   * Check if a specific publisher is in this topic
   * @param {string} publicKeyHex
   */
  hasPublisher (publicKeyHex) {
    return this.publishers.some(p => p.pk === publicKeyHex)
  }

  /**
   * Unsubscribe from this topic
   */
  unsubscribe () {
    this.registry.unsubscribe(this.topicPath)
    this.emit('unsubscribed')
  }

  /**
   * Internal: update from polling
   */
  _update (newRegistry, newSeq) {
    const oldPublishers = new Set(this.publishers.map(p => p.pk))
    const newPublishers = new Set(newRegistry.publishers.map(p => p.pk))
    
    const added = newRegistry.publishers.filter(p => !oldPublishers.has(p.pk))
    const removed = this.publishers.filter(p => !newPublishers.has(p.pk))
    
    this.publishers = newRegistry.publishers
    this.subtopics = newRegistry.subtopics || []
    this.seq = newSeq
    
    if (added.length > 0) {
      this.emit('publisher-added', added)
    }
    
    if (removed.length > 0) {
      this.emit('publisher-removed', removed)
    }
    
    this.emit('updated', { publishers: this.publishers, subtopics: this.subtopics })
  }
}

export default TopicRegistry
