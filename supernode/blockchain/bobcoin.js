import { EventEmitter } from 'events'
import crypto from 'crypto'
import { Connection, Keypair, PublicKey } from '@solana/web3.js'
import bs58 from 'bs58'

import { 
  createRpc, 
  bn, 
  buildAndSignTx, 
  sendAndConfirmTx 
} from "@lightprotocol/stateless.js";
import { 
  createMint, 
  mintTo, 
  transfer 
} from "@lightprotocol/compressed-token";

import { X402PaymentHandler } from 'x402-solana/server'
import { createX402Client } from 'x402-solana/client'

export class BobcoinBridge extends EventEmitter {
  constructor (options = {}) {
    super()
    this.rpcEndpoint = options.rpcEndpoint || 'https://api.devnet.solana.com'
    this.lightRpcEndpoint = options.lightRpcEndpoint || 'https://devnet.helius-rpc.com/?api-key=' + (options.heliusApiKey || 'demo')
    
    this.walletKey = options.walletKey
    this.contractAddress = options.contractAddress
    this.network = options.network || 'devnet'
    this.connected = false
    this.connection = null
    this.lightRpc = null
    this.payer = null
    this.pendingProofs = new Map()
    
    this.paymentHandler = null
    this.paymentClient = null
    this.treasuryAddress = null
  }

  async connect () {
    try {
      this.connection = new Connection(this.rpcEndpoint, 'confirmed')
      
      this.lightRpc = createRpc(this.lightRpcEndpoint)
      
      const version = await this.connection.getVersion()
      
      if (this.walletKey) {
        if (Array.isArray(this.walletKey)) {
          this.payer = Keypair.fromSecretKey(Uint8Array.from(this.walletKey))
        } else if (typeof this.walletKey === 'string') {
          try {
            this.payer = Keypair.fromSecretKey(bs58.decode(this.walletKey))
          } catch (e) {
            this.payer = Keypair.generate()
          }
        } else {
          this.payer = Keypair.generate()
        }
      } else {
        this.payer = Keypair.generate()
      }

      this.connected = true
      
      this.treasuryAddress = this.payer.publicKey.toBase58()
      this._initPaymentSystem()

      this.emit('connected', { 
        endpoint: this.rpcEndpoint, 
        network: this.network, 
        version,
        publicKey: this.payer.publicKey.toBase58() 
      })
      return true
    } catch (err) {
      this.connected = false
      this.emit('error', err)
      throw err
    }
  }

  async disconnect () {
    this.connected = false
    this.connection = null
    this.lightRpc = null
    this.emit('disconnected')
  }

  _initPaymentSystem() {
    try {
        this.paymentHandler = new X402PaymentHandler({
            network: this.network === 'mainnet' ? 'solana-mainnet' : 'solana-devnet',
            treasuryAddress: this.treasuryAddress,
            facilitatorUrl: 'https://facilitator.payai.network'
        })

        this.paymentClient = createX402Client({
            wallet: {
                address: this.payer.publicKey.toBase58(),
                signTransaction: async (tx) => {
                    tx.partialSign(this.payer)
                    return tx
                }
            },
            network: this.network === 'mainnet' ? 'solana-mainnet' : 'solana-devnet'
        })
    } catch (e) {
        console.warn('Failed to init x402 payment system:', e.message)
    }
  }

  async createPaymentRequest(resourceId, amountSol) {
    if (!this.paymentHandler) throw new Error('Payment handler not initialized')
    
    const requirements = await this.paymentHandler.createPaymentRequirements({
        amount: amountSol || 0.00001,
        asset: {
            address: 'So11111111111111111111111111111111111111112',
            decimals: 9
        }
    }, `bobcoin://${resourceId}`)

    return requirements
  }

  async verifyPeerPayment(paymentHeader, resourceId, amountSol) {
    if (!this.paymentHandler) throw new Error('Payment handler not initialized')

    const requirements = await this.paymentHandler.createPaymentRequirements({
         amount: amountSol || 0.00001,
         asset: {
             address: 'So11111111111111111111111111111111111111112',
             decimals: 9
         }
    }, `bobcoin://${resourceId}`)

    try {
        const verification = await this.paymentHandler.verifyPayment(
            paymentHeader, 
            requirements
        )
        
        if (verification.isValid) {
            await this.paymentHandler.settlePayment(paymentHeader, requirements)
            return true
        }
        return false
    } catch (e) {
        console.error('Payment verification failed:', e.message)
        return false
    }
  }

