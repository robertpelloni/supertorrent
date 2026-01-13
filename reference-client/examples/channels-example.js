#!/usr/bin/env node

import DHT from 'bittorrent-dht'
import { generateKeypair } from '../lib/crypto.js'
import { Channel, createChannel } from '../lib/channels.js'
import { SubscriptionStore } from '../lib/subscription-store.js'

async function main () {
  console.log('=== Supertorrent Channels Example ===\n')
  
  const keypair = generateKeypair()
  console.log('Generated identity:', keypair.publicKey.toString('hex').slice(0, 32) + '...')
  
  console.log('\nStarting DHT...')
  const dht = new DHT()
  await new Promise(resolve => dht.on('ready', resolve))
  console.log('DHT ready')
  
  const channel = await createChannel(dht, { keypair })
  const store = new SubscriptionStore('./example-subscriptions.json')
  store.load()
  
  console.log('\n--- PUBLISHER FLOW ---')
  
  const collections = [{
    title: 'January 2026 Mix',
    items: [
      { name: 'track01.mp3', size: 5242880 },
      { name: 'track02.mp3', size: 4718592 },
      { name: 'track03.mp3', size: 6291456 }
    ]
  }]
  
  console.log('Publishing to mp3/electronic...')
  const manifest = await channel.publish('mp3/electronic', collections, {
    name: 'DJ Example',
    description: 'Electronic music releases'
  })
  console.log('Published! Manifest seq:', manifest.sequence)
  
  console.log('\n--- SUBSCRIBER FLOW ---')
  
  console.log('Browsing mp3 topic...')
  const browseResult = await channel.browse('mp3')
  console.log('Subtopics:', browseResult.subtopics)
  console.log('Publishers:', browseResult.publishers.length)
  
  console.log('\nSubscribing to mp3/electronic with filters...')
  store.addSubscription('mp3/electronic', {
    filters: {
      keywords: ['mix'],
      maxAge: '7d'
    }
  })
  
  const subscription = await channel.subscribe('mp3/electronic', {
    filters: {
      keywords: ['mix'],
      maxAge: '7d'
    }
  })
  
  subscription.on('content', ({ publisher, manifest: m }) => {
    console.log('\nNew content from:', publisher.name)
    console.log('Collections:', m.collections.map(c => c.title))
  })
  
  subscription.on('publisher-added', (publishers) => {
    console.log('New publisher(s) joined:', publishers.map(p => p.name))
  })
  
  console.log('Active publishers:', subscription.getPublishers().length)
  
  console.log('\n--- SUBSCRIPTION STORE ---')
  console.log('Persisted subscriptions:', store.getAllSubscriptions().map(s => s.topicPath))
  
  console.log('\n--- CLEANUP ---')
  
  await new Promise(resolve => setTimeout(resolve, 2000))
  
  subscription.unsubscribe()
  channel.destroy()
  store.destroy()
  dht.destroy()
  
  console.log('Done!')
}

main().catch(err => {
  console.error('Error:', err)
  process.exit(1)
})
