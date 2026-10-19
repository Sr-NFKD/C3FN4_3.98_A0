package com.sr.nfkd;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import static org.junit.Assert.*;

public class EpaperTransferTest {
    @Test public void firmwareRejectsWrongChipProjectAndContainer() {
        byte[] firmware = new byte[1024];
        firmware[0] = (byte)0xe9; firmware[12] = 5;
        ByteBuffer.wrap(firmware).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 0xabcd5432);
        byte[] project = "C3FN4_3.98_A0_BLE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(project, 0, firmware, 80, project.length);
        assertEquals("C3FN4_3.98_A0_BLE", EpaperProtocol.validateFirmware(firmware));
        Arrays.fill(firmware,80,112,(byte)0);
        byte[] wifi="C3FN4_3.98_A0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(wifi,0,firmware,80,wifi.length);
        assertEquals("C3FN4_3.98_A0", EpaperProtocol.validateFirmware(firmware));
        assertTrue(EpaperProtocol.firmwareConnection("C3FN4_3.98_A0").contains("192.168.4.1"));
        assertTrue(EpaperProtocol.firmwareConnection("C3FN4_3.98_A0_BLE").contains("EPD-Calendar"));
        firmware[12] = 9;
        assertThrows(IllegalArgumentException.class, () -> EpaperProtocol.validateFirmware(firmware));
        firmware[12] = 5; firmware[80] = 'X';
        assertThrows(IllegalArgumentException.class, () -> EpaperProtocol.validateFirmware(firmware));
        firmware[80] = 'C'; firmware[32] = 0;
        assertThrows(IllegalArgumentException.class, () -> EpaperProtocol.validateFirmware(firmware));
        assertThrows(IllegalArgumentException.class, () -> EpaperProtocol.validateFirmware(new byte[287]));
        assertThrows(IllegalArgumentException.class, () -> EpaperProtocol.validateFirmware(new byte[EpaperProtocol.FIRMWARE_MAX_SIZE + 1]));
    }
    @Test public void firmwareChunksRoundTripAndHaveCorrectCommandAndCrc() {
        byte[] firmware = new byte[65539]; new Random(11).nextBytes(firmware);
        for (int mtu : new int[] {23, 247, 517}) {
            byte[] received = new byte[firmware.length];
            for (int offset = 0; offset < firmware.length;) {
                byte[] chunk = EpaperProtocol.chunk(firmware, offset, mtu);
                byte[] encoded = EpaperProtocol.encode(EpaperProtocol.OTA_DATA, offset & 65535, chunk);
                assertTrue(encoded.length <= Math.min(mtu - 3, 512));
                EpaperProtocol.Stream parser = new EpaperProtocol.Stream();
                java.util.List<EpaperProtocol.Packet> packets = new java.util.ArrayList<>();
                parser.feed(encoded, packets::add);
                assertEquals(1, packets.size()); assertEquals(0x21, packets.get(0).command);
                assertEquals(offset, EpaperProtocol.readInt(packets.get(0).payload, 0));
                System.arraycopy(chunk, 4, received, offset, chunk.length - 4);
                offset += chunk.length - 4;
            }
            assertArrayEquals(firmware, received); assertEquals(EpaperProtocol.crc32(firmware), EpaperProtocol.crc32(received));
        }
    }
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
        ByteBuffer begin = ByteBuffer.wrap(EpaperProtocol.begin(frame, 123, 2)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(123, begin.getInt()); assertEquals(2, begin.get()); assertEquals(frame.length, begin.getInt());
        assertEquals(crc.getValue(), Integer.toUnsignedLong(begin.getInt()));
        for (int mtu : new int[] {23, 185, 247, 517}) {
            byte[] received = new byte[frame.length];
            CRC32 streamed = new CRC32();
            int offset = 0;
            while (offset < frame.length) {
                byte[] packet = EpaperProtocol.chunk(frame, offset, mtu);
                assertTrue(EpaperProtocol.encode(11, 7, packet).length <= Math.min(mtu - 3, 512));
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
        assertThrows(IllegalArgumentException.class, () -> EpaperProtocol.begin(new byte[4], 1, 0));
        assertThrows(IllegalArgumentException.class, () -> EpaperProtocol.chunk(new byte[4], 4, 23));
    }
    @Test public void protocolMatchesUpstreamGoldenFrameAndCrc() {
        assertEquals(0x29b1, EpaperProtocol.crc16("123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 9));
        assertArrayEquals(new byte[] {0x45, 0x50, 1, 1, 0x34, 0x12, 0, 0, 0x2d, 0x41},
                EpaperProtocol.encode(1, 0x1234, new byte[0]));
        assertEquals("7b4d0004-6a6c-4c61-9f20-65706463616c", EpaperProtocol.DATA.toString());
    }
    @Test public void notificationsCanSplitCombineAndRecoverAfterCorruption() {
        byte[] good = EpaperProtocol.encode(0x8b, 42, new byte[] {0, 20, 0, 0, 0});
        byte[] bad = good.clone(); bad[8] ^= 1;
        EpaperProtocol.Stream parser = new EpaperProtocol.Stream();
        java.util.List<EpaperProtocol.Packet> packets = new java.util.ArrayList<>();
        parser.feed(new byte[] {0, 1, 0x45}, packets::add); parser.feed(bad, packets::add);
        for (byte value : good) parser.feed(new byte[] {value}, packets::add);
        byte[] combined = new byte[good.length * 2];
        System.arraycopy(good, 0, combined, 0, good.length);
        System.arraycopy(good, 0, combined, good.length, good.length);
        parser.feed(combined, packets::add);
        assertEquals(3, packets.size()); assertEquals(42, packets.get(0).sequence);
        assertEquals(20, EpaperProtocol.readInt(packets.get(0).payload, 1));
        parser.feed(Arrays.copyOf(good, 5), packets::add); parser.reset();
        parser.feed(good, packets::add); assertEquals(4, packets.size());
    }
    @Test public void infoReadsVariableStringsAndResumeTail() {
        byte[] timezone = "Asia/Shanghai".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] plan = "无".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(46 + timezone.length + plan.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {0, 1, 1, 0, 2, 0, 0, 6}); b.position(30);
        b.put((byte)timezone.length).put(timezone).put((byte)plan.length).put(plan);
        b.put((byte)1).put((byte)2).putInt(123).putInt(EpaperProtocol.FRAME_SIZE).putInt(1000);
        EpaperProtocol.Info info = new EpaperProtocol.Info(b.array());
        assertTrue(info.uploadActive); assertEquals(123, info.photoId); assertEquals(1000, info.offset);
        assertEquals(2, info.slot); assertEquals(2, info.uploadSlot);
        byte[] broken = b.array().clone(); broken[30] = (byte)255;
        assertThrows(IllegalArgumentException.class, () -> new EpaperProtocol.Info(broken));
    }
}
