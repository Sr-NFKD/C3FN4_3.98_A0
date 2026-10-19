package com.sr.nfkd;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Arrays;
import java.util.UUID;

@SuppressLint("MissingPermission")
final class BleClient {
    interface Listener { void onState(String message, boolean ready, boolean busy, int progress); }
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EpaperProtocol.Stream stream = new EpaperProtocol.Stream();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic control, data, notify, status;
    private String phase = "offline";
    private String firmwareProject = "C3FN4_3.98_A0_BLE";
    private int mtu = 23, offset, pendingSize, slot, photoId;
    private int sequence = new java.util.Random().nextInt(65536);
    private int command, commandSequence, writeOffset, retries;
    private byte[] frame, firmware, commandPayload, encoded;
    private boolean writeComplete;
    private boolean committed;
    private boolean showCompletion;
    private EpaperProtocol.Packet response;
    private EpaperProtocol.Info info;
    private long transferDeadline, refreshDeadline;
    private final Runnable timeout = this::operationTimeout;
    private final Runnable poll = this::readStatus;
    private final Runnable retryBusy = () -> request(command, commandPayload);

    BleClient(Context context, Listener listener) { this.context = context; this.listener = listener; }
    private void report(String message, int progress) {
        listener.onState(message, phase.equals("ready"),
                !phase.equals("ready") && !phase.equals("offline"), progress);
    }
    private void armTimeout() {
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 15000);
    }
    private void operationTimeout() {
        // Retry the same sequence only after Android has finished the GATT write.
        if (encoded != null && writeComplete && retries++ < 2) {
            writeOffset = 0; writeComplete = false; response = null; writePart();
        } else fail("蓝牙操作超时，请重新连接；同一张图片可以继续传输");
    }
    void connect(BluetoothDevice device) {
        disconnect(); phase = "connecting"; report("正在连接墨水屏…", 0);
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { fail("无法创建蓝牙连接"); return; }
            armTimeout();
        } catch (SecurityException | IllegalArgumentException e) { fail("无法连接：" + e.getMessage()); }
    }
    void disconnect() {
        handler.removeCallbacks(timeout); handler.removeCallbacks(poll); handler.removeCallbacks(retryBusy);
        BluetoothGatt old = gatt; gatt = null;
        if (old != null) { old.disconnect(); old.close(); }
        phase = "offline"; control = data = notify = status = null;
        encoded = null; response = null; info = null; mtu = 23; stream.reset();
        showCompletion = false; firmware = null;
        // Keep the local image and photo ID for PHOTO_BEGIN after reconnection.
        report("未连接设备", 0);
    }
    private void fail(String message) { disconnect(); report(message, 0); }
    void send(byte[] bytes) {
        if (!phase.equals("ready")) return;
        if (bytes.length != EpaperProtocol.FRAME_SIZE) { report("图片帧大小错误", 0); return; }
        if (frame == null || !Arrays.equals(frame, bytes)) {
            frame = bytes.clone(); photoId = new java.util.Random().nextInt();
            committed = false;
        }
        slot = info != null && info.uploadActive && info.photoId == photoId ? info.uploadSlot :
                info == null ? 0 : info.slot;
        transferDeadline = SystemClock.elapsedRealtime() + 240000;
        offset = 0;
        if (info != null && info.uploadActive && info.photoId != photoId) {
            phase = "abort"; report("正在取消上一次未完成上传…", 0);
            request(EpaperProtocol.PHOTO_ABORT, new byte[0]);
        } else selectPhotoMode();
    }
    void clear() {
        byte[] white = new byte[EpaperProtocol.FRAME_SIZE]; Arrays.fill(white, (byte)0x55); send(white);
    }
    void updateFirmware(byte[] bytes) {
        if (!phase.equals("ready")) { report("请先连接设备并等待当前操作完成", 0); return; }
        try { firmwareProject = EpaperProtocol.validateFirmware(bytes); }
        catch (IllegalArgumentException e) { report(e.getMessage(), 0); return; }
        frame = null; firmware = bytes.clone(); offset = 0;
        transferDeadline = SystemClock.elapsedRealtime() + 300000;
        phase = "ota-begin"; report("正在准备固件更新…", 0);
        request(EpaperProtocol.OTA_BEGIN, EpaperProtocol.buffer(8).putInt(firmware.length)
                .putInt((int)EpaperProtocol.crc32(firmware)).array());
    }
    private void sendFirmwareNext() {
        if (offset == firmware.length) {
            phase = "ota-end"; report("固件上传完成，正在校验…", 100);
            request(EpaperProtocol.OTA_END, new byte[0]);
        } else {
            phase = "ota-data";
            byte[] packet = EpaperProtocol.chunk(firmware, offset, mtu);
            pendingSize = packet.length - 4;
            request(EpaperProtocol.OTA_DATA, packet);
        }
    }
    private void selectPhotoMode() {
        if (info != null && info.mode != 1) {
            phase = "mode"; request(EpaperProtocol.SET_MODE, new byte[] {1});
        } else begin();
    }
    private void begin() {
        phase = "begin"; report("正在开始或恢复传输…", 0);
        request(EpaperProtocol.PHOTO_BEGIN, EpaperProtocol.begin(frame, photoId, slot));
    }
    private void request(int type, byte[] payload) {
        command = type; commandPayload = payload.clone(); commandSequence = sequence++ & 65535;
        encoded = EpaperProtocol.encode(type, commandSequence, payload);
        retries = 0; writeOffset = 0; writeComplete = false; response = null; writePart();
    }
    private void writePart() {
        if (gatt == null || encoded == null) return;
        int count = Math.min(Math.min(mtu - 3, 512), encoded.length - writeOffset);
        byte[] part = Arrays.copyOfRange(encoded, writeOffset, writeOffset + count);
        writeOffset += count; armTimeout();
        int result = gatt.writeCharacteristic((command == EpaperProtocol.PHOTO_DATA || command == EpaperProtocol.OTA_DATA) ? data : control,
                part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (result != BluetoothStatusCodes.SUCCESS) fail("蓝牙写入未启动（" + result + "），请重连");
    }
    private void receive(EpaperProtocol.Packet packet) {
        if (encoded == null || packet.sequence != commandSequence || packet.command != (command | 128)) return;
        response = packet;
        if (writeComplete) completeCommand();
    }
    private void completeCommand() {
        if (response == null || !writeComplete) return;
        handler.removeCallbacks(timeout);
        byte[] payload = response.payload; encoded = null; response = null;
        if (payload.length == 0) { fail("设备响应不完整"); return; }
        int result = payload[0] & 255;
        if (result == 5 && (frame != null || firmware != null) && SystemClock.elapsedRealtime() < transferDeadline) {
            report("设备忙，正在等待继续上传…", offset * 100 / (firmware != null ? firmware.length : frame.length));
            handler.postDelayed(retryBusy, 500); return; // Busy retries need a NEW sequence.
        }
        if (result != 0) {
            if (phase.startsWith("ota-") && result == 4)
                fail("当前设备固件不支持无线升级，请先通过 USB 烧录支持 OTA 的版本");
            else fail("设备处理失败（" + result + "），请重连后重试");
            return;
        }
        try {
            if (phase.equals("initial")) {
                info = new EpaperProtocol.Info(payload);
                if (status != null) readStatus();
                else { phase = "ready"; report("已连接 · EPD-Calendar", 0); }
            } else if (phase.equals("abort")) selectPhotoMode();
            else if (phase.equals("mode")) begin();
            else if (phase.equals("ota-begin")) {
                if (payload.length != 9 || EpaperProtocol.readInt(payload, 1) != 0 ||
                        EpaperProtocol.readInt(payload, 5) < firmware.length)
                    throw new IllegalArgumentException("设备固件分区不匹配");
                sendFirmwareNext();
            } else if (phase.equals("ota-data")) {
                if (payload.length != 5 || EpaperProtocol.readInt(payload, 1) != offset + pendingSize)
                    throw new IllegalArgumentException("设备固件接收位置错误");
                offset += pendingSize;
                report("正在上传固件 " + offset * 100 / firmware.length + "%", offset * 100 / firmware.length);
                sendFirmwareNext();
            } else if (phase.equals("ota-end")) {
                if (payload.length != 1) throw new IllegalArgumentException("固件提交响应错误");
                firmware = null; phase = "ota-reboot";
                report("固件校验通过，设备正在重启…", 100);
                handler.postDelayed(() -> {
                    if (phase.equals("ota-reboot")) {
                        disconnect(); report("固件已提交，请" + EpaperProtocol.firmwareConnection(firmwareProject) + "；若未重启请手动重启", 100);
                    }
                }, 8000);
            } else if (phase.equals("begin")) {
                if (payload.length != 10 || (payload[1] & 255) != slot ||
                        EpaperProtocol.readInt(payload, 6) != frame.length)
                    throw new IllegalArgumentException("设备上传规格不匹配");
                offset = EpaperProtocol.readInt(payload, 2);
                if (offset < 0 || offset > frame.length) throw new IllegalArgumentException("设备续传位置错误");
                sendNext();
            } else if (phase.equals("data")) {
                if (payload.length != 5) throw new IllegalArgumentException("设备分包响应错误");
                int next = EpaperProtocol.readInt(payload, 1);
                if (next <= offset || next > offset + pendingSize)
                    throw new IllegalArgumentException("设备接收位置错误");
                offset = next; report("正在传输图片 " + offset * 100 / frame.length + "%", offset * 100 / frame.length);
                sendNext();
            } else if (phase.equals("commit")) {
                if (payload.length != 10 || (payload[1] & 255) != slot ||
                        EpaperProtocol.readInt(payload, 2) != photoId || EpaperProtocol.readInt(payload, 6) != frame.length)
                    throw new IllegalArgumentException("设备提交结果不匹配");
                info = null;
                committed = true;
                if (status == null) {
                    frame = null; phase = "ready";
                    committed = false;
                    report("图片已保存，设备已安排刷新", 100);
                } else {
                    phase = "wait"; refreshDeadline = SystemClock.elapsedRealtime() + 240000; readStatus();
                }
            }
        } catch (IllegalArgumentException e) { fail(e.getMessage()); }
    }
    private void sendNext() {
        if (offset == frame.length) {
            phase = "commit"; report("图片已传完，正在校验并保存…", 100);
            request(EpaperProtocol.PHOTO_END, EpaperProtocol.buffer(4).putInt((int)EpaperProtocol.crc32(frame)).array());
        } else {
            phase = "data"; byte[] packet = EpaperProtocol.chunk(frame, offset, mtu);
            pendingSize = packet.length - 4; request(EpaperProtocol.PHOTO_DATA, packet);
        }
    }
    private void readStatus() {
        if (gatt == null || status == null) return;
        armTimeout();
        if (!gatt.readCharacteristic(status)) fail("无法读取墨水屏状态，请重连");
    }
    private void acceptStatus(byte[] value) {
        try {
            EpaperProtocol.Status device = new EpaperProtocol.Status(value);
            if (device.state == 4) {
                frame = null; phase = "ready"; report("屏幕刷新失败，可以重新发送", 0);
            } else if (device.state == 2) {
                if (!phase.equals("wait")) refreshDeadline = SystemClock.elapsedRealtime() + 240000;
                phase = "wait"; report("墨水屏正在刷新，请等待完成…", 100);
                if (SystemClock.elapsedRealtime() > refreshDeadline) { fail("屏幕刷新超时"); return; }
                handler.postDelayed(poll, 1000);
            } else if (phase.equals("wait")) {
                if (device.state != 3) { fail("设备未完成刷新，请重新发送"); return; }
                if (committed && frame != null && (device.received != frame.length || device.crc != EpaperProtocol.crc32(frame))) {
                    fail("设备帧校验结果不匹配"); return;
                }
                if (committed) { frame = null; committed = false; }
                showCompletion = true;
                phase = "initial"; request(EpaperProtocol.GET_INFO, new byte[0]);
                report("刷新完成，正在读取设备信息…", 100);
            } else {
                phase = "ready";
                report(showCompletion ? "刷新完成，可以发送下一张图片" : "已连接 · 768 × 552 四色墨水屏",
                        showCompletion ? 100 : 0);
                showCompletion = false;
            }
        } catch (IllegalArgumentException e) { fail(e.getMessage()); }
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int result, int newState) {
            handler.post(() -> {
                if (source != gatt) return;
                handler.removeCallbacks(timeout);
                if (result != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (phase.equals("ota-reboot")) {
                        disconnect(); report("固件已提交，设备已断开连接，请" + EpaperProtocol.firmwareConnection(firmwareProject), 100);
                    } else if (phase.startsWith("ota-")) {
                        fail("固件更新连接中断；请重连确认设备，未提交的固件需要重新上传");
                    } else fail("蓝牙连接已断开（" + result + "），重新连接后可继续传同一张图片");
                    return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    phase = "discover"; report("正在识别墨水屏服务…", 0);
                    source.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); armTimeout();
                    if (!source.discoverServices()) fail("服务发现未启动");
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt source, int result) {
            handler.post(() -> {
                if (source != gatt || !phase.equals("discover")) return;
                handler.removeCallbacks(timeout);
                BluetoothGattService service = source.getService(EpaperProtocol.SERVICE);
                if (result != BluetoothGatt.GATT_SUCCESS || service == null) { fail("此设备不支持 EPD-Calendar 协议"); return; }
                control = service.getCharacteristic(EpaperProtocol.CONTROL); data = service.getCharacteristic(EpaperProtocol.DATA);
                notify = service.getCharacteristic(EpaperProtocol.NOTIFY); status = service.getCharacteristic(EpaperProtocol.STATUS);
                if (control == null || data == null || notify == null) { fail("墨水屏服务不完整"); return; }
                BluetoothGattDescriptor cccd = notify.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                if (cccd == null || !source.setCharacteristicNotification(notify, true)) { fail("无法订阅设备通知"); return; }
                phase = "subscribe"; armTimeout();
                if (source.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS)
                    fail("通知订阅未启动");
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int result) {
            handler.post(() -> {
                if (source != gatt || !phase.equals("subscribe")) return;
                handler.removeCallbacks(timeout);
                if (result != BluetoothGatt.GATT_SUCCESS) { fail("通知订阅失败"); return; }
                phase = "mtu"; armTimeout();
                if (!source.requestMtu(517)) { phase = "initial"; request(EpaperProtocol.GET_INFO, new byte[0]); }
            });
        }
        @Override public void onMtuChanged(BluetoothGatt source, int size, int result) {
            handler.post(() -> {
                if (source != gatt || !phase.equals("mtu")) return;
                handler.removeCallbacks(timeout); mtu = result == BluetoothGatt.GATT_SUCCESS ? Math.max(23, size) : 23;
                phase = "initial"; request(EpaperProtocol.GET_INFO, new byte[0]);
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic ch, int result) {
            handler.post(() -> {
                if (source != gatt || encoded == null) return;
                if (result != BluetoothGatt.GATT_SUCCESS) { fail("设备拒绝写入（" + result + "），请重新连接"); return; }
                if (writeOffset < encoded.length) writePart();
                else { writeComplete = true; if (response != null) completeCommand(); else armTimeout(); }
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic ch, byte[] value) {
            byte[] copy = value.clone();
            handler.post(() -> { if (source == gatt && ch.getUuid().equals(EpaperProtocol.NOTIFY)) stream.feed(copy, BleClient.this::receive); });
        }
        @Override public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic ch, byte[] value, int result) {
            byte[] copy = value.clone();
            handler.post(() -> {
                if (source != gatt || !ch.getUuid().equals(EpaperProtocol.STATUS)) return;
                handler.removeCallbacks(timeout);
                if (result != BluetoothGatt.GATT_SUCCESS) { fail("读取设备状态失败（" + result + "）"); return; }
                acceptStatus(copy);
            });
        }
    };
}