  async payForResource(resourceId, requirements) {
      if (!this.paymentClient) throw new Error('Payment client not initialized')
      
      try {
          const paymentResult = await this.paymentClient.handlePaymentRequirements(
              requirements, 
              `bobcoin://${resourceId}`
          )
          return paymentResult.headers
      } catch (e) {
          throw new Error(`Payment generation failed: ${e.message}`)
      }
  }

  async createCompressedMint(decimals = 9) {
    this._ensureConnected()
    try {
        const { mint, transactionSignature } = await createMint(
            this.lightRpc,
            this.payer,
            this.payer.publicKey,
            decimals
        );
        return { mint: mint.toBase58(), txHash: transactionSignature };
    } catch (err) {
        throw new Error(`Failed to create compressed mint: ${err.message}`)
    }
  }

  async transferPrivate(mintAddress, recipientAddress, amount) {
    this._ensureConnected()
    try {
        const mint = new PublicKey(mintAddress)
        const recipient = new PublicKey(recipientAddress)
        const amountBn = bn(amount)

        const accounts = await this.lightRpc.getCompressedTokenAccountsByOwner(
            this.payer.publicKey,
            { mint }
        );

        const { selectMinCompressedTokenAccountsForTransfer } = await import("@lightprotocol/compressed-token");
        const [inputAccounts] = selectMinCompressedTokenAccountsForTransfer(
            accounts.items,
            amountBn
        );

        const proof = await this.lightRpc.getValidityProof(
            inputAccounts.map(acc => bn(acc.compressedAccount.hash))
        );

        const ix = await transfer({
            payer: this.payer.publicKey,
            mint,
            amount: amountBn,
            owner: this.payer,
            toAddress: recipient,
            recentInputStateRootIndices: proof.rootIndices,
            recentValidityProof: proof.compressedProof,
        });

        const { blockhash } = await this.lightRpc.getLatestBlockhash();
        const { ComputeBudgetProgram } = await import("@solana/web3.js");
        
        const tx = buildAndSignTx(
            [ComputeBudgetProgram.setComputeUnitLimit({ units: 350_000 }), ix],
            this.payer,
            blockhash
        );

        const txHash = await sendAndConfirmTx(this.lightRpc, tx);
        return { txHash, amount, recipient: recipientAddress }

    } catch (err) {
        throw new Error(`Private transfer failed: ${err.message}`)
    }
  }

  async connect () {
    try {
      this.connection = new Connection(this.rpcEndpoint, 'confirmed')
      
      this.lightRpc = createRpc(this.lightRpcEndpoint)
      
      const version = await this.connection.getVersion()
      
      if (this.walletKey) {
        if (Array.isArray(this.walletKey)) {
          this.payer = Keypair.fromSecretKey(Uint8Array.from(this.walletKey))
        } else if (typeof this.walletKey === 'string') {
          try {
            this.payer = Keypair.fromSecretKey(bs58.decode(this.walletKey))
          } catch (e) {
            this.payer = Keypair.generate()
          }
        } else {
          this.payer = Keypair.generate()
        }
      } else {
        this.payer = Keypair.generate()
      }

      this.connected = true
      this.emit('connected', { 
        endpoint: this.rpcEndpoint, 
        network: this.network, 
        version,
        publicKey: this.payer.publicKey.toBase58() 
      })
      return true
    } catch (err) {
      this.connected = false
      this.emit('error', err)
      throw err
    }
  }

  async disconnect () {
    this.connected = false
    this.connection = null
    this.lightRpc = null
    this.emit('disconnected')
  }

  async createCompressedMint(decimals = 9) {
    this._ensureConnected()
    try {
        const { mint, transactionSignature } = await createMint(
            this.lightRpc,
            this.payer,
            this.payer.publicKey,
            decimals
        );
        return { mint: mint.toBase58(), txHash: transactionSignature };
    } catch (err) {
        throw new Error(`Failed to create compressed mint: ${err.message}`)
    }
  }

