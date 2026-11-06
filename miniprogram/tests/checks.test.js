const test = require('node:test')
const assert = require('node:assert/strict')
const P = require('../utils/protocol')
const Image = require('../utils/image')
const { BleClient } = require('../utils/ble')

test('EP CRCs, frame boundaries and fragmented/corrupted notifications', () => {
  const bytes = Uint8Array.from(Buffer.from('123456789'))
  assert.equal(P.crc16(bytes), 0x29b1); assert.equal(P.crc32(bytes), 0xcbf43926)
  const one = P.encode(1, 65535, bytes), two = P.encode(11, 0, new Uint8Array([0, 1, 2]))
  // Independently checked with Python binascii.crc_hqx(header + payload, 0xffff).
  assert.equal(Buffer.from(one).toString('hex'), '45500101ffff090031323334353637383935f5')
  const damaged = one.slice(); damaged[9] ^= 255
  const all = Uint8Array.from([...new Uint8Array([9, 8]), ...damaged, ...one, ...two])
  const stream = new P.Stream(), packets = []
  for (const byte of all) stream.feed(new Uint8Array([byte]), p => packets.push(p))
  assert.equal(packets.length, 2); assert.equal(packets[0].sequence, 65535)
  assert.deepEqual(packets[1].payload, new Uint8Array([0, 1, 2]))
  stream.feed(P.encode(1, 2, bytes), p => packets.push(p)); assert.equal(packets.length, 3)
})

test('all negotiated packet sizes reconstruct the 105984-byte framebuffer', () => {
  const frame = Uint8Array.from({ length: P.FRAME_SIZE }, (_, i) => i % 251)
  const begin = P.begin(frame, 0xfedcba98, 3)
  assert.equal(P.u32(begin, 0), 0xfedcba98); assert.equal(begin[4], 3)
  assert.equal(P.u32(begin, 5), frame.length); assert.equal(P.u32(begin, 9), P.crc32(frame))
  for (const mtu of [23, 185, 247, 517]) {
    const received = new Uint8Array(frame.length)
    for (let offset = 0; offset < frame.length;) {
      const chunk = P.chunk(frame, offset, mtu)
      assert.equal(P.u32(chunk, 0), offset)
      assert.ok(P.encode(P.CMD.DATA, 1, chunk).length <= Math.min(mtu - 3, 512))
      received.set(chunk.subarray(4), offset); offset += chunk.length - 4
    }
    assert.deepEqual(received, frame)
  }
  assert.throws(() => P.begin(new Uint8Array(4), 1, 0))
  assert.throws(() => P.chunk(frame, frame.length, 23))
})

test('fit, rotation, zoom, four-colour packing and preview agree', async () => {
  for (const rotation of [0, 90, 180, 270]) {
    const scale = Image.scale(1200, 800, rotation, 100)
    const width = rotation % 180 ? 552 : 768, height = rotation % 180 ? 768 : 552
    assert.ok(1200 * scale <= width + .001 && 800 * scale <= height + .001)
    assert.ok(Math.abs(Image.scale(1200, 800, rotation, 300) - scale * 3) < 0.000001)
    assert.ok(Math.abs(Image.scale(1200, 800, rotation, 25) - scale / 4) < 0.000001)
  }
  assert.throws(() => Image.scale(1200, 800, 45, 100))
  const rgba = new Uint8ClampedArray([0,0,0,255, 255,255,255,255, 255,255,0,255, 255,0,0,255])
  assert.deepEqual(await Image.quantize(rgba, 4, 1, true, false), new Uint8Array([0x1b]))
  const white = new Uint8ClampedArray(P.WIDTH * P.HEIGHT * 4); white.fill(255)
  const frame = await Image.quantize(white, P.WIDTH, P.HEIGHT, true, false)
  assert.equal(frame.length, P.FRAME_SIZE); assert.ok(frame.every(value => value === 0x55))
  const pixels = Uint8ClampedArray.from({ length: 8 * 8 * 4 }, (_, i) => i % 4 === 3 ? 255 : (i * 17) % 256)
  const packed = await Image.quantize(pixels, 8, 8, true, true)
  for (let i = 0; i < 64; i++) assert.deepEqual(Array.from(pixels.subarray(i * 4, i * 4 + 3)), Image.COLORS[(packed[i >> 2] >> (6 - 2 * (i % 4))) & 3])
  await assert.rejects(Image.quantize(rgba, 4, 1, false, false, () => true))
})

