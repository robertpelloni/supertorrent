import { SeededRNG } from './forge.js'

const SECTOR_SIZE = 2048
const EL_TORITO_BOOT_RECORD_SECTOR = 17
const BOOT_CATALOG_SECTOR = 19
const BOOT_IMAGE_SECTOR = 20

const PLATFORM_X86 = 0x00
const PLATFORM_PPC = 0x01
const PLATFORM_MAC = 0x02
const PLATFORM_EFI = 0xEF

const MEDIA_NO_EMULATION = 0x00
const MEDIA_FLOPPY_1_2 = 0x01
const MEDIA_FLOPPY_1_44 = 0x02
const MEDIA_FLOPPY_2_88 = 0x03
const MEDIA_HARD_DISK = 0x04

export function createBootRecord (bootCatalogSector) {
  const record = Buffer.alloc(SECTOR_SIZE)
  
  record[0] = 0x00
  record.write('CD001', 1, 'ascii')
  record[6] = 0x01
  record.write('EL TORITO SPECIFICATION', 7, 'ascii')
  
  record.writeUInt32LE(bootCatalogSector, 0x47)
  
  return record
}

export function createBootCatalog (rng, bootImageSector, bootImageSectors = 4) {
  const catalog = Buffer.alloc(SECTOR_SIZE)
  let offset = 0
  
  catalog[offset] = 0x01
  catalog[offset + 1] = PLATFORM_X86
  catalog.writeUInt16LE(0, offset + 2)
  
  const idStrings = [
    'SUPERNODE BOOT', 'MESH LINUX', 'FREEDOM OS',
    'SOVEREIGN BOOT', 'LIBERTY LINUX'
  ]
  const idString = rng.pick(idStrings)
  catalog.write(idString.padEnd(24, '\0'), offset + 4, 'ascii')
  
  const checksum = calculateValidationChecksum(catalog.subarray(offset, offset + 32))
  catalog.writeUInt16LE(checksum, offset + 28)
  
  catalog[offset + 30] = 0x55
  catalog[offset + 31] = 0xAA
  
  offset += 32
  
  catalog[offset] = 0x88
  catalog[offset + 1] = MEDIA_NO_EMULATION
  catalog.writeUInt16LE(0, offset + 2)
  catalog[offset + 4] = 0x00
  catalog[offset + 5] = 0x00
  catalog.writeUInt16LE(bootImageSectors, offset + 6)
  catalog.writeUInt32LE(bootImageSector, offset + 8)
  
  return catalog
}

function calculateValidationChecksum (data) {
  let sum = 0
  for (let i = 0; i < data.length; i += 2) {
    sum += data.readUInt16LE(i)
  }
  return (0x10000 - (sum & 0xFFFF)) & 0xFFFF
}

export function createMinimalBootImage (rng, sectorCount = 4) {
  const image = Buffer.alloc(sectorCount * 512)
  
  const bootCode = [
    0xFA,
    0x31, 0xC0,
    0x8E, 0xD8,
    0x8E, 0xC0,
    0x8E, 0xD0,
    0xBC, 0x00, 0x7C,
    0xFB,
    
    0xBE, 0x30, 0x7C,
    0xAC,
    0x08, 0xC0,
    0x74, 0x09,
    0xB4, 0x0E,
    0xBB, 0x07, 0x00,
    0xCD, 0x10,
    0xEB, 0xF2,
    
    0xFA,
    0xF4,
    0xEB, 0xFC
  ]
  
  Buffer.from(bootCode).copy(image, 0)
  
  const messages = [
    'Supernode Boot v1.0',
    'Mesh Linux Loader',
    'Freedom OS Starting',
    'Sovereign Boot Init',
    'Decentralized Boot'
  ]
  const message = rng.pick(messages)
  image.write(message + '\r\n\0', 0x30, 'ascii')
  
  image[510] = 0x55
  image[511] = 0xAA
  
  const padding = rng.bytes(image.length - 512)
  padding.copy(image, 512)
  
  return image
}

export function createEfiBootImage (rng, sectorCount = 4) {
  const image = Buffer.alloc(sectorCount * SECTOR_SIZE)
  
  image.write('MZ', 0, 'ascii')
  image.writeUInt16LE(0x90, 2)
  image.writeUInt16LE(3, 4)
  image.writeUInt16LE(0, 6)
  image.writeUInt16LE(4, 8)
  image.writeUInt16LE(0, 10)
  image.writeUInt16LE(0xFFFF, 12)
  image.writeUInt16LE(0, 14)
  image.writeUInt16LE(0xB8, 16)
  image.writeUInt32LE(0x80, 60)
  
  const peOffset = 0x80
  image.write('PE\0\0', peOffset, 'ascii')
  image.writeUInt16LE(0x8664, peOffset + 4)
  image.writeUInt16LE(1, peOffset + 6)
  image.writeUInt32LE(0, peOffset + 8)
  image.writeUInt32LE(0, peOffset + 12)
  image.writeUInt32LE(0, peOffset + 16)
  image.writeUInt16LE(0xF0, peOffset + 20)
  image.writeUInt16LE(0x22, peOffset + 22)
  
  const padding = rng.bytes(image.length - 256)
  padding.copy(image, 256)
  
  return image
}

export class ElToritoExtension {
  constructor () {
    this.bootCatalogSector = BOOT_CATALOG_SECTOR
    this.bootImageSector = BOOT_IMAGE_SECTOR
    this.bootImageSectors = 4
  }

  generateBootRecord () {
    return createBootRecord(this.bootCatalogSector)
  }

  generateBootCatalog (rng) {
    return createBootCatalog(rng, this.bootImageSector, this.bootImageSectors)
  }

  generateBootImage (rng, type = 'bios') {
    if (type === 'efi' || type === 'uefi') {
      return createEfiBootImage(rng, this.bootImageSectors)
    }
    return createMinimalBootImage(rng, this.bootImageSectors)
  }

  getSectorMap () {
    return {
      bootRecord: EL_TORITO_BOOT_RECORD_SECTOR,
      bootCatalog: this.bootCatalogSector,
      bootImage: this.bootImageSector,
      bootImageEnd: this.bootImageSector + Math.ceil((this.bootImageSectors * 512) / SECTOR_SIZE)
    }
  }
}

export default ElToritoExtension