  async transferPrivate(mintAddress, recipientAddress, amount) {
    this._ensureConnected()
    try {
        const mint = new PublicKey(mintAddress)
        const recipient = new PublicKey(recipientAddress)
        const amountBn = bn(amount)

        const accounts = await this.lightRpc.getCompressedTokenAccountsByOwner(
            this.payer.publicKey,
            { mint }
        );

        const { selectMinCompressedTokenAccountsForTransfer } = await import("@lightprotocol/compressed-token");
        const [inputAccounts] = selectMinCompressedTokenAccountsForTransfer(
            accounts.items,
            amountBn
        );

        const proof = await this.lightRpc.getValidityProof(
            inputAccounts.map(acc => bn(acc.compressedAccount.hash))
        );

        const ix = await transfer({
            payer: this.payer.publicKey,
            mint,
            amount: amountBn,
            owner: this.payer,
            toAddress: recipient,
            recentInputStateRootIndices: proof.rootIndices,
            recentValidityProof: proof.compressedProof,
        });

        const { blockhash } = await this.lightRpc.getLatestBlockhash();
        const { ComputeBudgetProgram } = await import("@solana/web3.js");
        
        const tx = buildAndSignTx(
            [ComputeBudgetProgram.setComputeUnitLimit({ units: 350_000 }), ix],
            this.payer,
            blockhash
        );

        const txHash = await sendAndConfirmTx(this.lightRpc, tx);
        return { txHash, amount, recipient: recipientAddress }

    } catch (err) {
        throw new Error(`Private transfer failed: ${err.message}`)
    }
  }

  async connect () {
    try {
      this.connection = new Connection(this.rpcEndpoint, 'confirmed')
      
      // Initialize Light Protocol RPC
      this.lightRpc = createRpc(this.lightRpcEndpoint)
      
      const version = await this.connection.getVersion()
      
      if (this.walletKey) {
        if (Array.isArray(this.walletKey)) {
          this.payer = Keypair.fromSecretKey(Uint8Array.from(this.walletKey))
        } else if (typeof this.walletKey === 'string') {
          try {
            this.payer = Keypair.fromSecretKey(bs58.decode(this.walletKey))
          } catch (e) {
            this.payer = Keypair.generate()
          }
        } else {
          this.payer = Keypair.generate()
        }
      } else {
        this.payer = Keypair.generate()
      }

      this.connected = true
      this.emit('connected', { 
        endpoint: this.rpcEndpoint, 
        network: this.network, 
        version,
        publicKey: this.payer.publicKey.toBase58() 
      })
      return true
    } catch (err) {
      this.connected = false
      this.emit('error', err)
      throw err
    }
  }

  async disconnect () {
    this.connected = false
    this.connection = null
    this.lightRpc = null
    this.emit('disconnected')
  }

  // ZK Compression: Create a new compressed token mint (for game assets/scores)
  async createCompressedMint(decimals = 9) {
    this._ensureConnected()
    try {
        const { mint, transactionSignature } = await createMint(
            this.lightRpc,
            this.payer,
            this.payer.publicKey,
            decimals
        );
        return { mint: mint.toBase58(), txHash: transactionSignature };
    } catch (err) {
        throw new Error(`Failed to create compressed mint: ${err.message}`)
    }
  }

  // ZK Compression: Private transfer
  async transferPrivate(mintAddress, recipientAddress, amount) {
    this._ensureConnected()
    try {
        const mint = new PublicKey(mintAddress)
        const recipient = new PublicKey(recipientAddress)
        const amountBn = bn(amount)

        // 1. Get compressed accounts
        const accounts = await this.lightRpc.getCompressedTokenAccountsByOwner(
            this.payer.publicKey,
            { mint }
        );

        // 2. Select accounts for transfer
        const { selectMinCompressedTokenAccountsForTransfer } = await import("@lightprotocol/compressed-token");
        const [inputAccounts] = selectMinCompressedTokenAccountsForTransfer(
            accounts.items,
            amountBn
        );

        // 3. Get validity proof
        const proof = await this.lightRpc.getValidityProof(
            inputAccounts.map(acc => bn(acc.compressedAccount.hash))
        );

        // 4. Build transfer instruction
        const ix = await transfer({
            payer: this.payer.publicKey,
            mint,
            amount: amountBn,
            owner: this.payer,
            toAddress: recipient,
            recentInputStateRootIndices: proof.rootIndices,
            recentValidityProof: proof.compressedProof,
        });

        // 5. Send transaction
        const { blockhash } = await this.lightRpc.getLatestBlockhash();
        const { ComputeBudgetProgram } = await import("@solana/web3.js");
        
        const tx = buildAndSignTx(
            [ComputeBudgetProgram.setComputeUnitLimit({ units: 350_000 }), ix],
            this.payer,
            blockhash
        );

        const txHash = await sendAndConfirmTx(this.lightRpc, tx);
        return { txHash, amount, recipient: recipientAddress }

    } catch (err) {
        throw new Error(`Private transfer failed: ${err.message}`)
    }
  }