test('independent ink depths preserve defaults, lighten each ink and match transmitted pixels', async () => {
  const source = Uint8ClampedArray.from({ length: 32 * 16 * 4 }, (_, i) =>
    i % 4 === 3 ? 255 : [180, 60, 60, 80, 80, 80, 200, 200, 70][Math.floor(i / 4) % 3 * 3 + i % 4])
  const original = await Image.quantize(source.slice(), 32, 16, true, false)
  assert.deepEqual(await Image.quantize(source.slice(), 32, 16, true, false, undefined,
    { red: 100, black: 100, yellow: 100 }), original)
  for (const [name, code] of [['red', 3], ['black', 0], ['yellow', 2]]) {
    for (const dither of [false, true]) {
      const solid = Uint8ClampedArray.from({ length: 32 * 16 * 4 }, (_, i) =>
        i % 4 === 3 ? 255 : Image.COLORS[code][i % 4])
      const counts = []
      for (const amount of [0, 50, 100, 200]) {
        const pixels = solid.slice()
        const packed = await Image.quantize(pixels, 32, 16, dither, false, undefined, { [name]: amount })
        let count = 0
        for (let i = 0; i < 512; i++) {
          const actual = (packed[i >> 2] >> (6 - 2 * (i % 4))) & 3
          assert.deepEqual(Array.from(pixels.subarray(i * 4, i * 4 + 3)), Image.COLORS[actual])
          assert.ok(actual === code || actual === 1)
          if (actual === code) count++
        }
        counts.push(count)
      }
      assert.equal(counts[0], 0); assert.equal(counts[2], 512)
      assert.ok(counts.every((value, i) => i === 0 || value >= counts[i - 1]))
      if (dither) assert.ok(counts[1] > 0 && counts[1] < 512)
      const other = name === 'red' ? 'yellow' : 'red'
      const unchanged = await Image.quantize(solid.slice(), 32, 16, dither, false, undefined, { [other]: 0 })
      assert.ok(unchanged.every(byte => byte === code * 85))
    }
  }
  await assert.rejects(Image.quantize(source.slice(), 32, 16, true, false, undefined, { red: NaN }), /深浅/)
})

class FakeDevice {
  constructor() {
    this.stream = new P.Stream(); this.offset = 0; this.active = false; this.photoId = 0
    this.frame = new Uint8Array(P.FRAME_SIZE); this.state = 0; this.mode = 0; this.commands = []
    this.nextBusy = false; this.dropAt = -1; this.subscribed = false
  }
  onBLECharacteristicValueChange(cb) { this.onValue = cb }
  onBLEConnectionStateChange(cb) { this.onConnection = cb }
  onBluetoothDeviceFound(cb) { this.onDevices = cb }
  offBLECharacteristicValueChange() {}
  offBLEConnectionStateChange() {}
  offBluetoothDeviceFound() {}
  openBluetoothAdapter(o) { o.success({}) }
  createBLEConnection(o) { o.success({}) }
  closeBLEConnection(o) { o.success({}) }
  getDeviceInfo() { return { platform: 'android' } }
  setBLEMTU(o) { assert.equal(o.mtu, 247); o.success({}) }
  getBLEMTU(o) { o.success({ mtu: 247 }) }
  getBLEDeviceServices(o) { o.success({ services: [{ uuid: P.UUID.service.toUpperCase() }] }) }
  getBLEDeviceCharacteristics(o) {
    o.success({ characteristics: [
      { uuid: P.UUID.control, properties: { write: true } }, { uuid: P.UUID.data, properties: { write: true } },
      { uuid: P.UUID.notify, properties: { notify: true } }, { uuid: P.UUID.status, properties: { read: true } }
    ] })
  }
  notifyBLECharacteristicValueChange(o) { this.subscribed = true; o.success({}) }
  readBLECharacteristicValue(o) {
    const status = new Uint8Array(20), view = new DataView(status.buffer)
    status[0] = 1; status[1] = this.state
    view.setUint32(4, this.state === 3 ? this.frame.length : 0, true)
    view.setUint32(8, this.frame.length, true); view.setUint16(12, 768, true); view.setUint16(14, 552, true)
    view.setUint32(16, P.crc32(this.frame), true)
    this.onValue({ deviceId: 'test', characteristicId: P.UUID.status, value: status.buffer }); o.success({})
  }
  writeBLECharacteristicValue(o) {
    assert.ok(this.subscribed); assert.equal(o.writeType, 'write')
    assert.ok(o.value.byteLength <= 244)
    if (this.dropAt >= 0 && this.offset >= this.dropAt && o.characteristicId === P.UUID.data) {
      this.dropAt = -1; o.fail({ errCode: 10006 }); return
    }
    this.stream.feed(new Uint8Array(o.value), packet => {
      this.commands.push({ command: packet.command, sequence: packet.sequence })
      let payload = new Uint8Array([0])
      if (packet.command === P.CMD.DATA && this.nextBusy) { payload[0] = 5; this.nextBusy = false }
      else if (packet.command === P.CMD.INFO) {
        payload = new Uint8Array(46); payload[1] = 1; payload[2] = this.mode
        payload[32] = Number(this.active); payload[33] = this.active ? 0 : 255
        payload.set(P.int32(this.photoId), 34); payload.set(P.int32(this.frame.length), 38); payload.set(P.int32(this.offset), 42)
      } else if (packet.command === P.CMD.MODE) this.mode = packet.payload[0]
      else if (packet.command === P.CMD.ABORT) { this.active = false; this.offset = 0 }
      else if (packet.command === P.CMD.BEGIN) {
        const id = P.u32(packet.payload, 0)
        if (!this.active || this.photoId !== id) this.offset = 0
        this.photoId = id; this.active = true
        payload = new Uint8Array(10); payload.set(P.int32(this.offset), 2); payload.set(P.int32(this.frame.length), 6)
      } else if (packet.command === P.CMD.DATA) {
        assert.equal(P.u32(packet.payload, 0), this.offset)
        this.frame.set(packet.payload.subarray(4), this.offset); this.offset += packet.payload.length - 4
        payload = new Uint8Array(5); payload.set(P.int32(this.offset), 1)
      } else if (packet.command === P.CMD.END) {
        assert.equal(this.offset, this.frame.length); assert.equal(P.u32(packet.payload, 0), P.crc32(this.frame))
        this.state = 3; this.active = false
        payload = new Uint8Array(10); payload.set(P.int32(this.photoId), 2); payload.set(P.int32(this.frame.length), 6)
      }
      const response = P.encode(packet.command | 128, packet.sequence, payload)
      for (let i = 0; i < response.length; i += 17) this.onValue({ deviceId: 'test', characteristicId: P.UUID.notify, value: response.slice(i, i + 17).buffer })
    })
    // Deliberately notify before write success, as some phones do.
    o.success({})
  }
}

