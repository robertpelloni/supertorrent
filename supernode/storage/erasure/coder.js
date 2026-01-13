import { EventEmitter } from 'events'
import crypto from 'crypto'

const GF_EXP = new Uint8Array(512)
const GF_LOG = new Uint8Array(256)

function initGaloisField () {
  let x = 1
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x
    GF_LOG[x] = i
    x <<= 1
    if (x & 0x100) {
      x ^= 0x11d
    }
  }
  for (let i = 255; i < 512; i++) {
    GF_EXP[i] = GF_EXP[i - 255]
  }
}

initGaloisField()

function gfMul (a, b) {
  if (a === 0 || b === 0) return 0
  return GF_EXP[GF_LOG[a] + GF_LOG[b]]
}

function gfDiv (a, b) {
  if (b === 0) throw new Error('Division by zero')
  if (a === 0) return 0
  return GF_EXP[(GF_LOG[a] + 255 - GF_LOG[b]) % 255]
}

function gfInverse (a) {
  if (a === 0) throw new Error('Zero has no inverse')
  return GF_EXP[255 - GF_LOG[a]]
}

function gfPow (a, n) {
  if (n === 0) return 1
  if (a === 0) return 0
  return GF_EXP[(GF_LOG[a] * n) % 255]
}

export class ErasureCoder extends EventEmitter {
  constructor (options = {}) {
    super()
    this.dataShards = options.dataShards !== undefined ? options.dataShards : 4
    this.parityShards = options.parityShards !== undefined ? options.parityShards : 2
    this.totalShards = this.dataShards + this.parityShards
    
    if (this.dataShards < 1 || this.dataShards > 255) {
      throw new Error('dataShards must be between 1 and 255')
    }
    if (this.parityShards < 1 || this.parityShards > 255) {
      throw new Error('parityShards must be between 1 and 255')
    }
    if (this.totalShards > 255) {
      throw new Error('Total shards cannot exceed 255')
    }
    
    this.matrix = this._buildVandermondeMatrix()
  }

  _buildVandermondeMatrix () {
    const matrix = []
    
    for (let r = 0; r < this.dataShards; r++) {
      const row = new Uint8Array(this.dataShards)
      row[r] = 1
      matrix.push(row)
    }
    
    for (let r = 0; r < this.parityShards; r++) {
      const row = new Uint8Array(this.dataShards)
      for (let c = 0; c < this.dataShards; c++) {
        row[c] = gfPow(r + 1, c)
      }
      matrix.push(row)
    }
    
    return matrix
  }

  encode (data) {
    const shardSize = Math.ceil(data.length / this.dataShards)
    const paddedSize = shardSize * this.dataShards
    
    const paddedData = Buffer.alloc(paddedSize)
    data.copy(paddedData)
    
    const shards = []
    for (let i = 0; i < this.dataShards; i++) {
      shards.push(Buffer.from(paddedData.subarray(i * shardSize, (i + 1) * shardSize)))
    }
    
    for (let p = 0; p < this.parityShards; p++) {
      const parityShard = Buffer.alloc(shardSize)
      const row = this.matrix[this.dataShards + p]
      
      for (let byteIdx = 0; byteIdx < shardSize; byteIdx++) {
        let val = 0
        for (let d = 0; d < this.dataShards; d++) {
          val ^= gfMul(row[d], shards[d][byteIdx])
        }
        parityShard[byteIdx] = val
      }
      shards.push(parityShard)
    }
    
    this.emit('encoded', {
      originalSize: data.length,
      shardSize,
      dataShards: this.dataShards,
      parityShards: this.parityShards
    })
    
    return {
      shards,
      shardSize,
      originalSize: data.length,
      dataShards: this.dataShards,
      parityShards: this.parityShards
    }
  }

  decode (shards, presentIndices, originalSize, shardSize) {
    if (presentIndices.length < this.dataShards) {
      throw new Error(`Need at least ${this.dataShards} shards, got ${presentIndices.length}`)
    }
    
    const useIndices = presentIndices.slice(0, this.dataShards)
    const useShards = useIndices.map(i => shards[i])
    
    const subMatrix = useIndices.map(i => this.matrix[i])
    const invMatrix = this._invertMatrix(subMatrix)
    
    const decodedShards = []
    for (let d = 0; d < this.dataShards; d++) {
      const shard = Buffer.alloc(shardSize)
      for (let byteIdx = 0; byteIdx < shardSize; byteIdx++) {
        let val = 0
        for (let s = 0; s < this.dataShards; s++) {
          val ^= gfMul(invMatrix[d][s], useShards[s][byteIdx])
        }
        shard[byteIdx] = val
      }
      decodedShards.push(shard)
    }
    
    const result = Buffer.concat(decodedShards).subarray(0, originalSize)
    
    this.emit('decoded', {
      originalSize,
      usedShards: useIndices.length,
      missingShards: this.totalShards - presentIndices.length
    })
    
    return result
  }

  _invertMatrix (matrix) {
    const n = matrix.length
    const augmented = matrix.map((row, i) => {
      const newRow = new Uint8Array(n * 2)
      newRow.set(row)
      newRow[n + i] = 1
      return newRow
    })
    
    for (let col = 0; col < n; col++) {
      let pivotRow = -1
      for (let row = col; row < n; row++) {
        if (augmented[row][col] !== 0) {
          pivotRow = row
          break
        }
      }
      
      if (pivotRow === -1) {
        throw new Error('Matrix is singular and cannot be inverted')
      }
      
      if (pivotRow !== col) {
        [augmented[col], augmented[pivotRow]] = [augmented[pivotRow], augmented[col]]
      }
      
      const pivot = augmented[col][col]
      const pivotInv = gfInverse(pivot)
      for (let c = 0; c < n * 2; c++) {
        augmented[col][c] = gfMul(augmented[col][c], pivotInv)
      }
      
      for (let row = 0; row < n; row++) {
        if (row !== col && augmented[row][col] !== 0) {
          const factor = augmented[row][col]
          for (let c = 0; c < n * 2; c++) {
            augmented[row][c] ^= gfMul(factor, augmented[col][c])
          }
        }
      }
    }
    
    return augmented.map(row => row.slice(n))
  }

  computeShardHash (shard) {
    return crypto.createHash('sha256').update(shard).digest('hex')
  }

  verify (shards, hashes) {
    const results = []
    for (let i = 0; i < shards.length; i++) {
      if (shards[i] && hashes[i]) {
        const computed = this.computeShardHash(shards[i])
        results.push({
          index: i,
          valid: computed === hashes[i],
          expected: hashes[i],
          actual: computed
        })
      } else {
        results.push({ index: i, valid: false, missing: true })
      }
    }
    return results
  }

  getStats () {
    return {
      dataShards: this.dataShards,
      parityShards: this.parityShards,
      totalShards: this.totalShards,
      redundancyRatio: this.parityShards / this.dataShards,
      maxRecoverable: this.parityShards
    }
  }
}

export default ErasureCoder