  async connect () {
    try {
      this.connection = new Connection(this.rpcEndpoint, 'confirmed')
      
      // Initialize Light Protocol RPC
      this.lightRpc = createRpc(this.lightRpcEndpoint)
      
      const version = await this.connection.getVersion()
      
      if (this.walletKey) {
        if (Array.isArray(this.walletKey)) {
          this.payer = Keypair.fromSecretKey(Uint8Array.from(this.walletKey))
        } else if (typeof this.walletKey === 'string') {
          try {
            this.payer = Keypair.fromSecretKey(bs58.decode(this.walletKey))
          } catch (e) {
            this.payer = Keypair.generate()
          }
        } else {
          this.payer = Keypair.generate()
        }
      } else {
        this.payer = Keypair.generate()
      }

      this.connected = true
      this.emit('connected', { 
        endpoint: this.rpcEndpoint, 
        network: this.network, 
        version,
        publicKey: this.payer.publicKey.toBase58() 
      })
      return true
    } catch (err) {
      this.connected = false
      this.emit('error', err)
      throw err
    }
  }

  async disconnect () {
    this.connected = false
    this.connection = null
    this.lightRpc = null
    this.emit('disconnected')
  }

  // ZK Compression: Create a new compressed token mint (for game assets/scores)
  async createCompressedMint(decimals = 9) {
    this._ensureConnected()
    try {
        const { mint, transactionSignature } = await createMint(
            this.lightRpc,
            this.payer,
            this.payer.publicKey,
            decimals
        );
        return { mint: mint.toBase58(), txHash: transactionSignature };
    } catch (err) {
        throw new Error(`Failed to create compressed mint: ${err.message}`)
    }
  }

  // ZK Compression: Private transfer
  async transferPrivate(mintAddress, recipientAddress, amount) {
    this._ensureConnected()
    try {
        const mint = new PublicKey(mintAddress)
        const recipient = new PublicKey(recipientAddress)
        const amountBn = bn(amount)

        // 1. Get compressed accounts
        const accounts = await this.lightRpc.getCompressedTokenAccountsByOwner(
            this.payer.publicKey,
            { mint }
        );

        // 2. Select accounts for transfer
        const { selectMinCompressedTokenAccountsForTransfer } = await import("@lightprotocol/compressed-token");
        const [inputAccounts] = selectMinCompressedTokenAccountsForTransfer(
            accounts.items,
            amountBn
        );

        // 3. Get validity proof
        const proof = await this.lightRpc.getValidityProof(
            inputAccounts.map(acc => bn(acc.compressedAccount.hash))
        );

        // 4. Build transfer instruction
        const ix = await transfer({
            payer: this.payer.publicKey,
            mint,
            amount: amountBn,
            owner: this.payer,
            toAddress: recipient,
            recentInputStateRootIndices: proof.rootIndices,
            recentValidityProof: proof.compressedProof,
        });

        // 5. Send transaction
        const { blockhash } = await this.lightRpc.getLatestBlockhash();
        const { ComputeBudgetProgram } = await import("@solana/web3.js");
        
        const tx = buildAndSignTx(
            [ComputeBudgetProgram.setComputeUnitLimit({ units: 350_000 }), ix],
            this.payer,
            blockhash
        );

        const txHash = await sendAndConfirmTx(this.lightRpc, tx);
        return { txHash, amount, recipient: recipientAddress }

    } catch (err) {
        throw new Error(`Private transfer failed: ${err.message}`)
    }
  }

  async connect () {
    try {
      this.connection = new Connection(this.rpcEndpoint, 'confirmed')
      
      // Initialize Light Protocol RPC
      this.lightRpc = createRpc(this.lightRpcEndpoint)
      
      const version = await this.connection.getVersion()
      
      if (this.walletKey) {
        if (Array.isArray(this.walletKey)) {
          this.payer = Keypair.fromSecretKey(Uint8Array.from(this.walletKey))
        } else if (typeof this.walletKey === 'string') {
          try {
            this.payer = Keypair.fromSecretKey(bs58.decode(this.walletKey))
          } catch (e) {
            this.payer = Keypair.generate()
          }
        } else {
          this.payer = Keypair.generate()
        }
      } else {
        this.payer = Keypair.generate()
      }

      this.connected = true
      this.emit('connected', { 
        endpoint: this.rpcEndpoint, 
        network: this.network, 
        version,
        publicKey: this.payer.publicKey.toBase58() 
      })
      return true
    } catch (err) {
      this.connected = false
      this.emit('error', err)
      throw err
    }
  }

