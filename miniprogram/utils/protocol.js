// Same EP v1 wire format as the Android application and ESP32-C3 firmware.
const WIDTH = 768, HEIGHT = 552, FRAME_SIZE = WIDTH * HEIGHT / 4
const uuid = id => `7b4d000${id}-6a6c-4c61-9f20-65706463616c`
const UUID = { service: uuid(1), control: uuid(2), notify: uuid(3), data: uuid(4), status: uuid(5) }
const CMD = { INFO: 1, MODE: 3, BEGIN: 10, DATA: 11, END: 12, ABORT: 13 }
function view(bytes) { return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength) }
function u32(bytes, offset) {
  if (offset < 0 || offset + 4 > bytes.length) throw Error('设备响应不完整')
  return view(bytes).getUint32(offset, true)
}
function int32(value) { const b = new Uint8Array(4); view(b).setUint32(0, value, true); return b }
function crc16(bytes, count = bytes.length) {
  let crc = 0xffff
  for (let i = 0; i < count; i++) {
    crc ^= bytes[i] << 8
    for (let j = 0; j < 8; j++) crc = ((crc << 1) ^ ((crc & 0x8000) ? 0x1021 : 0)) & 0xffff
  }
  return crc
}
const crcTable = new Uint32Array(256)
for (let n = 0; n < 256; n++) {
  let c = n
  for (let bit = 0; bit < 8; bit++) c = (c >>> 1) ^ ((c & 1) ? 0xedb88320 : 0)
  crcTable[n] = c >>> 0
}
function crc32(bytes) {
  let c = 0xffffffff
  for (let i = 0; i < bytes.length; i++) c = crcTable[(c ^ bytes[i]) & 255] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
function encode(command, sequence, payload = new Uint8Array(0)) {
  if (payload.length > 1024) throw Error('协议数据过长')
  const bytes = new Uint8Array(payload.length + 10), b = view(bytes)
  bytes.set([0x45, 0x50, 1, command])
  b.setUint16(4, sequence, true); b.setUint16(6, payload.length, true)
  bytes.set(payload, 8); b.setUint16(bytes.length - 2, crc16(bytes, bytes.length - 2), true)
  return bytes
}
class Stream {
  constructor() { this.pending = new Uint8Array(1034); this.length = 0 }
  reset() { this.length = 0 }
  drop(count) { this.pending.copyWithin(0, count, this.length); this.length -= count }
  feed(bytes, receive) {
    for (const value of bytes) {
      if (this.length === this.pending.length) this.drop(1)
      this.pending[this.length++] = value
      while (this.length >= 2) {
        const p = this.pending
        if (p[0] !== 0x45 || p[1] !== 0x50) { this.drop(1); continue }
        if (this.length < 8) break
        const size = p[6] | (p[7] << 8), total = size + 10
        if (p[2] !== 1 || size > 1024) { this.drop(2); continue }
        if (this.length < total) break
        if (crc16(p, total - 2) !== (p[total - 2] | (p[total - 1] << 8))) { this.drop(2); continue }
        const packet = { command: p[3], sequence: p[4] | (p[5] << 8), payload: p.slice(8, total - 2) }
        this.drop(total); receive(packet)
      }
    }
  }
}
function parseInfo(p) {
  if (p.length < 46 || p[0] !== 0 || p[1] !== 1 || p[2] > 3 || p[4] > 3) throw Error('设备信息协议不匹配')
  let pos = 30
  for (let i = 0; i < 2; i++) {
    if (pos >= p.length) throw Error('设备信息不完整')
    pos += 1 + p[pos]
  }
  if (p.length - pos !== 14) throw Error('设备信息字段错误')
  const info = { mode: p[2], slot: p[4], uploadActive: !!p[pos], uploadSlot: p[pos + 1],
    photoId: u32(p, pos + 2), total: u32(p, pos + 6), offset: u32(p, pos + 10) }
  if (info.uploadActive && (info.uploadSlot > 3 || info.total !== FRAME_SIZE || info.offset > info.total))
    throw Error('设备上传状态错误')
  return info
}
function parseStatus(p) {
  if (p.length !== 20 || p[0] !== 1 || p[1] > 4 || u32(p, 4) > FRAME_SIZE ||
      u32(p, 8) !== FRAME_SIZE || view(p).getUint16(12, true) !== WIDTH || view(p).getUint16(14, true) !== HEIGHT)
    throw Error('设备屏幕规格不匹配')
  return { state: p[1], error: p[2], received: u32(p, 4), crc: u32(p, 16) }
}
function begin(frame, photoId, slot) {
  if (frame.length !== FRAME_SIZE || slot < 0 || slot > 3) throw Error('图片帧或槽位错误')
  const b = new Uint8Array(13)
  b.set(int32(photoId)); b[4] = slot; b.set(int32(frame.length), 5); b.set(int32(crc32(frame)), 9)
  return b
}
function chunk(frame, offset, mtu) {
  if (offset < 0 || offset >= frame.length || mtu < 23) throw Error('分包参数错误')
  const count = Math.min(Math.min(mtu - 3, 512) - 14, frame.length - offset)
  const b = new Uint8Array(count + 4)
  b.set(int32(offset)); b.set(frame.subarray(offset, offset + count), 4)
  return b
}
module.exports = { WIDTH, HEIGHT, FRAME_SIZE, UUID, CMD, u32, int32, crc16, crc32, encode, Stream, parseInfo, parseStatus, begin, chunk }
