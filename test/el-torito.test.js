import { test, describe } from 'node:test'
import assert from 'node:assert'
import crypto from 'crypto'
import { 
  ISOForge, 
  SeededRNG,
  ElToritoExtension,
  createBootRecord,
  createBootCatalog,
  createMinimalBootImage
} from '../supernode/storage/iso-forge/index.js'

describe('El Torito Boot Support', () => {
  test('createBootRecord has correct structure', () => {
    const bootRecord = createBootRecord(19)
    
    assert.strictEqual(bootRecord[0], 0x00)
    assert.strictEqual(bootRecord.toString('ascii', 1, 6), 'CD001')
    assert.strictEqual(bootRecord[6], 0x01)
    assert.strictEqual(bootRecord.toString('ascii', 7, 30), 'EL TORITO SPECIFICATION')
    assert.strictEqual(bootRecord.readUInt32LE(0x47), 19)
  })

  test('createBootCatalog has validation entry', () => {
    const seed = crypto.randomBytes(32)
    const rng = new SeededRNG(seed)
    const catalog = createBootCatalog(rng, 20, 4)
    
    assert.strictEqual(catalog[0], 0x01)
    assert.strictEqual(catalog[30], 0x55)
    assert.strictEqual(catalog[31], 0xAA)
    
    assert.strictEqual(catalog[32], 0x88)
    assert.strictEqual(catalog.readUInt32LE(40), 20)
  })

  test('createMinimalBootImage has boot signature', () => {
    const seed = crypto.randomBytes(32)
    const rng = new SeededRNG(seed)
    const bootImage = createMinimalBootImage(rng, 4)
    
    assert.strictEqual(bootImage.length, 4 * 512)
    assert.strictEqual(bootImage[510], 0x55)
    assert.strictEqual(bootImage[511], 0xAA)
  })

  test('ElToritoExtension generates consistent sector map', () => {
    const ext = new ElToritoExtension()
    const map = ext.getSectorMap()
    
    assert.strictEqual(map.bootRecord, 17)
    assert.strictEqual(map.bootCatalog, 19)
    assert.strictEqual(map.bootImage, 20)
  })

  test('ISOForge bootable option generates El Torito structures', () => {
    const seed = crypto.randomBytes(32)
    const forge = new ISOForge({ bootable: true })
    const iso = forge.generate(seed, 'nano')
    
    const bootRecord = iso.subarray(17 * 2048, 18 * 2048)
    assert.strictEqual(bootRecord[0], 0x00)
    assert.strictEqual(bootRecord.toString('ascii', 1, 6), 'CD001')
    assert.strictEqual(bootRecord.toString('ascii', 7, 30), 'EL TORITO SPECIFICATION')
    
    const vdt = iso.subarray(18 * 2048, 19 * 2048)
    assert.strictEqual(vdt[0], 255)
  })

  test('ISOForge bootable generates same ISO from same seed', () => {
    const seed = crypto.randomBytes(32)
    const forge = new ISOForge({ bootable: true })
    
    const iso1 = forge.generate(seed, 'nano')
    const iso2 = forge.generate(seed, 'nano')
    
    assert.deepStrictEqual(iso1, iso2)
  })

  test('ISOForge bootable generateSector matches generate', () => {
    const seed = crypto.randomBytes(32)
    const forge = new ISOForge({ bootable: true })
    const iso = forge.generate(seed, 'nano')
    
    for (const sectorIndex of [0, 16, 17, 18, 19, 20, 24, 25, 100]) {
      const sector = forge.generateSector(seed, sectorIndex, 'nano')
      const expected = iso.subarray(sectorIndex * 2048, (sectorIndex + 1) * 2048)
      assert.deepStrictEqual(sector, expected, `Sector ${sectorIndex} mismatch`)
    }
  })

  test('non-bootable ISO has VDT at sector 17', () => {
    const seed = crypto.randomBytes(32)
    const forge = new ISOForge({ bootable: false })
    const iso = forge.generate(seed, 'nano')
    
    const vdt = iso.subarray(17 * 2048, 18 * 2048)
    assert.strictEqual(vdt[0], 255)
    assert.strictEqual(vdt.toString('ascii', 1, 6), 'CD001')
  })
})
