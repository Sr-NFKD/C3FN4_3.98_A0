const { WIDTH, HEIGHT } = require('./protocol')
const COLORS = [[0, 0, 0], [255, 255, 255], [255, 255, 0], [255, 0, 0]]
function scale(width, height, rotation, percent) {
  if (width <= 0 || height <= 0 || rotation % 90 || percent < 25 || percent > 300) throw Error('图片缩放参数错误')
  const sideways = rotation % 180 !== 0
  return Math.min((sideways ? HEIGHT : WIDTH) / width, (sideways ? WIDTH : HEIGHT) / height) * percent / 100
}
// Mutates RGBA pixels so the canvas preview is exactly the frame sent to the device.
async function quantize(pixels, width, height, dither, invert, cancelled = () => false, depth = {}) {
  if (pixels.length !== width * height * 4 || width <= 0 || height <= 0 || (width * height) % 4)
    throw Error('图片尺寸错误')
  const frame = new Uint8Array(width * height / 4)
  const adjustments = ['red', 'black', 'yellow'].map(name => {
    const value = depth[name] === undefined ? 100 : depth[name]
    if (!Number.isFinite(value) || value < 0 || value > 200) throw Error('颜色深浅参数错误')
    return value / 100 - 1
  })
  let current = new Float32Array((width + 2) * 3), next = new Float32Array(current.length)
  for (let y = 0; y < height; y++) {
    if (y % 16 === 0) {
      await new Promise(resolve => setTimeout(resolve, 0))
      if (cancelled()) throw Error('图片处理已取消')
    }
    const direction = y % 2 === 0 ? 1 : -1
    for (let step = 0; step < width; step++) {
      const x = direction === 1 ? step : width - 1 - step, index = y * width + x, pos = index * 4, slot = (x + 1) * 3
      const values = [0, 0, 0]
      for (let c = 0; c < 3; c++) {
        // Composite transparent pixels onto white, just like the Canvas composition.
        const base = pixels[pos + c] * pixels[pos + 3] / 255 + 255 - pixels[pos + 3]
        values[c] = invert ? 255 - base : base
      }
      // Adjust source ink amounts before diffusion so lighter tones retain their dot density.
      const black = (255 - Math.max(...values)) * adjustments[1]
      const red = Math.max(0, values[0] - values[1]) * adjustments[0]
      const yellow = Math.max(0, Math.min(values[0], values[1]) - values[2]) * adjustments[2]
      values[0] -= black
      values[1] -= black + red
      values[2] -= black + red + yellow
      for (let c = 0; c < 3; c++)
        values[c] = Math.max(0, Math.min(255, Math.max(0, Math.min(255, values[c])) + current[slot + c]))
      let code = 0, best = Infinity
      for (let c = 0; c < 4; c++) {
        const dr = values[0] - COLORS[c][0], dg = values[1] - COLORS[c][1], db = values[2] - COLORS[c][2]
        const distance = dr * dr * 0.30 + dg * dg * 0.59 + db * db * 0.11
        if (distance < best) { best = distance; code = c }
      }
      frame[index >> 2] |= code << (6 - 2 * (index % 4))
      for (let c = 0; c < 3; c++) {
        pixels[pos + c] = COLORS[code][c]
        if (dither) {
          const e = (values[c] - COLORS[code][c]) / 16
          current[slot + direction * 3 + c] += e * 7
          next[slot - direction * 3 + c] += e * 3
          next[slot + c] += e * 5
          next[slot + direction * 3 + c] += e
        }
      }
      pixels[pos + 3] = 255
    }
    const swap = current; current = next; next = swap; next.fill(0)
  }
  return frame
}
module.exports = { scale, quantize, COLORS }
