package com.sr.nfkd;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.CRC32;

final class EpaperProtocol {
    static final int WIDTH = 768, HEIGHT = 552, FRAME_SIZE = WIDTH * HEIGHT / 4;
    static final int GET_INFO = 1, SET_MODE = 3, PHOTO_BEGIN = 10, PHOTO_DATA = 11,
            PHOTO_END = 12, PHOTO_ABORT = 13;
    static final UUID SERVICE = uuid(1), CONTROL = uuid(2), NOTIFY = uuid(3), DATA = uuid(4), STATUS = uuid(5);
    static final int OTA_BEGIN = 0x20, OTA_DATA = 0x21, OTA_END = 0x22, OTA_ABORT = 0x23;
    static final int FIRMWARE_MAX_SIZE = 0x160000;
    static String validateFirmware(byte[] bytes) {
        if (bytes.length < 288 || bytes.length > FIRMWARE_MAX_SIZE || (bytes[0] & 255) != 0xe9 ||
                (bytes[12] & 255) != 5 || bytes[13] != 0 || readInt(bytes, 32) != 0xabcd5432)
            throw new IllegalArgumentException("请选择 ESP32-C3 应用固件 .bin，不能使用启动程序或合并固件");
        int end = 80;
        while (end < 112 && bytes[end] != 0) ++end;
        String project = new String(bytes, 80, end - 80, java.nio.charset.StandardCharsets.US_ASCII);
        if (end == 112 || (!project.equals("C3FN4_3.98_A0_BLE") && !project.equals("C3FN4_3.98_A0")))
            throw new IllegalArgumentException("请选择本设备的蓝牙版或 Wi-Fi 版应用固件");
        return project;
    }
    static String firmwareConnection(String project) {
        return project.equals("C3FN4_3.98_A0")
                ? "连接 Wi-Fi 热点 C3FN4-EPAPER（密码 12345678），打开 http://192.168.4.1"
                : "在 App 中重新扫描并连接 EPD-Calendar";
    }
    static UUID uuid(int id) {
        return UUID.fromString(String.format(java.util.Locale.ROOT,
                "7b4d%04x-6a6c-4c61-9f20-65706463616c", id));
    }
    static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    static long crc32(byte[] frame) { CRC32 crc = new CRC32(); crc.update(frame); return crc.getValue(); }
    static int crc16(byte[] bytes, int count) {
        int crc = 0xffff;
        for (int i = 0; i < count; ++i) {
            crc ^= (bytes[i] & 255) << 8;
            for (int bit = 0; bit < 8; ++bit)
                crc = ((crc << 1) ^ ((crc & 0x8000) != 0 ? 0x1021 : 0)) & 0xffff;
        }
        return crc;
    }
    static byte[] encode(int command, int sequence, byte[] payload) {
        if (payload.length > 1024) throw new IllegalArgumentException("协议数据过长");
        byte[] result = buffer(payload.length + 10).put((byte)0x45).put((byte)0x50)
                .put((byte)1).put((byte)command).putShort((short)sequence)
                .putShort((short)payload.length).put(payload).array();
        int crc = crc16(result, result.length - 2);
        result[result.length - 2] = (byte)crc; result[result.length - 1] = (byte)(crc >> 8);
        return result;
    }
    static byte[] begin(byte[] frame, int photoId, int slot) {
        if (frame.length != FRAME_SIZE || slot < 0 || slot > 3)
            throw new IllegalArgumentException("图片帧或槽位错误");
        return buffer(13).putInt(photoId).put((byte)slot).putInt(frame.length)
                .putInt((int)crc32(frame)).array();
    }
    static byte[] chunk(byte[] frame, int offset, int mtu) {
        if (offset < 0 || offset >= frame.length || mtu < 23)
            throw new IllegalArgumentException("分包参数错误");
        // ATT 3 + EP framing 10 + offset 4. Attribute values are capped at 512.
        int size = Math.min(Math.min(mtu - 3, 512) - 14, frame.length - offset);
        return buffer(size + 4).putInt(offset).put(frame, offset, size).array();
    }
    static int readInt(byte[] bytes, int offset) {
        if (offset < 0 || bytes.length - offset < 4) throw new IllegalArgumentException("设备响应不完整");
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
    }
    static final class Packet {
        final int command, sequence;
        final byte[] payload;
        Packet(byte[] bytes) {
            command = bytes[3] & 255;
            sequence = (bytes[4] & 255) | ((bytes[5] & 255) << 8);
            payload = Arrays.copyOfRange(bytes, 8, bytes.length - 2);
        }
    }
    static final class Stream {
        private final byte[] pending = new byte[1034];
        private int length;
        void reset() { length = 0; }
        void feed(byte[] bytes, Consumer<Packet> receive) {
            for (byte value : bytes) {
                if (length == pending.length) drop(1);
                pending[length++] = value;
                while (length >= 2) {
                    if (pending[0] != 0x45 || pending[1] != 0x50) { drop(1); continue; }
                    if (length < 8) break;
                    int size = (pending[6] & 255) | ((pending[7] & 255) << 8);
                    if (pending[2] != 1 || size > 1024) { drop(2); continue; }
                    int total = size + 10;
                    if (length < total) break;
                    int crc = (pending[total - 2] & 255) | ((pending[total - 1] & 255) << 8);
                    if (crc16(pending, total - 2) == crc) {
                        Packet packet = new Packet(Arrays.copyOf(pending, total));
                        drop(total);
                        receive.accept(packet);
                    } else drop(2);
                }
            }
        }
        private void drop(int count) {
            System.arraycopy(pending, count, pending, 0, length - count);
            length -= count;
        }
    }
    static final class Info {
        final int mode, slot, uploadSlot, photoId, total, offset;
        final boolean uploadActive;
        Info(byte[] payload) {
            // Includes the response status byte before GET_INFO schema.
            if (payload.length < 46 || payload[0] != 0 || payload[1] != 1)
                throw new IllegalArgumentException("设备信息协议不匹配");
            mode = payload[2] & 255; slot = payload[4] & 255;
            int position = 30;
            if (position >= payload.length) throw new IllegalArgumentException("设备信息不完整");
            position += 1 + (payload[position] & 255);
            if (position >= payload.length) throw new IllegalArgumentException("设备信息不完整");
            position += 1 + (payload[position] & 255);
            if (payload.length - position != 14 || mode > 3 || slot > 3)
                throw new IllegalArgumentException("设备信息字段错误");
            uploadActive = payload[position] != 0; uploadSlot = payload[position + 1] & 255;
            photoId = readInt(payload, position + 2); total = readInt(payload, position + 6);
            offset = readInt(payload, position + 10);
            if (uploadActive && (uploadSlot > 3 || total != FRAME_SIZE || offset < 0 || offset > total))
                throw new IllegalArgumentException("设备上传状态错误");
        }
    }
    static final class Status {
        final int state, error, received;
        final long crc;
        Status(byte[] value) {
            if (value.length != 20 || value[0] != 1)
                throw new IllegalArgumentException("设备状态协议不匹配");
            ByteBuffer b = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            state = value[1] & 255; error = value[2] & 255;
            received = b.getInt(4); crc = Integer.toUnsignedLong(b.getInt(16));
            if (state > 4 || received < 0 || received > FRAME_SIZE || b.getInt(8) != FRAME_SIZE
                    || b.getShort(12) != WIDTH || b.getShort(14) != HEIGHT)
                throw new IllegalArgumentException("设备屏幕规格不匹配");
        }
    }
}
