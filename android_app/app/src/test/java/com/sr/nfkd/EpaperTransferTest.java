package com.sr.nfkd;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import static org.junit.Assert.*;

public class EpaperTransferTest {
    @Test public void panelColorsAndBitOrderMatchFirmware() {
        int[] pixels = {0xff000000, 0xffffffff, 0xffffff00, 0xffff0000};
        assertArrayEquals(new byte[] {0x1b}, FrameCodec.encode(pixels, 4, 1, false, false));
        int[] white = new int[EpaperProtocol.WIDTH * EpaperProtocol.HEIGHT];
        Arrays.fill(white, 0xffffffff);
        byte[] frame = FrameCodec.encode(white, EpaperProtocol.WIDTH, EpaperProtocol.HEIGHT, true, false);
        assertEquals(105984, frame.length);
        for (byte value : frame) assertEquals(0x55, value & 255);
    }
    @Test public void serpentineDitheringPacksTheSamePixelsAsPreview() {
        int[] pixels = new int[8 * 8];
        Random random = new Random(42);
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000 | random.nextInt(0x1000000);
        byte[] frame = FrameCodec.encode(pixels, 8, 8, true, false);
        for (int i = 0; i < pixels.length; i++) {
            int code = (frame[i / 4] >> (6 - 2 * (i % 4))) & 3;
            assertEquals(FrameCodec.COLORS[code], pixels[i]);
        }
        int[] white = {0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff};
        assertArrayEquals(new byte[] {0}, FrameCodec.encode(white, 4, 1, true, true));
    }
    @Test public void completeFrameRoundTripsAtMinimumAndLargeMtu() {
        byte[] frame = new byte[EpaperProtocol.FRAME_SIZE];
        new Random(7).nextBytes(frame);
        CRC32 crc = new CRC32(); crc.update(frame);
        ByteBuffer begin = ByteBuffer.wrap(EpaperProtocol.begin(frame)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, begin.get()); assertEquals(frame.length, begin.getInt());
        assertEquals(crc.getValue(), Integer.toUnsignedLong(begin.getInt()));
        for (int mtu : new int[] {23, 185, 247, 517}) {
            byte[] received = new byte[frame.length];
            CRC32 streamed = new CRC32();
            int offset = 0;
            while (offset < frame.length) {
                byte[] packet = EpaperProtocol.chunk(frame, offset, mtu);
                assertTrue(packet.length <= Math.min(mtu - 3, 512));
                assertEquals(offset, ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt());
                int size = packet.length - 4;
                System.arraycopy(packet, 4, received, offset, size);
                streamed.update(packet, 4, size); offset += size;
            }
            assertArrayEquals(frame, received); assertEquals(crc.getValue(), streamed.getValue());
        }
    }
    @Test public void deviceStatusRejectsWrongProtocolAndDimensions() {
        byte[] status = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .put(new byte[] {1, 3, 0, 0}).putInt(EpaperProtocol.FRAME_SIZE)
                .putInt(EpaperProtocol.FRAME_SIZE).putShort((short)768).putShort((short)552)
                .putInt(0xcbf43926).array();
        EpaperProtocol.Status decoded = new EpaperProtocol.Status(status);
        assertEquals(3, decoded.state); assertEquals(0xcbf43926L, decoded.crc);
        status[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> new EpaperProtocol.Status(status));
        status[0] = 1; status[12] = 0;
        status[13] = 2;
        assertThrows(IllegalArgumentException.class, () -> new EpaperProtocol.Status(status));
        assertThrows(IllegalArgumentException.class, () -> EpaperProtocol.begin(new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> EpaperProtocol.chunk(new byte[4], 4, 23));
    }
}