test('full BLE handshake, busy retry, verified refresh, disconnect and resume', async () => {
  const device = new FakeDevice(), states = []
  const client = new BleClient(state => states.push(state), () => {}, device)
  await client.connect('test'); assert.equal(client.ready, true)
  const frame = Uint8Array.from({ length: P.FRAME_SIZE }, (_, i) => (i * 23) % 256)
  device.nextBusy = true; device.dropAt = 2000
  await client.send(frame)
  assert.equal(client.ready, false); assert.equal(device.active, true)
  assert.ok(device.offset >= 2000 && device.offset < frame.length)
  const before = device.offset
  const busyCommands = device.commands.filter(c => c.command === P.CMD.DATA)
  assert.notEqual(busyCommands[0].sequence, busyCommands[1].sequence)
  await client.connect('test'); await client.send(frame)
  assert.equal(client.ready, true); assert.deepEqual(device.frame, frame)
  assert.ok(states.some(s => s.progress >= Math.floor(before * 100 / frame.length)))
  assert.match(states.at(-1).message, /刷新完成/)
  await client.clear(); assert.ok(device.frame.every(value => value === 0x55))
  await client.disconnect(); device.state = 4
  await client.connect('test'); assert.equal(client.ready, true)
  await client.clear(); assert.ok(device.frame.every(value => value === 0x55))
  await client.disconnect(); client.dispose()
})

test('invalid device info and incompatible screen are rejected', () => {
  assert.throws(() => P.parseInfo(new Uint8Array(46)))
  assert.throws(() => P.parseStatus(new Uint8Array(20)))
  const info = new Uint8Array(46); info[1] = 1; info[32] = 1; info[33] = 4
  assert.throws(() => P.parseInfo(info))
})

test('an already open Bluetooth adapter is reused; disabled Bluetooth still fails', async () => {
  const device = new FakeDevice()
  let opens = 0
  device.openBluetoothAdapter = o => { opens++; o.fail({ errMsg: 'openBluetoothAdapter:fail already opened' }) }
  device.getBluetoothAdapterState = o => o.success({ available: true })
  const client = new BleClient(() => {}, () => {}, device)
  await client.openAdapter(); assert.equal(opens, 0)
  let reads = 0
  device.getBluetoothAdapterState = o => ++reads === 1 ? o.fail({ errCode: 10000 }) : o.success({ available: true })
  await client.openAdapter(); assert.equal(opens, 1)
  device.getBluetoothAdapterState = o => o.success({ available: false })
  await assert.rejects(client.openAdapter(), /开启手机蓝牙/)
  client.dispose()
})

