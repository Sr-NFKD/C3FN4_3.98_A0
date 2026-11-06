const P = require('../../utils/protocol')
const Image = require('../../utils/image')
const { BleClient } = require('../../utils/ble')
const THEMES = {
  light: { background: '#f6f2e9', ink: '#24211d', accent: '#62574b', soft: '#eae4d9',
    card: '#fffdf9', border: '#e4dfd5', buttonText: '#ffffff' },
  dark: { background: '#161a18', ink: '#eceee7', accent: '#d6be91', soft: '#303832',
    card: '#222923', border: '#3b453d', buttonText: '#1b211d' }
}
const APPEARANCES = ['light', 'dark', 'system']
const APPEARANCE_NAMES = ['浅色', '深色', '跟随系统']
Page({
  data: {
    theme: THEMES.light, appearanceLabel: '跟随系统', devices: [], deviceIndex: 0, ready: false, busy: false, progress: 0,
    message: '可以先选图，连接墨水屏后再发送。', imageMessage: '选一张喜欢的图片，生成四色画面。', scanning: false,
    processing: false, hasImage: false, frameReady: false, canvasReady: false,
    zoom: 100, rotation: 0, dither: true, invert: false, canvasHeight: 230,
    depth: { red: 100, black: 100, yellow: 100 },
    depthControls: [{ key: 'red', label: '红色', color: '#b44945' },
      { key: 'black', label: '黑色', color: '#727272' }, { key: 'yellow', label: '黄色', color: '#d9ac37' }]
  },
  onLoad() {
    this.generation = 0
    this.offsetX = 0; this.offsetY = 0
    const saved = wx.getStorageSync('moying-appearance')
    this.appearance = APPEARANCES.includes(saved) ? saved : 'system'
    this.systemTheme = this.readSystemTheme()
    this.themeListener = event => {
      this.systemTheme = event.theme === 'dark' ? 'dark' : 'light'
      if (!this.closed && this.appearance === 'system') this.applyTheme()
    }
    if (wx.onThemeChange) wx.onThemeChange(this.themeListener)
    this.applyTheme()
    this.ble = new BleClient(state => {
      if (this.closed) return
      // Avoid bridging hundreds of identical progress updates over setData.
      const wasBusy = this.data.busy
      if (state.message !== this.data.message || state.progress !== this.data.progress ||
          state.ready !== this.data.ready || state.busy !== this.data.busy)
        this.setData({ ...state, scanning: this.ble ? !!this.ble.scanning : false })
      if (wasBusy && !state.busy) wx.setKeepScreenOn({ keepScreenOn: false })
    }, devices => { if (!this.closed) this.setData({ devices, deviceIndex: 0, scanning: !!this.ble.scanning }) })
  },
  onReady() {
    wx.createSelectorQuery().in(this).select('#preview').fields({ node: true, size: true })
      .select('#buffer').fields({ node: true }).exec(results => {
      if (this.closed) return
      const result = results[0]
      if (!result || !result.node) { this.setData({ imageMessage: '画布初始化失败，请升级微信后重试' }); return }
      this.canvas = result.node
      this.canvas.width = P.WIDTH; this.canvas.height = P.HEIGHT
      this.context = this.canvas.getContext('2d')
      this.canvasWidth = result.width
      this.buffer = results[1].node
      this.buffer.width = P.WIDTH; this.buffer.height = P.HEIGHT
      this.bufferContext = this.buffer.getContext('2d')
      this.context.fillStyle = '#ffffff'; this.context.fillRect(0, 0, P.WIDTH, P.HEIGHT)
      this.setData({ canvasReady: true, canvasHeight: result.width * P.HEIGHT / P.WIDTH })
    })
  },
  readSystemTheme() {
    const info = wx.getAppBaseInfo ? wx.getAppBaseInfo() : wx.getSystemInfoSync()
    return info.theme === 'dark' ? 'dark' : 'light'
  },
  applyTheme() {
    const mode = this.appearance === 'system' ? this.systemTheme : this.appearance
    const theme = THEMES[mode]
    this.setData({ theme, appearanceLabel: APPEARANCE_NAMES[APPEARANCES.indexOf(this.appearance)] })
    wx.setNavigationBarColor({ frontColor: mode === 'dark' ? '#ffffff' : '#000000', backgroundColor: theme.background })
    wx.setBackgroundColor({ backgroundColor: theme.background })
  },
  changeTheme() {
    wx.showActionSheet({ itemList: APPEARANCE_NAMES, success: result => {
      this.appearance = APPEARANCES[result.tapIndex]
      wx.setStorageSync('moying-appearance', this.appearance); this.applyTheme()
    } })
  },
  selectDevice(event) { this.setData({ deviceIndex: Number(event.detail.value) }) },
  scan() { if (!this.data.busy) this.ble.scan() },
  connect() {
    const device = this.data.devices[this.data.deviceIndex]
    if (device && !this.data.ready && (!this.data.busy || this.ble.scanning)) this.ble.connect(device.deviceId)
  },
  disconnect() { this.ble.disconnect() },
  async chooseImage() {
    if (this.data.processing || !this.canvas) return
    try {
      const result = await new Promise((resolve, reject) => wx.chooseMedia({
        count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'],
        success: resolve, fail: reject
      }))
      const path = result.tempFiles[0].tempFilePath
      const generation = ++this.generation
      this.frame = null
      this.setData({ processing: true, frameReady: false, imageMessage: '正在读取图片…' })
      const image = this.canvas.createImage()
      await new Promise((resolve, reject) => {
        image.onload = resolve; image.onerror = () => reject(Error('图片解码失败，请换一张图片'))
        image.src = path
      })
      if (this.closed || generation !== this.generation) return
      this.original = image
      this.offsetX = 0; this.offsetY = 0
      this.setData({ hasImage: true, rotation: 0, zoom: 100 })
      await this.render(generation)
    } catch (error) {
      if (this.closed || /cancel/.test(error.errMsg || '')) return
      this.setData({ processing: false, imageMessage: error.message || error.errMsg || '选图失败' })
    }
  },
  async render(generation = ++this.generation) {
    if (!this.original || !this.context) return
    this.frame = null
    this.setData({ processing: true, frameReady: false })
    try {
      if (this.closed || generation !== this.generation) return
      const { rotation, zoom, dither, invert } = this.data
      const ctx = this.bufferContext
      this.drawSource(ctx)
      const pixels = ctx.getImageData(0, 0, P.WIDTH, P.HEIGHT)
      const frame = await Image.quantize(pixels.data, P.WIDTH, P.HEIGHT, dither, invert,
        () => this.closed || generation !== this.generation, this.data.depth)
      if (this.closed || generation !== this.generation) return
      this.context.putImageData(pixels, 0, 0)
      this.frame = frame
      this.setData({ processing: false, frameReady: true,
        imageMessage: `768 × 552 · ${rotation}° · ${zoom}% · 可拖动调整位置 · 103.5 KB` })
    } catch (error) {
      if (!this.closed && generation === this.generation)
        this.setData({ processing: false, frameReady: false, imageMessage: error.message || '图片处理失败' })
    }
  },
  drawSource(ctx) {
    const { rotation, zoom } = this.data
    const image = this.original
    const scale = Image.scale(image.width, image.height, rotation, zoom)
    ctx.save()
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.fillStyle = '#ffffff'; ctx.fillRect(0, 0, P.WIDTH, P.HEIGHT)
    ctx.translate(P.WIDTH / 2 + this.offsetX, P.HEIGHT / 2 + this.offsetY)
    ctx.rotate(rotation * Math.PI / 180)
    ctx.drawImage(image, -image.width * scale / 2, -image.height * scale / 2,
      image.width * scale, image.height * scale)
    ctx.restore()
  },
  dragStart(event) {
    if (!this.original || this.data.processing || event.touches.length !== 1) return
    const touch = event.touches[0]
    this.drag = { x: touch.x, y: touch.y, offsetX: this.offsetX, offsetY: this.offsetY }
  },
  dragMove(event) {
    if (!this.drag || event.touches.length !== 1) return
    const touch = event.touches[0], ratio = P.WIDTH / this.canvasWidth
    const scale = Image.scale(this.original.width, this.original.height, this.data.rotation, this.data.zoom)
    const sideways = this.data.rotation % 180 !== 0
    const width = (sideways ? this.original.height : this.original.width) * scale
    const height = (sideways ? this.original.width : this.original.height) * scale
    const limitX = (P.WIDTH + width) / 2 - 32, limitY = (P.HEIGHT + height) / 2 - 32
    this.offsetX = Math.max(-limitX, Math.min(limitX, this.drag.offsetX + (touch.x - this.drag.x) * ratio))
    this.offsetY = Math.max(-limitY, Math.min(limitY, this.drag.offsetY + (touch.y - this.drag.y) * ratio))
    this.frame = null
    if (this.data.frameReady) this.setData({ frameReady: false })
    this.drag.moved = true
    this.drawSource(this.context)
  },
  dragEnd() {
    if (!this.drag) return
    const moved = this.drag.moved
    this.drag = null
    if (moved) this.render()
  },
  rotate() {
    if (!this.original || this.data.processing) return
    this.setData({ rotation: (this.data.rotation + 90) % 360 }); this.render()
  },
  zoomChanging(event) { this.frame = null; this.setData({ zoom: event.detail.value, frameReady: false }) },
  zoomChanged(event) {
    if (!this.original || this.data.processing) return
    this.setData({ zoom: event.detail.value }); this.render()
  },
  fit() { if (this.original && !this.data.processing) { this.offsetX = 0; this.offsetY = 0; this.setData({ zoom: 100 }); this.render() } },
  changeDither(event) { this.setData({ dither: event.detail.value }); if (this.original) this.render() },
  changeInvert(event) { this.setData({ invert: event.detail.value }); if (this.original) this.render() },
  depthChanging(event) {
    const key = event.currentTarget.dataset.color, value = Number(event.detail.value)
    if (!this.original || this.data.processing || !['red', 'black', 'yellow'].includes(key) ||
        !Number.isFinite(value) || value < 0 || value > 200) return
    this.frame = null
    this.setData({ depth: { ...this.data.depth, [key]: value }, frameReady: false })
  },
  depthChanged(event) {
    this.depthChanging(event)
    if (this.original && !this.data.processing) this.render()
  },
  resetDepth() {
    if (!this.original || this.data.processing) return
    this.setData({ depth: { red: 100, black: 100, yellow: 100 } }); this.render()
  },
  async send() {
    if (!this.data.ready || !this.frame || this.data.processing) return
    wx.setKeepScreenOn({ keepScreenOn: true })
    try { await this.ble.send(this.frame) } finally { wx.setKeepScreenOn({ keepScreenOn: false }) }
  },
  clear() {
    if (!this.data.ready) return
    wx.showModal({ title: '清屏为白色', content: '这会用全白画面替换设备当前照片槽，是否继续？',
      success: async result => {
        if (!result.confirm || !this.data.ready) return
        wx.setKeepScreenOn({ keepScreenOn: true })
        try { await this.ble.clear() } finally { wx.setKeepScreenOn({ keepScreenOn: false }) }
      } })
  },
  onShow() {
    if (this.appearance === 'system') { this.systemTheme = this.readSystemTheme(); this.applyTheme() }
  },
  onHide() { if (this.ble && this.ble.scanning) this.ble.disconnect('已停止扫描，可以重新搜索设备') },
  onUnload() {
    this.closed = true; ++this.generation
    if (wx.offThemeChange) wx.offThemeChange(this.themeListener)
    this.ble.dispose(); wx.setKeepScreenOn({ keepScreenOn: false })
  },
  onShareAppMessage() { return { title: '墨映 · 把喜欢的画面留在纸上', path: '/pages/index/index' } }
})
