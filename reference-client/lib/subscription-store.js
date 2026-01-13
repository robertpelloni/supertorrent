import fs from 'fs'
import path from 'path'
import { EventEmitter } from 'events'

/**
 * SubscriptionStore - Persist channel subscriptions across restarts
 * 
 * Stores subscription state (topics, filters, last seen sequence numbers)
 * to a JSON file for recovery after restart.
 */

const DEFAULT_STORE_PATH = './subscriptions.json'

export class SubscriptionStore extends EventEmitter {
  /**
   * @param {string} storePath - Path to the JSON file
   */
  constructor (storePath = DEFAULT_STORE_PATH) {
    super()
    this.storePath = storePath
    this.data = {
      version: 1,
      subscriptions: {},
      lastUpdated: null
    }
    this._dirty = false
    this._saveDebounceTimer = null
  }

  /**
   * Load subscriptions from disk
   * @returns {Object} - The loaded data
   */
  load () {
    try {
      if (fs.existsSync(this.storePath)) {
        const content = fs.readFileSync(this.storePath, 'utf-8')
        this.data = JSON.parse(content)
        this.emit('loaded', this.data)
      }
    } catch (err) {
      this.emit('error', new Error(`Failed to load subscriptions: ${err.message}`))
      // Start with empty state on error
      this.data = { version: 1, subscriptions: {}, lastUpdated: null }
    }
    return this.data
  }

  /**
   * Save subscriptions to disk (debounced)
   */
  save () {
    this._dirty = true
    
    if (this._saveDebounceTimer) {
      clearTimeout(this._saveDebounceTimer)
    }
    
    this._saveDebounceTimer = setTimeout(() => {
      this._doSave()
    }, 500)
  }

  /**
   * Force immediate save (bypasses debounce)
   */
  saveSync () {
    if (this._saveDebounceTimer) {
      clearTimeout(this._saveDebounceTimer)
      this._saveDebounceTimer = null
    }
    this._doSave()
  }

  _doSave () {
    if (!this._dirty) return
    
    try {
      this.data.lastUpdated = Date.now()
      
      // Ensure directory exists
      const dir = path.dirname(this.storePath)
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true })
      }
      
      fs.writeFileSync(this.storePath, JSON.stringify(this.data, null, 2))
      this._dirty = false
      this.emit('saved', this.data)
    } catch (err) {
      this.emit('error', new Error(`Failed to save subscriptions: ${err.message}`))
    }
  }

  /**
   * Add or update a subscription
   * @param {string} topicPath - e.g., "mp3/electronic"
   * @param {Object} options - Subscription options
   */
  addSubscription (topicPath, options = {}) {
    const existing = this.data.subscriptions[topicPath] || {}
    
    this.data.subscriptions[topicPath] = {
      topicPath,
      addedAt: existing.addedAt || Date.now(),
      updatedAt: Date.now(),
      lastSeq: options.lastSeq || existing.lastSeq || 0,
      lastPollTime: options.lastPollTime || existing.lastPollTime || null,
      filters: options.filters || existing.filters || {},
      enabled: options.enabled !== undefined ? options.enabled : (existing.enabled !== undefined ? existing.enabled : true),
      publisherCache: options.publisherCache || existing.publisherCache || [],
      metadata: { ...existing.metadata, ...options.metadata }
    }
    
    this.save()
    this.emit('subscription-added', this.data.subscriptions[topicPath])
    
    return this.data.subscriptions[topicPath]
  }

  /**
   * Remove a subscription
   * @param {string} topicPath
   */
  removeSubscription (topicPath) {
    if (this.data.subscriptions[topicPath]) {
      const removed = this.data.subscriptions[topicPath]
      delete this.data.subscriptions[topicPath]
      this.save()
      this.emit('subscription-removed', removed)
      return true
    }
    return false
  }

  /**
   * Get a subscription by topic path
   * @param {string} topicPath
   * @returns {Object|null}
   */
  getSubscription (topicPath) {
    return this.data.subscriptions[topicPath] || null
  }

  /**
   * Get all subscriptions
   * @returns {Array}
   */
  getAllSubscriptions () {
    return Object.values(this.data.subscriptions)
  }

  /**
   * Get enabled subscriptions only
   * @returns {Array}
   */
  getEnabledSubscriptions () {
    return this.getAllSubscriptions().filter(sub => sub.enabled)
  }

  /**
   * Update subscription filters
   * @param {string} topicPath
   * @param {Object} filters
   */
  updateFilters (topicPath, filters) {
    const sub = this.data.subscriptions[topicPath]
    if (sub) {
      sub.filters = { ...sub.filters, ...filters }
      sub.updatedAt = Date.now()
      this.save()
      this.emit('filters-updated', { topicPath, filters: sub.filters })
    }
    return sub
  }

  /**
   * Update last seen sequence number (for resuming)
   * @param {string} topicPath
   * @param {number} seq
   */
  updateLastSeq (topicPath, seq) {
    const sub = this.data.subscriptions[topicPath]
    if (sub && seq > sub.lastSeq) {
      sub.lastSeq = seq
      sub.lastPollTime = Date.now()
      this.save()
    }
    return sub
  }

  /**
   * Cache publisher list for a topic (for offline viewing)
   * @param {string} topicPath
   * @param {Array} publishers
   */
  cachePublishers (topicPath, publishers) {
    const sub = this.data.subscriptions[topicPath]
    if (sub) {
      sub.publisherCache = publishers.map(p => ({
        pk: p.pk,
        name: p.name,
        cachedAt: Date.now()
      }))
      this.save()
    }
    return sub
  }

  /**
   * Enable/disable a subscription
   * @param {string} topicPath
   * @param {boolean} enabled
   */
  setEnabled (topicPath, enabled) {
    const sub = this.data.subscriptions[topicPath]
    if (sub) {
      sub.enabled = enabled
      sub.updatedAt = Date.now()
      this.save()
      this.emit('subscription-toggled', { topicPath, enabled })
    }
    return sub
  }

  /**
   * Check if a topic is subscribed
   * @param {string} topicPath
   * @returns {boolean}
   */
  isSubscribed (topicPath) {
    return !!this.data.subscriptions[topicPath]
  }

  /**
   * Export subscriptions as OPML-style format (for backup/sharing)
   * @returns {Object}
   */
  export () {
    return {
      version: this.data.version,
      exportedAt: Date.now(),
      subscriptions: this.getAllSubscriptions().map(sub => ({
        topicPath: sub.topicPath,
        filters: sub.filters,
        metadata: sub.metadata
      }))
    }
  }

  /**
   * Import subscriptions from OPML-style format
   * @param {Object} exportData
   * @param {Object} options - { merge: true/false }
   */
  import (exportData, options = { merge: true }) {
    if (!exportData.subscriptions || !Array.isArray(exportData.subscriptions)) {
      throw new Error('Invalid export data format')
    }

    if (!options.merge) {
      // Clear existing
      this.data.subscriptions = {}
    }

    for (const sub of exportData.subscriptions) {
      this.addSubscription(sub.topicPath, {
        filters: sub.filters,
        metadata: sub.metadata
      })
    }

    this.emit('imported', { count: exportData.subscriptions.length })
    return exportData.subscriptions.length
  }

  /**
   * Clean up and save any pending changes
   */
  destroy () {
    if (this._saveDebounceTimer) {
      clearTimeout(this._saveDebounceTimer)
    }
    if (this._dirty) {
      this._doSave()
    }
  }
}

export default SubscriptionStore
