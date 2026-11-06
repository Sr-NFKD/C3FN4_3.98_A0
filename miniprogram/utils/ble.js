const P = require('./protocol')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const equalUuid = (a, b) => String(a).toLowerCase() === String(b).toLowerCase()
const ERRORS = { 10001: '请开启手机蓝牙，并允许微信使用蓝牙', 10002: '未找到设备，请靠近后重新扫描',
  10003: '连接失败，请确认设备没有被其他手机或安卓 App 占用', 10006: '蓝牙连接已断开，请重新扫描',
  10009: '当前环境不支持 BLE，请在手机微信中预览', 10012: '连接超时，请靠近设备后重试' }
class BleClient {
  constructor(onState, onDevices, api = wx) {
    this.api = api; this.onState = onState; this.onDevices = onDevices
    this.epoch = 0; this.sequence = Math.floor(Math.random() * 65536); this.mtu = 23
    this.ready = false; this.deviceId = ''; this.devices = []; this.stream = new P.Stream()
    this.valueHandler = event => {
      if (event.deviceId !== this.deviceId) return
      const bytes = new Uint8Array(event.value)
      if (equalUuid(event.characteristicId, P.UUID.status)) {
        if (this.statusWait) this.statusWait.resolve(bytes)
      } else if (equalUuid(event.characteristicId, P.UUID.notify)) {
        this.stream.feed(bytes, packet => {
          const pending = this.pending
          if (pending && packet.sequence === pending.sequence && packet.command === (pending.command | 128)) {
            pending.response = packet.payload
            if (pending.written) pending.resolve(packet.payload)
          }
        })
      }
    }
    this.connectionHandler = event => {
      if (event.deviceId === this.deviceId && !event.connected) this.disconnect('连接已断开，同一张图重新连接后可以继续发送')
    }
    this.deviceHandler = event => {
      if (!this.scanning) return
      for (const device of event.devices || []) {
        if (!this.devices.some(item => item.deviceId === device.deviceId)) {
          this.devices.push({ deviceId: device.deviceId, name: device.name || device.localName || '四色墨水屏', RSSI: device.RSSI })
        }
      }
      this.onDevices(this.devices.slice())
    }
    api.onBLECharacteristicValueChange(this.valueHandler)
    api.onBLEConnectionStateChange(this.connectionHandler)
    api.onBluetoothDeviceFound(this.deviceHandler)
  }
  state(message, ready = false, busy = true, progress = 0) {
    this.ready = ready; this.onState({ message, ready, busy, progress })
  }
  call(name, options = {}, timeout = 15000) {
    return new Promise((resolve, reject) => {
      if (typeof this.api[name] !== 'function') { reject(Error('当前微信版本不支持此功能，请升级微信')); return }
      const timer = setTimeout(() => reject(Error('蓝牙操作超时，请重连后重试')), timeout)
      try {
        this.api[name]({ ...options,
          success: value => { clearTimeout(timer); resolve(value) },
          fail: error => { clearTimeout(timer); reject(Error(ERRORS[error.errCode] || error.errMsg || '蓝牙操作失败')) } })
      } catch (error) { clearTimeout(timer); reject(error) }
    })
  }
  alive(epoch) { if (epoch !== this.epoch) throw Error('操作已取消') }
  async openAdapter() {
    const state = await this.call('getBluetoothAdapterState').catch(() => null)
    if (state) {
      if (!state.available) throw Error('请开启手机蓝牙，并允许微信使用蓝牙')
      return
    }
    try { await this.call('openBluetoothAdapter', { mode: 'central' }) }
    catch (error) {
      if (!/already opened/i.test(error.message)) throw error
      // DevTools/hot reload can retain an adapter opened by a previous instance.
      const existing = await this.call('getBluetoothAdapterState')
      if (!existing.available) throw Error('请开启手机蓝牙，并允许微信使用蓝牙')
    }
  }
  async stopScan() {
    clearTimeout(this.scanTimer)
    if (this.scanning) { this.scanning = false; await this.call('stopBluetoothDevicesDiscovery').catch(() => {}) }
  }
  async scan() {
    await this.disconnect()
    const epoch = this.epoch
    this.devices = []; this.onDevices([]); this.state('正在搜索附近的墨水屏（15 秒）…')
    try {
      await this.openAdapter(); this.alive(epoch)
      this.scanning = true
      await this.call('startBluetoothDevicesDiscovery', { services: [P.UUID.service], allowDuplicatesKey: false })
      this.alive(epoch)
      this.scanTimer = setTimeout(async () => {
        await this.stopScan()
        if (epoch === this.epoch) this.state(this.devices.length ? '选择设备后点击连接' : '未找到设备，请确认已上电且未被其他 App 连接', false, false)
      }, 15000)
    } catch (error) {
      if (epoch === this.epoch) { await this.stopScan(); this.state(error.message, false, false) }
    }
  }
  async disconnect(message = '未连接设备，可以先选择图片') {
    ++this.epoch; this.ready = false
    if (this.pending) { this.pending.reject(Error('蓝牙已断开')); this.pending = null }
    if (this.statusWait) { this.statusWait.reject(Error('蓝牙已断开')); this.statusWait = null }
    const id = this.deviceId; this.deviceId = ''; this.stream.reset(); this.info = null
    this.state(message, false, false)
    await this.stopScan()
    if (id) await this.call('closeBLEConnection', { deviceId: id }).catch(() => {})
  }
  async connect(deviceId) {
    await this.disconnect()
    const epoch = this.epoch
    this.deviceId = deviceId; this.state('正在连接墨水屏…')
    try {
      await this.openAdapter(); this.alive(epoch)
      await this.call('createBLEConnection', { deviceId, timeout: 10000 })
      if (epoch !== this.epoch) {
        if (this.deviceId !== deviceId) await this.call('closeBLEConnection', { deviceId }).catch(() => {})
        return
      }
      const services = await this.call('getBLEDeviceServices', { deviceId }); this.alive(epoch)
      const service = services.services.find(s => equalUuid(s.uuid, P.UUID.service))
      if (!service) throw Error('该设备不是当前协议的四色墨水屏')
      this.serviceId = service.uuid
      const result = await this.call('getBLEDeviceCharacteristics', { deviceId, serviceId: this.serviceId }); this.alive(epoch)
      const find = id => result.characteristics.find(c => equalUuid(c.uuid, id))
      const control = find(P.UUID.control), data = find(P.UUID.data), notify = find(P.UUID.notify), status = find(P.UUID.status)
      if (!control || !data || !notify || !control.properties.write || !data.properties.write ||
          !(notify.properties.notify || notify.properties.indicate)) throw Error('设备 BLE 特征不完整或不支持有响应写入')
      this.controlId = control.uuid; this.dataId = data.uuid; this.notifyId = notify.uuid
      this.statusId = status && status.properties.read ? status.uuid : null
      this.mtu = 23
      const system = this.api.getDeviceInfo ? this.api.getDeviceInfo() : this.api.getSystemInfoSync()
      if (system.platform === 'android' && this.api.setBLEMTU)
        await this.call('setBLEMTU', { deviceId, mtu: 247 }).catch(() => {})
      if (this.api.getBLEMTU) {
        const negotiated = await this.call('getBLEMTU', { deviceId, writeType: 'write' }).catch(() => null)
        if (negotiated && negotiated.mtu >= 23) this.mtu = Math.min(517, negotiated.mtu)
      }
      this.alive(epoch)
      await this.call('notifyBLECharacteristicValueChange', { deviceId, serviceId: this.serviceId,
        characteristicId: this.notifyId, state: true })
      await sleep(150); this.alive(epoch)
      this.info = P.parseInfo(await this.request(P.CMD.INFO, new Uint8Array(0), Date.now() + 30000, epoch))
      if (this.statusId) await this.waitForScreen(epoch)
      else this.state('已连接 · EPD-Calendar', true, false)
    } catch (error) { if (epoch === this.epoch) await this.disconnect(error.message) }
  }
  async exchange(command, sequence, payload, epoch) {
    const encoded = P.encode(command, sequence, payload)
    for (let retry = 0; retry < 3; retry++) {
      this.alive(epoch)
      let pending
      const response = new Promise((resolve, reject) => { pending = { command, sequence, resolve, reject, written: false } })
      // A disconnect/notification can arrive before the asynchronous write returns.
      response.catch(() => {})
      this.pending = pending
      let timer
      try {
        for (let offset = 0; offset < encoded.length;) {
          this.alive(epoch)
          const part = encoded.slice(offset, offset + Math.min(this.mtu - 3, 512))
          await this.call('writeBLECharacteristicValue', { deviceId: this.deviceId, serviceId: this.serviceId,
            characteristicId: command === P.CMD.DATA ? this.dataId : this.controlId, value: part.buffer, writeType: 'write' })
          offset += part.length
        }
        this.alive(epoch); pending.written = true
        if (pending.response) pending.resolve(pending.response)
        timer = setTimeout(() => { const error = Error('等待设备响应超时'); error.responseTimeout = true; pending.reject(error) }, 15000)
        return await response
      } catch (error) {
        // Only retry after all GATT writes completed; reuse the sequence for deduplication.
        if (!error.responseTimeout || retry === 2) throw error
      } finally {
        clearTimeout(timer)
        if (this.pending === pending) this.pending = null
      }
    }
  }
  async request(command, payload, deadline, epoch) {
    while (Date.now() < deadline) {
      this.alive(epoch)
      const result = await this.exchange(command, this.sequence++ & 65535, payload, epoch)
      if (!result.length) throw Error('设备响应不完整')
      if (result[0] === 0) return result
      if (result[0] !== 5) throw Error(`设备拒绝请求（错误码 ${result[0]}），请重连后重试`)
      await sleep(500) // A busy response needs a NEW sequence, unlike a lost notification.
    }
    throw Error('设备持续繁忙，请等待刷新结束后重试')
  }
  async readStatus(epoch) {
    this.alive(epoch)
    let wait
    const response = new Promise((resolve, reject) => { wait = { resolve, reject } })
    response.catch(() => {})
    this.statusWait = wait
    const timer = setTimeout(() => wait.reject(Error('读取屏幕状态超时')), 15000)
    try {
      await this.call('readBLECharacteristicValue', { deviceId: this.deviceId, serviceId: this.serviceId, characteristicId: this.statusId })
      return P.parseStatus(await response)
    } finally { clearTimeout(timer); if (this.statusWait === wait) this.statusWait = null }
  }
  async waitForScreen(epoch, session = null) {
    const deadline = Date.now() + 240000
    let waiting = !!session
    while (true) {
      this.alive(epoch)
      const status = await this.readStatus(epoch)
      if (status.state === 4) {
        if (session) throw Error('屏幕刷新失败，请检查设备后重新发送')
        // A failed previous refresh must not prevent a new upload or clear.
        this.state('设备上次刷新失败，检查接线后可以重新发送', true, false)
        return
      }
      if (status.state !== 2) {
        if (waiting && status.state !== 3) throw Error('设备尚未完成刷新，请重连确认')
        if (session && (status.received !== session.frame.length || status.crc !== P.crc32(session.frame)))
          throw Error('设备图片校验结果不一致')
        this.state(waiting ? '刷新完成，可以发送下一张图片' : '已连接 · 768 × 552 四色墨水屏', true, false, waiting ? 100 : 0)
        return
      }
      waiting = true; this.state('墨水屏正在刷新，请等待完成…', false, true, 100)
      if (Date.now() >= deadline) throw Error('屏幕刷新超时，请检查设备')
      await sleep(1000)
    }
  }
  async send(bytes) {
    if (!this.ready) return
    if (bytes.length !== P.FRAME_SIZE) { this.state('图片大小不正确，请重新处理', true, false); return }
    const epoch = this.epoch
    if (!this.session || !this.session.frame.every((value, i) => value === bytes[i]))
      this.session = { frame: bytes.slice(), id: Math.floor(Math.random() * 0x100000000) }
    const session = this.session, frame = session.frame, deadline = Date.now() + 900000
    this.state('正在准备传图…')
    try {
      // Read live mode/slot/upload state before each transfer, including after a clear.
      const info = P.parseInfo(await this.request(P.CMD.INFO, new Uint8Array(0), deadline, epoch))
      const slot = info.uploadActive && info.photoId === session.id ? info.uploadSlot : info.slot
      if (info.uploadActive && info.photoId !== session.id) await this.request(P.CMD.ABORT, new Uint8Array(0), deadline, epoch)
      if (info.mode !== 1) await this.request(P.CMD.MODE, new Uint8Array([1]), deadline, epoch)
      const begin = await this.request(P.CMD.BEGIN, P.begin(frame, session.id, slot), deadline, epoch)
      if (begin.length !== 10 || begin[1] !== slot || P.u32(begin, 6) !== frame.length) throw Error('设备上传规格不匹配')
      let offset = P.u32(begin, 2)
      if (offset > frame.length) throw Error('设备续传位置错误')
      while (offset < frame.length) {
        if (Date.now() >= deadline) throw Error('图片传输超时，请重连后继续发送')
        const packet = P.chunk(frame, offset, this.mtu)
        const result = await this.request(P.CMD.DATA, packet, deadline, epoch)
        if (result.length !== 5) throw Error('设备分包响应错误')
        const next = P.u32(result, 1)
        if (next <= offset || next > offset + packet.length - 4) throw Error('设备接收位置错误')
        offset = next; const progress = Math.floor(offset * 100 / frame.length)
        this.state(`正在传输图片 ${progress}%`, false, true, progress)
      }
      this.state('传输完成，正在校验和保存…', false, true, 100)
      const end = await this.request(P.CMD.END, P.int32(P.crc32(frame)), deadline, epoch)
      if (end.length !== 10 || end[1] !== slot || P.u32(end, 2) !== session.id || P.u32(end, 6) !== frame.length)
        throw Error('设备提交结果不匹配')
      if (this.statusId) await this.waitForScreen(epoch, session)
      else this.state('图片已保存，设备已安排刷新', true, false, 100)
      this.session = null
    } catch (error) { if (epoch === this.epoch) await this.disconnect(error.message) }
  }
  async clear() { const frame = new Uint8Array(P.FRAME_SIZE); frame.fill(0x55); await this.send(frame) }
  dispose() {
    this.disconnect()
    this.api.offBLECharacteristicValueChange(this.valueHandler)
    this.api.offBLEConnectionStateChange(this.connectionHandler)
    this.api.offBluetoothDeviceFound(this.deviceHandler)
  }
}
module.exports = { BleClient }
