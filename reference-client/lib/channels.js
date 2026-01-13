import { EventEmitter } from 'events'
import { TopicRegistry, topicToSalt } from './topic-registry.js'
import { createManifest, validateManifest } from './manifest.js'
import { generateKeypair } from './crypto.js'

const DEFAULT_FILTER_OPTIONS = {
  minSeeders: 0,
  maxAge: null,
  keywords: [],
  excludeKeywords: [],
  maxSize: null,
  autoDownload: false
}

export class Channel extends EventEmitter {
  constructor (dht, options = {}) {
    super()
    this.dht = dht
    this.keypair = options.keypair || null
    this.registry = new TopicRegistry(dht, this.keypair)
    this.manifestClient = options.manifestClient || null
    this.subscriptions = new Map()
    this.filters = new Map()
    this._destroyed = false

    this.registry.on('error', (err) => this.emit('error', err))
  }

  async publish (topicPath, collections, metadata = {}) {
    if (!this.keypair) {
      throw new Error('Keypair required to publish')
    }

    await this.registry.registerPublisher(topicPath, {
      name: metadata.name || 'Anonymous Publisher',
      description: metadata.description,
      ...metadata
    })

    const sequence = metadata.sequence || Date.now()
    const manifest = createManifest(this.keypair, sequence, collections)
    manifest.topics = [topicPath]

    if (this.manifestClient) {
      await this.manifestClient.publish(manifest)
    }

    this.emit('published', { topicPath, manifest })
    return manifest
  }

  async subscribe (topicPath, options = {}) {
    if (this.subscriptions.has(topicPath)) {
      return this.subscriptions.get(topicPath)
    }

    const topicSubscription = await this.registry.subscribe(topicPath, {
      pollInterval: options.pollInterval
    })

    const channelSubscription = new ChannelSubscription(
      this,
      topicPath,
      topicSubscription,
      options
    )

    this.subscriptions.set(topicPath, channelSubscription)
    this.filters.set(topicPath, { ...DEFAULT_FILTER_OPTIONS, ...options.filters })

    if (this.manifestClient) {
      this._watchPublisherManifests(channelSubscription)
    }

    this.emit('subscribed', { topicPath, publishers: topicSubscription.getPublishers() })
    return channelSubscription
  }

  unsubscribe (topicPath) {
    const sub = this.subscriptions.get(topicPath)
    if (sub) {
      sub.destroy()
      this.subscriptions.delete(topicPath)
      this.filters.delete(topicPath)
      this.emit('unsubscribed', { topicPath })
    }
  }

  setFilters (topicPath, filters) {
    const existing = this.filters.get(topicPath) || { ...DEFAULT_FILTER_OPTIONS }
    this.filters.set(topicPath, { ...existing, ...filters })
  }

  getFilters (topicPath) {
    return this.filters.get(topicPath) || { ...DEFAULT_FILTER_OPTIONS }
  }

  async browse (topicPath = '') {
    return this.registry.browse(topicPath)
  }

  async listPublishers (topicPath) {
    return this.registry.listPublishers(topicPath)
  }

  async listSubtopics (topicPath) {
    return this.registry.listSubtopics(topicPath)
  }

  _watchPublisherManifests (channelSubscription) {
    const checkPublisherManifests = async (publishers) => {
      for (const publisher of publishers) {
        try {
          const manifest = await this.manifestClient.getLatest(publisher.pk)
          if (manifest && validateManifest(manifest)) {
            const filtered = this._applyFilters(
              channelSubscription.topicPath,
              manifest
            )
            if (filtered.collections.length > 0) {
              channelSubscription.emit('content', { publisher, manifest: filtered })
            }
          }
        } catch (err) {
          this.emit('error', err)
        }
      }
    }

    channelSubscription.topicSubscription.on('publisher-added', checkPublisherManifests)
    channelSubscription.topicSubscription.on('updated', ({ publishers }) => {
      checkPublisherManifests(publishers)
    })

    checkPublisherManifests(channelSubscription.topicSubscription.getPublishers())
  }

  _applyFilters (topicPath, manifest) {
    const filters = this.getFilters(topicPath)
    const filteredCollections = manifest.collections.filter(collection => {
      if (filters.keywords.length > 0) {
        const title = (collection.title || '').toLowerCase()
        const hasKeyword = filters.keywords.some(kw => title.includes(kw.toLowerCase()))
        if (!hasKeyword) return false
      }

      if (filters.excludeKeywords.length > 0) {
        const title = (collection.title || '').toLowerCase()
        const hasExcluded = filters.excludeKeywords.some(kw => title.includes(kw.toLowerCase()))
        if (hasExcluded) return false
      }

      if (filters.maxSize && collection.totalSize > filters.maxSize) {
        return false
      }

      if (filters.maxAge) {
        const maxAgeMs = this._parseAge(filters.maxAge)
        const age = Date.now() - (collection.timestamp || manifest.timestamp)
        if (age > maxAgeMs) return false
      }

      return true
    })

    return { ...manifest, collections: filteredCollections }
  }

  _parseAge (ageStr) {
    const match = ageStr.match(/^(\d+)([dhms])$/)
    if (!match) return Infinity

    const value = parseInt(match[1], 10)
    const unit = match[2]

    const multipliers = { d: 86400000, h: 3600000, m: 60000, s: 1000 }
    return value * (multipliers[unit] || 1)
  }

  destroy () {
    this._destroyed = true
    
    for (const [, sub] of this.subscriptions) {
      sub.destroy()
    }
    this.subscriptions.clear()
    this.filters.clear()
    this.registry.destroy()
    
    this.emit('destroyed')
  }
}

export class ChannelSubscription extends EventEmitter {
  constructor (channel, topicPath, topicSubscription, options = {}) {
    super()
    this.channel = channel
    this.topicPath = topicPath
    this.topicSubscription = topicSubscription
    this.options = options
    this._destroyed = false

    topicSubscription.on('publisher-added', (publishers) => {
      this.emit('publisher-added', publishers)
    })

    topicSubscription.on('publisher-removed', (publishers) => {
      this.emit('publisher-removed', publishers)
    })

    topicSubscription.on('updated', (data) => {
      this.emit('updated', data)
    })
  }

  getPublishers () {
    return this.topicSubscription.getPublishers()
  }

  getSubtopics () {
    return this.topicSubscription.getSubtopics()
  }

  setFilters (filters) {
    this.channel.setFilters(this.topicPath, filters)
  }

  getFilters () {
    return this.channel.getFilters(this.topicPath)
  }

  unsubscribe () {
    this.channel.unsubscribe(this.topicPath)
  }

  destroy () {
    if (this._destroyed) return
    this._destroyed = true
    this.topicSubscription.unsubscribe()
    this.emit('destroyed')
  }
}

export async function createChannel (dht, options = {}) {
  const keypair = options.keypair || (options.canPublish ? generateKeypair() : null)
  return new Channel(dht, { ...options, keypair })
}

export default Channel