  async disconnect () {
    this.connected = false
    this.connection = null
    this.lightRpc = null
    this.emit('disconnected')
  }

  // ZK Compression: Create a new compressed token mint (for game assets/scores)
  async createCompressedMint(decimals = 9) {
    this._ensureConnected()
    try {
        const { mint, transactionSignature } = await createMint(
            this.lightRpc,
            this.payer,
            this.payer.publicKey,
            decimals
        );
        return { mint: mint.toBase58(), txHash: transactionSignature };
    } catch (err) {
        throw new Error(`Failed to create compressed mint: ${err.message}`)
    }
  }

  // ZK Compression: Private transfer
  async transferPrivate(mintAddress, recipientAddress, amount) {
    this._ensureConnected()
    try {
        const mint = new PublicKey(mintAddress)
        const recipient = new PublicKey(recipientAddress)
        const amountBn = bn(amount)

        // 1. Get compressed accounts
        const accounts = await this.lightRpc.getCompressedTokenAccountsByOwner(
            this.payer.publicKey,
            { mint }
        );

        // 2. Select accounts for transfer
        const { selectMinCompressedTokenAccountsForTransfer } = await import("@lightprotocol/compressed-token");
        const [inputAccounts] = selectMinCompressedTokenAccountsForTransfer(
            accounts.items,
            amountBn
        );

        // 3. Get validity proof
        const proof = await this.lightRpc.getValidityProof(
            inputAccounts.map(acc => bn(acc.compressedAccount.hash))
        );

        // 4. Build transfer instruction
        const ix = await transfer({
            payer: this.payer.publicKey,
            mint,
            amount: amountBn,
            owner: this.payer,
            toAddress: recipient,
            recentInputStateRootIndices: proof.rootIndices,
            recentValidityProof: proof.compressedProof,
        });

        // 5. Send transaction
        const { blockhash } = await this.lightRpc.getLatestBlockhash();
        const { ComputeBudgetProgram } = await import("@solana/web3.js");
        
        const tx = buildAndSignTx(
            [ComputeBudgetProgram.setComputeUnitLimit({ units: 350_000 }), ix],
            this.payer,
            blockhash
        );

        const txHash = await sendAndConfirmTx(this.lightRpc, tx);
        return { txHash, amount, recipient: recipientAddress }

    } catch (err) {
        throw new Error(`Private transfer failed: ${err.message}`)
    }
  }


  async registerStorageProvider (capacity, pricePerGBHour) {
    this._ensureConnected()
    
    const providerId = crypto.randomBytes(16).toString('hex')
    
    this.emit('provider-registered', {
      providerId,
      capacity,
      pricePerGBHour
    })
    
    return { providerId, txHash: this._mockTxHash() }
  }

  async createStorageDeal (params) {
    this._ensureConnected()
    const { fileId, size, duration, maxPrice, redundancy = 3 } = params
    
    const dealId = crypto.randomBytes(16).toString('hex')
    const totalCost = this._calculateCost(size, duration, maxPrice, redundancy)
    
    this.emit('deal-created', {
      dealId,
      fileId,
      size,
      duration,
      redundancy,
      totalCost
    })
    
    return {
      dealId,
      txHash: this._mockTxHash(),
      totalCost,
      expiresAt: Date.now() + duration
    }
  }

  async submitStorageProof (dealId, chunkHashes, merkleRoot) {
    this._ensureConnected()
    
    const proofId = crypto.randomBytes(16).toString('hex')
    this.pendingProofs.set(proofId, {
      dealId,
      chunkHashes,
      merkleRoot,
      submittedAt: Date.now()
    })
    
    this.emit('proof-submitted', { proofId, dealId, merkleRoot })
    
    return { proofId, txHash: this._mockTxHash() }
  }

  async verifyStorageProof (proofId) {
    this._ensureConnected()
    
    const proof = this.pendingProofs.get(proofId)
    if (!proof) throw new Error(`Unknown proof: ${proofId}`)
    
    const isValid = true
    
    this.emit('proof-verified', { proofId, isValid })
    
    return { isValid, txHash: this._mockTxHash() }
  }

  async claimReward (dealId) {
    this._ensureConnected()
    
    const reward = Math.floor(Math.random() * 1000) + 100
    
    this.emit('reward-claimed', { dealId, reward })
    
    return { reward, txHash: this._mockTxHash() }
  }

