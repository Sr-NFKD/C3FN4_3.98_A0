const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../components/web_ui/index.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const elements = new Map();
function element(id) {
  if (!elements.has(id)) elements.set(id, {files: [], value: 0, disabled: false, textContent: '',
    addEventListener(type, fn) { this[type] = fn; }});
  return elements.get(id);
}
let reply = 'ok', uploads = 0, sentHeader = '';
class XHR {
  constructor() { this.upload = {}; }
  open(method, url) { assert.equal(method, 'POST'); assert.equal(url, '/api/firmware'); }
  setRequestHeader(name, value) { if (name === 'X-Firmware-CRC32') sentHeader = value; }
  send(bytes) {
    ++uploads; this.upload.onprogress({lengthComputable: true, loaded: bytes.size, total: bytes.size});
    if (reply === 'network') { this.onerror(); return; }
    if (reply === 'timeout') { this.ontimeout(); return; }
    this.status = reply === 'reject' ? 400 : 200;
    this.responseText = reply === 'reject' ? 'Firmware rejected' : JSON.stringify({ok: true, reboot: reply !== 'manual'});
    this.onload();
  }
}
const ctx = vm.createContext({document: {getElementById: element}, window: {}, TextDecoder,
  Uint8Array, Promise, XMLHttpRequest: XHR, confirm: () => true, setTimeout: () => 0});
vm.runInContext(script, ctx);
assert.equal(vm.runInContext('firmwareCrc32(new Uint8Array([49,50,51,52,53,54,55,56,57]))', ctx), 'cbf43926');
function file(bytes) { return {size: bytes.length, arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength)}; }
(async () => {
  const bytes = Buffer.alloc(1024); bytes[0] = 0xe9; bytes[12] = 5;
  bytes.write('C3FN4_3.98_A0', 80, 'ascii'); bytes.writeUInt32LE(0xabcd5432,32);
  element('firmware').files = [file(bytes)];
  await element('updateFirmware').click();
  assert.equal(uploads, 1); assert.match(sentHeader, /^[0-9a-f]{8}$/);
  assert.match(element('firmwareStatus').textContent, /校验通过/);
  assert.equal(element('firmwareProgress').value, 100);
  for (reply of ['network', 'timeout', 'reject', 'manual']) {
    await element('updateFirmware').click();
    assert.match(element('firmwareStatus').textContent, reply === 'manual' ? /手动重启/ : /更新未完成/);
    if (reply !== 'manual') assert.equal(element('updateFirmware').disabled, false);
  }
  reply='ok';bytes.fill(0,80,112);bytes.write('C3FN4_3.98_A0_BLE',80,'ascii');
  await element('updateFirmware').click();
  assert.match(element('firmwareStatus').textContent,/EPD-Calendar/);
  const before = uploads;
  bytes[80] = 88; await element('updateFirmware').click();
  assert.equal(uploads, before); assert.match(element('firmwareStatus').textContent, /Wi-Fi/);
  element('firmware').files = [file(Buffer.alloc(100))];
  await element('updateFirmware').click(); assert.equal(uploads, before);
  assert.match(element('firmwareStatus').textContent, /文件大小/);
  console.log('Web OTA: CRC, upload progress, wrong firmware, HTTP/network/timeout errors and restart messages passed');
})();
