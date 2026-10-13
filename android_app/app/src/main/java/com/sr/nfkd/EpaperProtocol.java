package com.sr.nfkd;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.zip.CRC32;

final class EpaperProtocol {
    static final int WIDTH = 768, HEIGHT = 552, FRAME_SIZE = WIDTH * HEIGHT / 4;
    static final UUID SERVICE = uuid(1), CONTROL = uuid(2), DATA = uuid(3), STATUS = uuid(4);
    static UUID uuid(int id) {
        return UUID.fromString(String.format(java.util.Locale.ROOT,
                "7e40000%d-b5a3-f393-e0a9-e50e24dcca9e", id));
    }
    static byte[] begin(byte[] frame) {
        if (frame.length != FRAME_SIZE) throw new IllegalArgumentException("图片帧大小错误");
        CRC32 crc = new CRC32();
        crc.update(frame);
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 1).putInt(frame.length).putInt((int) crc.getValue()).array();
    }
    static byte[] chunk(byte[] frame, int offset, int mtu) {
        if (offset < 0 || offset >= frame.length || mtu < 23)
            throw new IllegalArgumentException("分包参数错误");
        int size = Math.min(Math.min(mtu - 3, 512) - 4, frame.length - offset);
        return ByteBuffer.allocate(size + 4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(offset).put(frame, offset, size).array();
    }
    static final class Status {
        final int state, error, received;
        final long crc;
        Status(byte[] value) {
            if (value.length != 20 || value[0] != 1)
                throw new IllegalArgumentException("设备通信协议不匹配");
            ByteBuffer b = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            state = value[1] & 255;
            error = value[2] & 255;
            received = b.getInt(4);
            crc = Integer.toUnsignedLong(b.getInt(16));
            if (state > 4 || received < 0 || received > FRAME_SIZE || b.getInt(8) != FRAME_SIZE
                    || b.getShort(12) != WIDTH || b.getShort(14) != HEIGHT)
                throw new IllegalArgumentException("设备屏幕规格不匹配");
        }
    }
}