  async getBalance () {
    this._ensureConnected()
    return { bob: 10000, staked: 5000, pending: 250 }
  }

  async getDealStatus (dealId) {
    this._ensureConnected()
    return {
      dealId,
      status: 'active',
      proofsSubmitted: 42,
      lastProofAt: Date.now() - 3600000,
      earnedRewards: 1500
    }
  }

  async listActiveDeals () {
    this._ensureConnected()
    return []
  }

  _ensureConnected () {
    if (!this.connected) throw new Error('Not connected to blockchain')
  }

  _mockTxHash () {
    return '0x' + crypto.randomBytes(32).toString('hex')
  }

  _calculateCost (size, duration, maxPrice, redundancy) {
    const gbHours = (size / (1024 * 1024 * 1024)) * (duration / 3600000)
    return Math.min(gbHours * 10 * redundancy, maxPrice || Infinity)
  }
}

export class SupernodeWithBobcoin extends EventEmitter {
  constructor (supernodeNetwork, bobcoinOptions = {}) {
    super()
    this.network = supernodeNetwork
    this.bobcoin = new BobcoinBridge(bobcoinOptions)
    this.activeDeals = new Map()
    this.proofInterval = bobcoinOptions.proofIntervalMs || 3600000
    this.proofTimers = new Map()
    
    this._setupEventForwarding()
  }

  _setupEventForwarding () {
    this.bobcoin.on('connected', (info) => this.emit('blockchain-connected', info))
    this.bobcoin.on('deal-created', (info) => this.emit('deal-created', info))
    this.bobcoin.on('proof-submitted', (info) => this.emit('proof-submitted', info))
    this.bobcoin.on('reward-claimed', (info) => this.emit('reward-claimed', info))
  }

  async connectBlockchain () {
    return this.bobcoin.connect()
  }

  async ingestWithDeal (fileBuffer, fileName, masterKey, dealParams = {}) {
    const result = await this.network.ingestFile(fileBuffer, fileName, masterKey)
    
    const deal = await this.bobcoin.createStorageDeal({
      fileId: result.fileId,
      size: fileBuffer.length,
      duration: dealParams.duration || 30 * 24 * 3600000,
      maxPrice: dealParams.maxPrice,
      redundancy: dealParams.redundancy
    })
    
    this.activeDeals.set(deal.dealId, {
      fileId: result.fileId,
      chunkHashes: result.chunkHashes,
      deal
    })
    
    this._scheduleProofs(deal.dealId)
    
    return { ...result, deal }
  }

  async _scheduleProofs (dealId) {
    const dealInfo = this.activeDeals.get(dealId)
    if (!dealInfo) return
    
    const submitProof = async () => {
      try {
        const merkleRoot = this._computeMerkleRoot(dealInfo.chunkHashes)
        await this.bobcoin.submitStorageProof(dealId, dealInfo.chunkHashes, merkleRoot)
      } catch (err) {
        this.emit('proof-error', { dealId, error: err.message })
      }
    }
    
    await submitProof()
    
    const timer = setInterval(submitProof, this.proofInterval)
    this.proofTimers.set(dealId, timer)
  }

  _computeMerkleRoot (hashes) {
    if (hashes.length === 0) return crypto.createHash('sha256').digest('hex')
    if (hashes.length === 1) return hashes[0]
    
    const leaves = [...hashes]
    while (leaves.length > 1) {
      const newLevel = []
      for (let i = 0; i < leaves.length; i += 2) {
        const left = leaves[i]
        const right = leaves[i + 1] || left
        const combined = crypto.createHash('sha256')
          .update(left + right)
          .digest('hex')
        newLevel.push(combined)
      }
      leaves.length = 0
      leaves.push(...newLevel)
    }
    return leaves[0]
  }

  async claimRewards (dealId) {
    return this.bobcoin.claimReward(dealId)
  }

  async getStats () {
    const networkStats = this.network.stats()
    const balance = await this.bobcoin.getBalance()
    
    return {
      ...networkStats,
      blockchain: {
        connected: this.bobcoin.connected,
        balance,
        activeDeals: this.activeDeals.size
      }
    }
  }

  destroy () {
    for (const timer of this.proofTimers.values()) {
      clearInterval(timer)
    }
    this.proofTimers.clear()
    this.bobcoin.disconnect()
    this.network.destroy()
  }
}

export default BobcoinBridge