test('light/dark/system appearance persists and follows system without resetting BLE or image', () => {
  const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path')
  let definition, listener, menu, removed, osTheme = 'dark'
  const storage = {}, bars = []
  const wx = {
    getStorageSync: key => storage[key], setStorageSync: (key, value) => { storage[key] = value },
    getAppBaseInfo: () => ({ theme: osTheme }),
    onThemeChange: cb => { listener = cb }, offThemeChange: cb => { removed = cb },
    setNavigationBarColor: value => bars.push(value), setBackgroundColor() {}, setKeepScreenOn() {},
    showActionSheet: options => { menu = options }
  }
  class FakeBle { dispose() { this.disposed = true } }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/index/index.js'), 'utf8'), {
    wx, Page: value => { definition = value },
    require: name => name.endsWith('/ble') ? { BleClient: FakeBle } : name.endsWith('/protocol') ? P : Image
  })
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)),
    setData(value) { Object.assign(this.data, value) } }
  page.onLoad()
  assert.equal(page.appearance, 'system'); assert.equal(page.data.theme.background, '#161a18')
  const frame = new Uint8Array([1,2,3]), ble = page.ble
  page.frame = frame; page.data.ready = true
  page.changeTheme(); assert.equal(Array.from(menu.itemList).join(','), '浅色,深色,跟随系统')
  menu.success({ tapIndex: 0 })
  assert.equal(storage['moying-appearance'], 'light'); assert.equal(bars.at(-1).frontColor, '#000000')
  listener({ theme: 'dark' }); assert.equal(page.data.theme.background, '#f6f2e9')
  menu.success({ tapIndex: 1 })
  assert.equal(page.data.theme.background, '#161a18'); assert.equal(bars.at(-1).frontColor, '#ffffff')
  osTheme = 'light'; listener({ theme: 'light' })
  assert.equal(page.data.theme.background, '#161a18')
  menu.success({ tapIndex: 2 }); assert.equal(page.data.theme.background, '#f6f2e9')
  osTheme = 'dark'; page.onShow(); assert.equal(page.data.theme.background, '#161a18')
  assert.equal(page.frame, frame); assert.equal(page.ble, ble); assert.equal(page.data.ready, true)
  page.onUnload(); assert.equal(removed, listener); assert.equal(ble.disposed, true)
})

test('preview drag uses canvas coordinates, commits on release and fit resets position', () => {
  const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path')
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/index/index.js'), 'utf8'), {
    Page: value => { definition = value }, require: name => name.endsWith('/ble') ? {} : name.endsWith('/protocol') ? P : Image
  })
  const page = { ...definition, data: { ...definition.data, hasImage: true, frameReady: true },
    setData(value) { Object.assign(this.data, value) }, original: { width: 768, height: 552 },
    canvasWidth: 384, offsetX: 0, offsetY: 0, frame: new Uint8Array([1]) }
  let draws = 0, renders = 0
  page.drawSource = () => draws++
  page.render = () => renders++
  page.dragStart({ touches: [{ x: 20, y: 30 }] })
  page.dragMove({ touches: [{ x: 70, y: 50 }] })
  assert.equal(page.offsetX, 100); assert.equal(page.offsetY, 40)
  assert.equal(page.frame, null); assert.equal(page.data.frameReady, false)
  assert.equal(draws, 1); assert.equal(renders, 0)
  page.dragEnd(); assert.equal(renders, 1); assert.equal(page.drag, null)
  page.fit(); assert.equal(page.offsetX, 0); assert.equal(page.offsetY, 0)
  const depthEvent = { currentTarget: { dataset: { color: 'red' } }, detail: { value: 65 } }
  page.frame = new Uint8Array([1]); page.data.frameReady = true
  page.depthChanging(depthEvent)
  assert.equal(page.data.depth.red, 65); assert.equal(page.data.depth.black, 100)
  assert.equal(page.frame, null); assert.equal(page.data.frameReady, false)
  assert.equal(renders, 2)
  page.depthChanged(depthEvent); assert.equal(renders, 3)
  page.resetDepth(); assert.equal(page.data.depth.red, 100); assert.equal(renders, 4)
  page.data.processing = true; page.dragStart({ touches: [{ x: 0, y: 0 }] })
  page.depthChanged(depthEvent); assert.equal(page.data.depth.red, 100); assert.equal(renders, 4)
  assert.equal(page.drag, null)
  const wxml = fs.readFileSync(path.join(__dirname, '../pages/index/index.wxml'), 'utf8')
  assert.ok(wxml.includes('wx:if="{{!hasImage}}"'))
})
