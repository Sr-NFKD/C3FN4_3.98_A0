package com.sr.nfkd;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

@SuppressLint("MissingPermission") // MainActivity requests SCAN and CONNECT before using this client.
final class BleClient {
    interface Listener {
        void onState(String message, boolean ready, boolean busy, int progress);
    }
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic control, data, status;
    private String phase = "offline";
    private int mtu = 23, offset, pendingSize;
    private byte[] frame;
    private long refreshDeadline;
    private final Runnable timeout = () -> fail("蓝牙操作超时，请靠近设备后重新连接");
    private final Runnable poll = this::readStatus;

    BleClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }
    private void report(String text, int progress) {
        listener.onState(text, phase.equals("ready"),
                !phase.equals("ready") && !phase.equals("offline"), progress);
    }
    private void armTimeout() {
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, 15000);
    }
    void connect(BluetoothDevice device) {
        disconnect();
        phase = "connecting";
        report("正在连接墨水屏…", 0);
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { fail("无法创建蓝牙连接"); return; }
            armTimeout();
        } catch (SecurityException | IllegalArgumentException e) { fail("无法连接：" + e.getMessage()); }
    }
    void disconnect() {
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(poll);
        BluetoothGatt old = gatt;
        gatt = null;
        if (old != null) { old.disconnect(); old.close(); }
        phase = "offline";
        control = data = status = null;
        frame = null; mtu = 23; offset = 0;
        report("未连接设备", 0);
    }
    private void fail(String message) { disconnect(); report(message, 0); }
    void send(byte[] bytes) {
        if (!phase.equals("ready")) return;
        frame = bytes.clone(); offset = 0;
        phase = "begin";
        report("正在开始传输…", 0);
        write(control, EpaperProtocol.begin(frame));
    }
    void clear() {
        if (!phase.equals("ready")) return;
        frame = null; phase = "clear";
        report("正在清屏…", 0);
        write(control, new byte[] {3});
    }
    private void write(BluetoothGattCharacteristic target, byte[] value) {
        if (gatt == null) return;
        armTimeout();
        int result = gatt.writeCharacteristic(target, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (result != BluetoothStatusCodes.SUCCESS) fail("蓝牙写入未启动（" + result + "），请重连");
    }
    private void sendNext() {
        if (offset == frame.length) {
            phase = "commit";
            report("图片已传完，正在校验…", 100);
            write(control, new byte[] {2});
            return;
        }
        phase = "data";
        byte[] packet = EpaperProtocol.chunk(frame, offset, mtu);
        pendingSize = packet.length - 4;
        write(data, packet);
    }
    private void readStatus() {
        if (gatt == null) return;
        armTimeout();
        if (!gatt.readCharacteristic(status)) fail("无法读取墨水屏状态，请重连");
    }
    private void acceptStatus(byte[] value) {
        try {
            EpaperProtocol.Status device = new EpaperProtocol.Status(value);
            if (device.state == 4) {
                phase = "ready"; frame = null;
                report(device.error == 5 ? "屏幕刷新失败，请检查接线和 BUSY 信号后重试" :
                        "设备处理失败（错误码 " + device.error + "），可以重新发送", 0);
            } else if (device.state == 2) {
                if (!phase.equals("wait")) refreshDeadline = SystemClock.elapsedRealtime() + 240000;
                phase = "wait";
                report("墨水屏正在刷新，请等待完成…", 100);
                if (SystemClock.elapsedRealtime() > refreshDeadline) { fail("屏幕刷新超时"); return; }
                handler.postDelayed(poll, 1000);
            } else if (phase.equals("wait")) {
                if (device.state != 3) { fail("设备未完成刷新，请重新发送"); return; }
                if (frame != null) {
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(frame);
                    if (device.received != frame.length || device.crc != crc.getValue()) {
                        fail("设备帧校验结果不匹配"); return;
                    }
                }
                phase = "ready"; frame = null;
                report("刷新完成，可以发送下一张图片", 100);
            } else {
                phase = "ready";
                report("已连接 · 768 × 552 四色墨水屏", 0);
            }
        } catch (IllegalArgumentException e) { fail(e.getMessage()); }
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int result, int newState) {
            handler.post(() -> {
                if (source != gatt) return;
                handler.removeCallbacks(timeout);
                if (result != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("蓝牙连接已断开（" + result + "），请重新扫描连接"); return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    phase = "discover";
                    report("正在识别墨水屏服务…", 0);
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    armTimeout();
                    if (!gatt.discoverServices()) fail("服务发现未启动");
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt source, int result) {
            handler.post(() -> {
                if (source != gatt || !phase.equals("discover")) return;
                handler.removeCallbacks(timeout);
                BluetoothGattService service = source.getService(EpaperProtocol.SERVICE);
                if (result != BluetoothGatt.GATT_SUCCESS || service == null) { fail("此设备不是适配的墨水屏"); return; }
                control = service.getCharacteristic(EpaperProtocol.CONTROL);
                data = service.getCharacteristic(EpaperProtocol.DATA);
                status = service.getCharacteristic(EpaperProtocol.STATUS);
                if (control == null || data == null || status == null) { fail("墨水屏服务不完整"); return; }
                phase = "mtu"; armTimeout();
                if (!source.requestMtu(517)) { phase = "initial"; readStatus(); }
            });
        }
        @Override public void onMtuChanged(BluetoothGatt source, int size, int result) {
            handler.post(() -> {
                if (source != gatt || !phase.equals("mtu")) return;
                handler.removeCallbacks(timeout);
                mtu = result == BluetoothGatt.GATT_SUCCESS ? Math.max(23, size) : 23;
                phase = "initial"; readStatus();
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic ch, int result) {
            handler.post(() -> {
                if (source != gatt) return;
                handler.removeCallbacks(timeout);
                if (result != BluetoothGatt.GATT_SUCCESS) { fail("设备拒绝数据（" + result + "），请重新连接并发送"); return; }
                if (phase.equals("begin")) sendNext();
                else if (phase.equals("data")) {
                    offset += pendingSize;
                    report("正在传输图片 " + offset * 100 / frame.length + "%", offset * 100 / frame.length);
                    sendNext();
                } else if (phase.equals("commit") || phase.equals("clear")) {
                    phase = "wait";
                    refreshDeadline = SystemClock.elapsedRealtime() + 240000;
                    readStatus();
                }
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic ch,
                                                    byte[] value, int result) {
            handler.post(() -> {
                if (source != gatt) return;
                handler.removeCallbacks(timeout);
                if (result != BluetoothGatt.GATT_SUCCESS) { fail("读取设备状态失败（" + result + "）"); return; }
                acceptStatus(value);
            });
        }
    };
}
